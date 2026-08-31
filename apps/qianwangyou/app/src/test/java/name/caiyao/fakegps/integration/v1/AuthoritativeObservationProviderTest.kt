package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1
import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_UID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthoritativeObservationProviderTest {

    @Test
    fun `owner away then restore inside raw read is rejected despite equal endpoint`() {
        val h = readyHarness("owner-away-restore")
        val before = h.tracker.snapshot().revision
        h.env.beforeObserveEffective = {
            h.authoritativeOracle.changed(
                ownerUid = 20_002,
                ownerPackage = "other.mock.owner",
            )
            h.authoritativeOracle.changed(
                ownerUid = ProviderHarness.QWY_UID,
                ownerPackage = ProviderHarness.QWY_PACKAGE,
            )
        }

        val observed = h.observeCurrent("owner-away-restore-observe")

        assertEquals(before + 1L, observed.environmentRevision)
        assertEquals(ContinuityCoverageV1.NONE.wire, observed.continuityCoverageWire)
        assertNull(observed.continuitySinceElapsedRealtimeMs)
    }

    @Test
    fun `provider disable then enable inside raw read is rejected`() {
        val h = readyHarness("provider-away-restore")
        val before = h.tracker.snapshot().revision
        h.env.beforeObserveEffective = {
            h.authoritativeOracle.changed(gpsEnabled = false)
            h.authoritativeOracle.changed(gpsEnabled = true)
        }

        val observed = h.observeCurrent("provider-away-restore-observe")

        assertEquals(before + 1L, observed.environmentRevision)
        assertEquals(ContinuityCoverageV1.NONE.wire, observed.continuityCoverageWire)
    }

    @Test
    fun `mutation still odd at POST is rejected and does not mint FULL`() {
        val h = readyHarness("odd-at-post")
        var token: AuthoritativeMutationToken? = null
        h.env.beforeObserveEffective = {
            token = h.authoritativeOracle.oracle.beginMutation()
        }

        val observed = h.observeCurrent("odd-at-post-observe")

        assertEquals(ContinuityCoverageV1.NONE.wire, observed.continuityCoverageWire)
        assertNull(observed.continuitySinceElapsedRealtimeMs)
        h.authoritativeOracle.oracle.finishMutation(
            checkNotNull(token),
            AuthoritativeMutationOutcome.PROVED_NO_OP,
            h.authoritativeOracle.currentState(),
        )
    }

    @Test
    fun `boot change between PRE and POST is rejected and ACKed fail closed`() {
        val h = readyHarness("boot-change")
        val pre = h.authoritativeOracle.oracle.snapshot()
        h.authoritativeOracle.scriptedSnapshots.addLast(pre)
        h.authoritativeOracle.scriptedSnapshots.addLast(
            pre.copy(bootId = "boot-after-restart", oracleInstanceId = "instance-after-restart"),
        )

        val observed = h.observeCurrent("boot-change-observe")

        assertEquals(ContinuityCoverageV1.NONE.wire, observed.continuityCoverageWire)
        assertNull(observed.continuitySinceElapsedRealtimeMs)
    }

    @Test
    fun `Binder failure on POST is NONE and a later stable window may recover`() {
        val h = readyHarness("post-read-failure")
        val stable = h.authoritativeOracle.oracle.snapshot()
        h.authoritativeOracle.scriptedSnapshots.addLast(stable)
        h.authoritativeOracle.failNextRead = true

        val failed = h.observeCurrent("post-read-failure-observe")
        assertEquals(ContinuityCoverageV1.NONE.wire, failed.continuityCoverageWire)

        h.env.beforeObserveEffective = null
        h.clock.advance(50L)
        h.observeCurrent("post-read-rebaseline")
        h.clock.advance(50L)
        val recovered = h.observeCurrent("post-read-recovered")
        assertEquals(ContinuityCoverageV1.FULL.wire, recovered.continuityCoverageWire)
    }

    @Test
    fun `ordinary raw refresh reads do not change authoritative sequence`() {
        val h = readyHarness("refresh-sequence")
        val before = h.authoritativeOracle.oracle.snapshot().sequence

        repeat(5) { index ->
            h.clock.advance(50L)
            h.observeCurrent("refresh-sequence-$index")
        }

        assertEquals(before, h.authoritativeOracle.oracle.snapshot().sequence)
    }

    private fun readyHarness(key: String): ProviderHarness {
        val h = ProviderHarness.create()
        h.pair()
        val receipt = h.apply(key = key)
        h.currentLeaseId = receipt.leaseId
        h.currentIntentHash = receipt.acceptedIntentHash
        // Apply's existing authoritative COMPLETE path establishes coverage;
        // this first stable observe also durably anchors the oracle cursor.
        h.observeCurrent("$key-baseline")
        return h
    }

    private fun ProviderHarness.observeCurrent(operationId: String) = handler.observe(
        AUTO_UID,
        ObserveRequestV1(
            leaseId = checkNotNull(currentLeaseId),
            operationId = operationId,
            expectedIntentHash = checkNotNull(currentIntentHash),
        ),
    )
}
