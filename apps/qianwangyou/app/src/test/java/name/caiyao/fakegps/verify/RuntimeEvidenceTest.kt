package name.caiyao.fakegps.verify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RuntimeEvidenceTest {
    private val request = ProbeRequest("request-1", "sha256:1111111111111111")

    @Test
    fun `probe lifecycle grammar carries correlation without field values`() {
        assertEquals(
            "event=requested requestId=request-1 fp=sha256:1111111111111111",
            RuntimeEvidence.probeRequested(request),
        )
        assertEquals(
            "event=started requestId=request-1 fp=sha256:1111111111111111",
            RuntimeEvidence.probeStarted(request),
        )
        assertEquals(
            "event=delivered requestId=request-1 fp=sha256:1111111111111111 fields=3",
            RuntimeEvidence.probeDelivered(request, 3),
        )
        assertEquals(
            "event=failed requestId=request-1 fp=sha256:1111111111111111 reason=TIMEOUT",
            RuntimeEvidence.probeFailed(request, ProbeFailure.TIMEOUT),
        )
        assertEquals(
            "event=ignored requestId=request-1 fp=sha256:1111111111111111 reason=STALE_RESULT",
            RuntimeEvidence.probeIgnored(request),
        )
        assertFalse(RuntimeEvidence.probeDelivered(request, 3).contains("22222"))
    }

    @Test
    fun `observer evidence records arm success, failure, and fallback`() {
        assertEquals(
            "FakeGPS-Hook: event=observer_armed process=com.example dir=/data/misc/uuid/prefs/pkg",
            RuntimeEvidence.observerArmed("com.example", "/data/misc/uuid/prefs/pkg"),
        )
        assertEquals(
            "FakeGPS-Hook: event=observer_arm_failed process=com.example error=SecurityException",
            RuntimeEvidence.observerArmFailed("com.example", "SecurityException"),
        )
        assertEquals(
            "FakeGPS-Hook: event=timer_fallback process=com.example intervalMs=30000",
            RuntimeEvidence.timerFallback("com.example", 30_000L),
        )
    }

    @Test
    fun `scheduler grammar records owner and real transitions only`() {
        assertEquals(
            "FakeGPS-Hook: event=scheduler_owned process=com.example intervalMs=30000",
            RuntimeEvidence.schedulerOwned("com.example", 30_000L),
        )
        assertEquals(
            "FakeGPS-Hook: event=interval_changed process=com.example fromMs=30000 toMs=5000",
            RuntimeEvidence.intervalChanged("com.example", 30_000L, 5_000L),
        )
    }

    @Test
    fun `fused discovery evidence records lifecycle without payload values`() {
        assertEquals(
            "FakeGPS-Hook: event=fused_factory_armed overloads=2",
            RuntimeEvidence.fusedFactoryArmed(2),
        )
        assertEquals(
            "FakeGPS-Hook: event=fused_client_discovered class=bkmc loader=12345",
            RuntimeEvidence.fusedClientDiscovered("bkmc", 12345),
        )
        assertEquals(
            "FakeGPS-Hook: event=fused_surface_hooked surface=LAST_LOCATION_TASK owner=bkmc",
            RuntimeEvidence.fusedSurfaceHooked("LAST_LOCATION_TASK", "bkmc"),
        )
        assertEquals(
            "FakeGPS-Hook: event=fused_client_rejected reason=NOT_ASSIGNABLE",
            RuntimeEvidence.fusedClientRejected("NOT_ASSIGNABLE"),
        )
        assertEquals(
            "FakeGPS-Hook: event=fused_surface_missing method=getLastLocation",
            RuntimeEvidence.fusedSurfaceMissing("getLastLocation"),
        )
        assertEquals(
            "FakeGPS-Hook: event=fused_delivery_summary task=bkww planned=3 installed=3 already=0 failed=0",
            RuntimeEvidence.fusedDeliverySummary("bkww", 3, 3, 0, 0, null),
        )
        assertEquals(
            "FakeGPS-Hook: event=fused_delivery_summary task=bkww planned=3 installed=1 already=1 failed=1 reason=IllegalStateException",
            RuntimeEvidence.fusedDeliverySummary("bkww", 3, 1, 1, 1, "IllegalStateException"),
        )
        assertEquals(
            "FakeGPS-Hook: event=fused_listener_wrapped task=bkwo",
            RuntimeEvidence.fusedListenerWrapped("bkwo"),
        )
    }

    @Test
    fun `per-delivery evidence separates what we delivered from what we intercepted`() {
        // class= describes the value the target app actually received.
        // input= describes what it would have received without the hook.
        // Steady healthy operation is class=EQUALS_PROFILE input=INPUT_DIFFERS_PROFILE:
        // we are delivering the profile AND actively displacing real updates.
        assertEquals(
            "FakeGPS-Hook: event=fused_delivered surface=LAST_LOCATION_TASK" +
                " class=EQUALS_PROFILE input=INPUT_DIFFERS_PROFILE deliveries=1",
            RuntimeEvidence.fusedDelivered(
                "LAST_LOCATION_TASK", "EQUALS_PROFILE", "INPUT_DIFFERS_PROFILE", 1,
            ),
        )
        assertEquals(
            "FakeGPS-Hook: event=fused_delivered surface=CURRENT_LOCATION_TASK" +
                " class=NOT_EQUAL input=INPUT_ABSENT deliveries=17",
            RuntimeEvidence.fusedDelivered(
                "CURRENT_LOCATION_TASK", "NOT_EQUAL", "INPUT_ABSENT", 17,
            ),
        )
        // Both axes stay value-free: the line answers the question without carrying the
        // coordinate that would answer it directly.
        assertFalse(
            RuntimeEvidence.fusedDelivered(
                "SYSTEM_LISTENER", "EQUALS_PROFILE", "INPUT_DIFFERS_PROFILE", 17,
            ).contains("22222"),
        )
    }
}
