package name.caiyao.fakegps.integration.v1

import android.app.Service
import android.content.Intent
import android.os.IBinder
import io.github.terryyyc.fakexxx.contract.v1.IEnvironmentMaintenanceV1
import io.github.terryyyc.fakexxx.contract.v1.MaintenanceResetErrorCodeV1
import io.github.terryyyc.fakexxx.contract.v1.MaintenanceResultV1

/**
 * Binder entry point of the #140 environment MAINTENANCE contract v1 — the
 * additive sibling of [EnvironmentControlService]. One operator-recovery
 * command: scheduleReset() (schedule_reset productized — generation+1, pointer
 * to the first item, exhausted cleared, last-applied residue removed, effective
 * profile re-anchored and re-published).
 *
 * Same discipline as the control service:
 *  - exported across apps with NO intent-filter, so only an explicit
 *    ComponentName reaches it; every call resolves its real caller from
 *    Binder.getCallingUid() and must match an operator-approved pairing via
 *    the SAME [CallerAuthorizer] (NOT_PAIRED / CALLER_NOT_ALLOWED travel as
 *    typed carrier errors, never as a reset);
 *  - Binder glue only: every rule lives in [EnvironmentControlHandler]
 *    (owner-fenced), so the JVM lane tests the behavior without Android;
 *  - a ContractException from authorization is an EXPECTED business failure
 *    and lands in the carrier; anything else propagates as a transport
 *    failure and must not be laundered into a business answer;
 *  - no android:process: the durable stores are single-writer per process.
 *
 * #140 快速重置的契约通道：Auto 一键重置经此命令联动 QWY；不走 debug seam。
 */
class EnvironmentMaintenanceService : Service() {

    private val binder: IEnvironmentMaintenanceV1.Stub = object : IEnvironmentMaintenanceV1.Stub() {
        override fun scheduleReset(): MaintenanceResultV1 = withProviderBinderIdentity { callerUid ->
            toMaintenanceResult {
                ProviderRuntime.handler(this@EnvironmentMaintenanceService)
                    .quickResetScheduleForCaller(callerUid)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** §8.4 clean-shutdown evidence, same as the control service. */
    override fun onDestroy() {
        ProviderRuntime.recordCleanShutdown()
        super.onDestroy()
    }
}

/**
 * Outcome→carrier mapping plus the KB-7 exception→carrier rule, as pure
 * top-level functions so the unit lane can pin them (the service class itself
 * is Android-bound).
 *
 * Authorization codes 1/2 mirror the control channel's §6.5 values (same
 * CallerAuthorizer, same meaning); any other ContractErrorCodeV1 reaching this
 * surface is clamped to CALLER_NOT_ALLOWED rather than leaking a control-only
 * wire value onto the maintenance channel.
 */
fun toMaintenanceResult(
    block: () -> QuickResetScheduleOutcome,
): MaintenanceResultV1 =
    try {
        when (val outcome = block()) {
            is QuickResetScheduleOutcome.Reset -> MaintenanceResultV1.resetDone(
                outcome.scheduleVersionAfter,
                outcome.republishedProfileRef,
            )
            is QuickResetScheduleOutcome.ResetButPublishFailed -> MaintenanceResultV1.failure(
                MaintenanceResetErrorCodeV1.PUBLISH_FAILED.wire,
                "schedule reset committed (V${outcome.scheduleVersionAfter}) but the effective " +
                    "profile could not be re-published",
                scheduleVersionAfter = outcome.scheduleVersionAfter,
            )
            QuickResetScheduleOutcome.BlockedByLease -> MaintenanceResultV1.failure(
                MaintenanceResetErrorCodeV1.BLOCKED_BY_LEASE.wire,
                "a non-converged lease still blocks the schedule",
            )
            QuickResetScheduleOutcome.NoSchedule -> MaintenanceResultV1.failure(
                MaintenanceResetErrorCodeV1.NO_SCHEDULE.wire,
                "no schedule to reset",
            )
            QuickResetScheduleOutcome.CorruptScheduleState -> MaintenanceResultV1.failure(
                MaintenanceResetErrorCodeV1.CORRUPT_SCHEDULE_STATE.wire,
                "durable schedule state is corrupt — fail-closed",
            )
            QuickResetScheduleOutcome.WriteFailed -> MaintenanceResultV1.failure(
                MaintenanceResetErrorCodeV1.WRITE_FAILED.wire,
                "the reset commit was not durable",
            )
        }
    } catch (e: ContractException) {
        val wire = when (e.code) {
            io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1.NOT_PAIRED ->
                MaintenanceResetErrorCodeV1.NOT_PAIRED.wire
            else -> MaintenanceResetErrorCodeV1.CALLER_NOT_ALLOWED.wire
        }
        MaintenanceResultV1.failure(wire, e.message)
    }
