package com.example.cellrebelauto.integration.v1

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.terryyyc.fakexxx.contract.v1.MaintenanceResetErrorCodeV1
import io.github.terryyyc.fakexxx.contract.v1.MaintenanceResultV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #140: the Auto client's carrier validation oracle. The client is the only
 * thing between a (possibly hostile or buggy) provider carrier and the
 * operator-facing summary, so every invariant tuple is pinned: OK must carry
 * version+ref, PUBLISH_FAILED must carry the committed version, plain refusals
 * carry neither, and any violation lands as Anomalous — never laundered into a
 * success, never a crash.
 */
@RunWith(RobolectricTestRunner::class)
class EnvironmentMaintenanceClientValidateTest {

    // validate() never binds; the context is only a constructor formality.
    private val client = EnvironmentMaintenanceClient(
        ApplicationProvider.getApplicationContext<Application>()
    )

    @Test
    fun `OK with version and ref validates to ResetDone`() {
        val result = client.validate(
            MaintenanceResultV1.resetDone(9L, "profile-3"),
            "provider.pkg",
        )
        assertEquals(
            EnvironmentMaintenanceClient.ResetResult.ResetDone(9L, "profile-3", "provider.pkg"),
            result,
        )
    }

    @Test
    fun `PUBLISH_FAILED with the committed version validates to PublishFailed`() {
        val result = client.validate(
            MaintenanceResultV1.failure(
                MaintenanceResetErrorCodeV1.PUBLISH_FAILED.wire,
                "transport refused",
                scheduleVersionAfter = 8L,
            ),
            "provider.pkg",
        )
        assertEquals(
            EnvironmentMaintenanceClient.ResetResult.PublishFailed(8L, "provider.pkg"),
            result,
        )
    }

    @Test
    fun `typed refusals validate to Refused with the wire code`() {
        val result = client.validate(
            MaintenanceResultV1.failure(
                MaintenanceResetErrorCodeV1.BLOCKED_BY_LEASE.wire,
                "lease active",
            ),
            "provider.pkg",
        )
        assertTrue(result is EnvironmentMaintenanceClient.ResetResult.Refused)
        assertEquals(
            MaintenanceResetErrorCodeV1.BLOCKED_BY_LEASE.wire,
            (result as EnvironmentMaintenanceClient.ResetResult.Refused).errorCodeWire,
        )
    }

    @Test
    fun `invariant violations are anomalies`() {
        // OK missing its payload.
        assertTrue(
            client.validate(
                MaintenanceResultV1(1, 1, null, null, null, null),
                "p",
            ) is EnvironmentMaintenanceClient.ResetResult.Anomalous,
        )
        // PUBLISH_FAILED without the committed version.
        assertTrue(
            client.validate(
                MaintenanceResultV1(
                    1,
                    2,
                    MaintenanceResetErrorCodeV1.PUBLISH_FAILED.wire,
                    null,
                    null,
                    null,
                ),
                "p",
            ) is EnvironmentMaintenanceClient.ResetResult.Anomalous,
        )
        // Plain ERROR carrying payload fields.
        assertTrue(
            client.validate(
                MaintenanceResultV1(
                    1,
                    2,
                    MaintenanceResetErrorCodeV1.NO_SCHEDULE.wire,
                    null,
                    5L,
                    null,
                ),
                "p",
            ) is EnvironmentMaintenanceClient.ResetResult.Anomalous,
        )
        // Unknown schema / unknown kind.
        assertTrue(
            client.validate(
                MaintenanceResultV1(99, 1, null, null, 1L, "r"),
                "p",
            ) is EnvironmentMaintenanceClient.ResetResult.Anomalous,
        )
        assertTrue(
            client.validate(
                MaintenanceResultV1(1, 42, null, null, 1L, "r"),
                "p",
            ) is EnvironmentMaintenanceClient.ResetResult.Anomalous,
        )
        // Null carrier.
        assertTrue(
            client.validate(null, "p") is EnvironmentMaintenanceClient.ResetResult.Anomalous,
        )
    }
}
