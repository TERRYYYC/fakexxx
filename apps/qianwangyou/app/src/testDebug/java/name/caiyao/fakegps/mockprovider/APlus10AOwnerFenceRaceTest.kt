package name.caiyao.fakegps.mockprovider

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import name.caiyao.fakegps.integration.v1.AdvancePointerOutcome
import name.caiyao.fakegps.integration.v1.ApplyOutcome
import name.caiyao.fakegps.integration.v1.CleanupOutcome
import name.caiyao.fakegps.integration.v1.EffectiveEnvironment
import name.caiyao.fakegps.integration.v1.FileDurableKv
import name.caiyao.fakegps.integration.v1.MonotonicClock
import name.caiyao.fakegps.integration.v1.PackageIdentityResolver
import name.caiyao.fakegps.integration.v1.ProviderRuntime
import name.caiyao.fakegps.integration.v1.QwyEnvironment
import name.caiyao.fakegps.integration.v1.RevisionBumpReason
import name.caiyao.fakegps.integration.v1.ScheduleSnapshot
import name.caiyao.fakegps.integration.v1.SignerLookup
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R6 P1 — latch-driven owner-race regression.
 *
 * The seed's serialization claim is: holding [APlus10AOwnerFence.lockOf] on a
 * REAL handler excludes every fenced owner operation. This test proves it at
 * runtime against a real EnvironmentControlHandler (built via the internal
 * ProviderRuntime.compose over a temp FileDurableKv):
 *
 *   thread A acquires lockOf(handler) and holds it behind a latch;
 *   thread B calls a REAL fenced op (runRevokedLeaseCleanup — fenced, needs
 *   no caller identity); the test asserts B stays BLOCKED while A holds the
 *   monitor and completes only after A releases.
 *
 * Mutation-sensitive by construction: renaming the private `ownerLock` field
 * makes lockOf throw; pointing lockOf at any OTHER object lets B finish while
 * A holds — both turn this red. This is the runtime half of the drift pin
 * (the surface guard scans the production source for the field name).
 */
class APlus10AOwnerFenceRaceTest {

    private object TestClock : MonotonicClock {
        override fun elapsedRealtimeMs(): Long = 1_000L
        override fun epochMs(): Long = 1_778_000_000_000L
    }

    private object NoResolver : PackageIdentityResolver {
        override fun packagesForUid(uid: Int): List<String> = emptyList()
        override fun signerLookup(applicationId: String): SignerLookup? = null
    }

    private object InertEnvironment : QwyEnvironment {
        override fun scheduleSnapshot(): ScheduleSnapshot? = null
        override fun advancePointer(fromItemId: String): AdvancePointerOutcome =
            AdvancePointerOutcome.Exhausted(versionAfter = 1L)
        override fun applyEnvironment(intent: EnvironmentIntentV1): ApplyOutcome =
            throw UnsupportedOperationException("not exercised")
        override fun cleanup(leaseId: String): CleanupOutcome = CleanupOutcome.Complete
        override fun observeEffective(): EffectiveEnvironment =
            throw UnsupportedOperationException("not exercised")
        override fun scheduleDecisionWire(scheduleRef: String): Int = 0
        override fun achievableVerificationLevelWire(): Int = 0
        override fun setRelevantChangeListener(listener: (RevisionBumpReason) -> Unit) {}
    }

    @Test
    fun holdingTheReflectedLockBlocksARealFencedOp() {
        val kv = FileDurableKv(Files.createTempDirectory("fence-race").toFile())
        val handler = ProviderRuntime.compose(
            kv = kv, clock = TestClock, resolver = NoResolver, environment = InertEnvironment,
        )
        val lock = APlus10AOwnerFence.lockOf(handler)

        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bFinished = AtomicBoolean(false)

        val a = Thread {
            synchronized(lock) {
                held.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }.apply { start() }
        assertTrue("holder thread must acquire the monitor", held.await(5, TimeUnit.SECONDS))

        val b = Thread {
            handler.runRevokedLeaseCleanup() // REAL fenced op (no caller identity needed)
            bFinished.set(true)
        }.apply { start() }

        Thread.sleep(400)
        assertFalse(
            "a real fenced owner op must BLOCK while the seed holds lockOf(handler) — " +
                "if this finished, lockOf is not the owner monitor (drift or split lock)",
            bFinished.get(),
        )

        release.countDown()
        b.join(5_000)
        a.join(5_000)
        assertTrue("the fenced op must complete once the seed releases the monitor", bFinished.get())
    }

    @Test
    fun withoutTheLockTheFencedOpCompletesPromptly() {
        // Sanity leg: proves the blocking above is caused by the monitor, not
        // by the op hanging on its own.
        val kv = FileDurableKv(Files.createTempDirectory("fence-race2").toFile())
        val handler = ProviderRuntime.compose(
            kv = kv, clock = TestClock, resolver = NoResolver, environment = InertEnvironment,
        )
        val done = AtomicBoolean(false)
        val t = Thread { handler.runRevokedLeaseCleanup(); done.set(true) }.apply { start() }
        t.join(5_000)
        assertTrue("the fenced op must complete promptly when the monitor is free", done.get())
    }
}
