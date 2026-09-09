package io.github.terryyyc.fakexxx.contract.v1

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Public result carrier for the [IEnvironmentMaintenanceV1] surface.
 *
 * Same discipline as [EnvironmentControlResultV1]: business failures are DATA
 * (resultKindWire = ERROR + a frozen [MaintenanceResetErrorCodeV1] wire), never
 * hidden framework exceptions; transport failures (binder death, RemoteException)
 * remain outside this carrier. kotlin-parcelize reads fields positionally, so the
 * declaration order below is part of the wire contract — it is frozen by class
 * name and field order; incompatible additions require a new carrier/interface
 * version rather than appending fields here.
 *
 * Invariants a conforming peer can rely on:
 *  - OK: [errorCodeWire] is null, [scheduleVersionAfter] and
 *    [republishedProfileRef] are both non-null.
 *  - ERROR with PUBLISH_FAILED: [errorCodeWire] = 7, [scheduleVersionAfter] is
 *    non-null (the reset committed), [republishedProfileRef] is null.
 *  - every other ERROR: [errorCodeWire] non-null and both payload fields null.
 */
@Parcelize
data class MaintenanceResultV1(
    val resultSchemaVersion: Int,
    /** [MaintenanceResultKindV1] wire code. */
    val resultKindWire: Int,
    /** [MaintenanceResetErrorCodeV1] wire code; non-null only when [resultKindWire] is ERROR. */
    val errorCodeWire: Int?,
    /** Human diagnosis only. No machine decision may depend on this string. */
    val diagnosticMessage: String?,
    /** The committed schedule generation AFTER the reset; null when nothing was reset. */
    val scheduleVersionAfter: Long?,
    /** The re-published effective profile ref (e.g. "profile-12"); null when publish failed/skipped. */
    val republishedProfileRef: String?,
) : Parcelable {
    fun resultKindOrNull(): MaintenanceResultKindV1? = MaintenanceResultKindV1.fromWire(resultKindWire)

    fun errorCodeOrNull(): MaintenanceResetErrorCodeV1? =
        errorCodeWire?.let(MaintenanceResetErrorCodeV1::fromWireOrInternalFailure)

    companion object {
        const val SCHEMA_VERSION: Int = 1

        fun resetDone(scheduleVersionAfter: Long, republishedProfileRef: String): MaintenanceResultV1 =
            MaintenanceResultV1(
                resultSchemaVersion = SCHEMA_VERSION,
                resultKindWire = MaintenanceResultKindV1.OK.wire,
                errorCodeWire = null,
                diagnosticMessage = null,
                scheduleVersionAfter = scheduleVersionAfter,
                republishedProfileRef = republishedProfileRef,
            )

        fun failure(
            errorCodeWire: Int,
            diagnosticMessage: String? = null,
            scheduleVersionAfter: Long? = null,
        ): MaintenanceResultV1 = MaintenanceResultV1(
            resultSchemaVersion = SCHEMA_VERSION,
            resultKindWire = MaintenanceResultKindV1.ERROR.wire,
            errorCodeWire = errorCodeWire,
            diagnosticMessage = diagnosticMessage,
            scheduleVersionAfter = scheduleVersionAfter,
            republishedProfileRef = null,
        )
    }
}
