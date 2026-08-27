package name.caiyao.fakegps.integration.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * P10DBG-COLLECTOR-V1 durable-snapshot honesty tests (qwy side).
 *
 * These are the tests behind hard constraint 3 of the dispatch ("注入后要能从
 * 持久状态读回结果，不能只看日志") and its mutation ("触发了但状态没落盘"
 * must not be able to false-green):
 *
 *   1. DISK-BACKED: a lease committed through a REAL FileDurableKv (temp dir)
 *      is visible to [QwyDurableSnapshot.capture]; nothing else is.
 *   2. PASSIVE: capture does not modify the durable file — the byte image
 *      before and after is identical. Readback that wrote would run §8.4
 *      recovery and destroy the evidence being read.
 *   3. DRIFT-GUARDED: the directory literal matches the one ProviderRuntime
 *      writes to (source-scanned, because the production literal is private).
 */
class QwyDurableSnapshotTest {

    private object TestClock : MonotonicClock {
        override fun elapsedRealtimeMs(): Long = 1_000L
        override fun epochMs(): Long = 1_778_000_000_000L
    }

    private fun tempDir(): File = Files.createTempDirectory("p10-qwy-snap").toFile()

    private fun activeLease(leaseId: String, caller: String = "com.example.cellrebelauto"): LeaseRecord =
        LeaseRecord(
            leaseId = leaseId,
            callerApplicationId = caller,
            callerSignerDigest = "deadbeef",
            acceptedIntentHash = "hash-1",
            state = LeaseState.ACTIVE,
            applyIdempotencyKey = "ap-1",
            startingEnvironmentRevision = 1L,
            deadlineElapsedRealtimeMs = Long.MAX_VALUE,
            applyOwnerGeneration = 1L,
            earnedScheduleRef = null,
        )

    @Test
    fun captureReadsCommittedDiskState() {
        val dir = tempDir()
        val kv = FileDurableKv(dir)
        EnvironmentLeaseStore(kv, TestClock).put(activeLease("lease-1"))

        val snap = QwyDurableSnapshot.capture(dir)
        assertEquals("lease-1", snap.lease.currentLeaseId)
        assertEquals("ACTIVE", snap.lease.leaseState)
        assertEquals("com.example.cellrebelauto", snap.lease.callerApplicationId)
    }

    @Test
    fun captureReadsAbsenceAsAbsence() {
        val dir = tempDir() // nothing ever written
        val snap = QwyDurableSnapshot.capture(dir)
        assertNull(snap.lease.currentLeaseId)
        assertNull(snap.lease.leaseState)
        assertTrue(snap.pendingCallers.isEmpty())
        assertTrue(snap.auditTail.isEmpty())
    }

    /**
     * MUTATION KILLER — "fired but state never persisted".
     *
     * A durable-file capture must reflect DISK, never an actor's in-memory
     * claim. Simulate the mutation: a SECOND view (separate DurableKv over a
     * different store) claims a lease exists; the durable directory never got
     * the bytes. capture() must report absence — if it read any in-process
     * cache or the acting store instead of the file, this test fails.
     */
    @Test
    fun captureIgnoresStateThatNeverReachedTheDisk() {
        val dir = tempDir()
        // The "actor": commits its state to a DIFFERENT durable directory that
        // the snapshot is never pointed at — simulating a fire whose bytes
        // never landed in the durable store under test.
        val actorDir = tempDir()
        val actorKv = FileDurableKv(actorDir)
        EnvironmentLeaseStore(actorKv, TestClock).put(activeLease("lease-never-durable"))

        val snap = QwyDurableSnapshot.capture(dir)
        assertNull(
            "a lease that never reached the durable directory must read back absent — " +
                "otherwise 'fired but not persisted' can false-green",
            snap.lease.currentLeaseId,
        )
    }

    /**
     * PASSIVITY — readback must not write.
     *
     * capture() constructs a fresh FileDurableKv; if any code path it touches
     * wrote (init mkdirs aside, which is a no-op on an existing dir), the
     * durable bytes would change during evidence collection. Byte-compare.
     */
    @Test
    fun captureLeavesTheDurableFileByteIdentical() {
        val dir = tempDir()
        val kv = FileDurableKv(dir)
        EnvironmentLeaseStore(kv, TestClock).put(activeLease("lease-1"))
        val file = File(dir, "environment-control-v1.kv")
        val before = file.readBytes()

        QwyDurableSnapshot.capture(dir)
        QwyDurableSnapshot.capture(dir, "com.example.cellrebelauto", "deadbeef")

        val after = file.readBytes()
        assertTrue(
            "capture() must not modify the durable store — readback that writes " +
                "runs §8.4 recovery and destroys the evidence",
            before.contentEquals(after),
        )
    }

    @Test
    fun revokeReadbackProvesPairingInactiveAndAuditRow() {
        val dir = tempDir()
        val kv = FileDurableKv(dir)
        // Approve a candidate, then revoke via the real store paths.
        val pairing = DurablePairingStore(kv)
        pairing.recordCandidate(
            PendingPairingCandidate(
                callerApplicationId = "com.example.cellrebelauto",
                currentSignerDigest = "deadbeef",
                observedVersionCode = 1L,
                firstSeenAtElapsedRealtimeMs = 1L,
            )
        )
        pairing.approve(pairing.pendingCandidates().first(), 2L)
        pairing.revoke("com.example.cellrebelauto", "deadbeef", 3L)
        DurableIntegrationAuditStore(kv, TestClock)
            .append("caller_revoked", callerApplicationId = "com.example.cellrebelauto")

        val snap = QwyDurableSnapshot.capture(dir, "com.example.cellrebelauto", "deadbeef")
        assertEquals("§5C durable proof: pairing must read inactive after revoke",
            false, snap.pairingStillActive)
        assertEquals("§5C durable proof: the caller_revoked audit row must exist",
            true, snap.revokeAudited)
        assertNotNull(snap.auditTail.lastOrNull { it.event == "caller_revoked" })
    }

    /**
     * DRIFT GUARD — the snapshot must read the directory the production
     * runtime writes. The literal in ProviderRuntime.build is private, so this
     * pins the equality source-scan style (same lane as the surface guards).
     */
    @Test
    fun durableDirMatchesTheProductionRuntimeDirectory() {
        val moduleRoot = sequenceOf(File("."), File("app"), File("../app"))
            .map { it.absoluteFile.normalize() }
            .firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
            ?: error("cannot locate the app module root")
        val runtimeSource = File(
            moduleRoot,
            "src/main/java/name/caiyao/fakegps/integration/v1/ProviderRuntime.kt",
        ).readText()
        assertTrue(
            "ProviderRuntime must still write to '${QwyDurableSnapshot.DURABLE_DIR_NAME}' — " +
                "if the production directory moved, update QwyDurableSnapshot or every " +
                "collector readback silently reads the wrong (empty) directory",
            runtimeSource.contains("\"${QwyDurableSnapshot.DURABLE_DIR_NAME}\""),
        )
        assertFalse(
            "production main must not reference the collector (debug-only boundary)",
            runtimeSource.contains("FaultCollectorActivity"),
        )
    }
}
