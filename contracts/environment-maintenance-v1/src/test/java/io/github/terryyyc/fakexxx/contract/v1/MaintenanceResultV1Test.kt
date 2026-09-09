package io.github.terryyyc.fakexxx.contract.v1

import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Wire pins for the maintenance carrier (#140): frozen wire codes, invariant
 * tuples (OK vs ERROR vs PUBLISH_FAILED partial), and a real Parcel round trip —
 * kotlin-parcelize is positional, so the round trip is the schema's proof.
 */
@RunWith(RobolectricTestRunner::class)
class MaintenanceResultV1Test {

    @Test
    fun `wire codes are frozen`() {
        assertEquals(1, MaintenanceResultKindV1.OK.wire)
        assertEquals(2, MaintenanceResultKindV1.ERROR.wire)
        assertEquals(1, MaintenanceResetErrorCodeV1.NOT_PAIRED.wire)
        assertEquals(2, MaintenanceResetErrorCodeV1.CALLER_NOT_ALLOWED.wire)
        assertEquals(3, MaintenanceResetErrorCodeV1.BLOCKED_BY_LEASE.wire)
        assertEquals(4, MaintenanceResetErrorCodeV1.NO_SCHEDULE.wire)
        assertEquals(5, MaintenanceResetErrorCodeV1.CORRUPT_SCHEDULE_STATE.wire)
        assertEquals(6, MaintenanceResetErrorCodeV1.WRITE_FAILED.wire)
        assertEquals(7, MaintenanceResetErrorCodeV1.PUBLISH_FAILED.wire)
        // NOTE: codes 1/2 deliberately mirror the control channel's §6.5 values
        // (same CallerAuthorizer, same meaning). The cross-module pin lives in
        // the QWY test lane, which sees both contract modules; this module
        // intentionally does not depend on environment-control-v1.
    }

    @Test
    fun `OK carries version and ref and no error`() {
        val result = MaintenanceResultV1.resetDone(9L, "profile-12")
        assertEquals(MaintenanceResultKindV1.OK, result.resultKindOrNull())
        assertNull(result.errorCodeWire)
        assertEquals(9L, result.scheduleVersionAfter)
        assertEquals("profile-12", result.republishedProfileRef)
    }

    @Test
    fun `PUBLISH_FAILED is a partial success carrying the committed generation`() {
        val result = MaintenanceResultV1.failure(
            MaintenanceResetErrorCodeV1.PUBLISH_FAILED.wire,
            "hook transport refused",
            scheduleVersionAfter = 9L,
        )
        assertEquals(MaintenanceResultKindV1.ERROR, result.resultKindOrNull())
        assertEquals(MaintenanceResetErrorCodeV1.PUBLISH_FAILED, result.errorCodeOrNull())
        assertEquals(9L, result.scheduleVersionAfter)
        assertNull(result.republishedProfileRef)
    }

    @Test
    fun `plain refusal carries no payload fields`() {
        val result = MaintenanceResultV1.failure(
            MaintenanceResetErrorCodeV1.BLOCKED_BY_LEASE.wire,
            "lease still active",
        )
        assertNull(result.scheduleVersionAfter)
        assertNull(result.republishedProfileRef)
    }

    @Test
    fun `parcel round trip preserves every field positionally`() {
        val original = MaintenanceResultV1.resetDone(42L, "profile-7")
        val back = regenerate(original)
        assertEquals(original, back)

        val failure = MaintenanceResultV1.failure(
            MaintenanceResetErrorCodeV1.NOT_PAIRED.wire,
            "uid not paired",
        )
        assertEquals(failure, regenerate(failure))
    }

    private fun regenerate(result: MaintenanceResultV1): MaintenanceResultV1 {
        val parcel = Parcel.obtain()
        try {
            // writeParcelable writes the creator too — that IS what crosses Binder.
            parcel.writeParcelable(result, 0)
            parcel.setDataPosition(0)
            @Suppress("DEPRECATION")
            return parcel.readParcelable(MaintenanceResultV1::class.java.classLoader)!!
        } finally {
            parcel.recycle()
        }
    }
}
