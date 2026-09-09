package com.example.cellrebelauto.integration.v1

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.example.cellrebelauto.automation.ProviderPrincipal
import io.github.terryyyc.fakexxx.contract.v1.IEnvironmentMaintenanceV1
import io.github.terryyyc.fakexxx.contract.v1.MaintenanceContractV1
import io.github.terryyyc.fakexxx.contract.v1.MaintenanceResetErrorCodeV1
import io.github.terryyyc.fakexxx.contract.v1.MaintenanceResultKindV1
import io.github.terryyyc.fakexxx.contract.v1.MaintenanceResultV1
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Auto's client end of the environment MAINTENANCE contract v1 (#140 dual-app
 * quick reset) — the additive sibling of [EnvironmentControlClient]. One call:
 * [scheduleReset] asks the paired provider to productize its own schedule_reset
 * (generation+1 → first item, last-applied residue removed, effective profile
 * re-anchored and re-published), authorized by the SAME pairing principal the
 * control channel uses. No debug seam: this is a release-reachable contract
 * channel.
 *
 * Binding is the same explicit-ComponentName discipline as the control client
 * (security property, not convenience), and every way the call can end is
 * typed so an operator-facing notice can distinguish "provider refused the
 * reset" from "provider is not installed".
 */
class EnvironmentMaintenanceClient(private val context: Context) {

    /** Every way a scheduleReset() can end, named. */
    sealed interface ResetResult {
        /** Reset committed AND the effective profile was re-published. */
        data class ResetDone(
            val scheduleVersionAfter: Long,
            val republishedProfileRef: String,
            val providerPackage: String,
        ) : ResetResult

        /**
         * HONEST partial: the schedule reset committed (versionAfter is the
         * committed generation) but the re-publish failed.
         */
        data class PublishFailed(
            val scheduleVersionAfter: Long,
            val providerPackage: String,
        ) : ResetResult

        /** Typed provider refusal (lease blocking / no schedule / corrupt / auth). */
        data class Refused(
            val errorCodeWire: Int,
            val diagnostic: String?,
            val providerPackage: String,
        ) : ResetResult

        /** Carrier failed validation (schema/kind/field invariants) — never guessed into a success. */
        data class Anomalous(val reason: String, val providerPackage: String) : ResetResult

        /** No provider package on the device, or none whose service accepted a bind. */
        data class NotBindable(val triedPackages: List<String>) : ResetResult

        /** Bound, but the call did not return in time — a hung provider is not a missing one. */
        data class TimedOut(val providerPackage: String, val waitedMs: Long) : ResetResult
    }

    /** Blocking call — the caller owns the thread (MainViewModel runs it on IO). */
    fun scheduleReset(timeoutMs: Long = 5_000L): ResetResult = tryPackage(PROVIDER_PACKAGE, timeoutMs)

    private fun tryPackage(providerPackage: String, timeoutMs: Long): ResetResult {
        val latch = CountDownLatch(1)
        val binderRef = AtomicReference<IBinder?>(null)

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binderRef.set(service)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binderRef.set(null)
                latch.countDown()
            }

            override fun onBindingDied(name: ComponentName?) {
                binderRef.set(null)
                latch.countDown()
            }

            override fun onNullBinding(name: ComponentName?) {
                binderRef.set(null)
                latch.countDown()
            }
        }

        val intent = Intent().setComponent(
            ComponentName(providerPackage, MaintenanceContractV1.SERVICE_CLASS_NAME)
        )

        val bindRequested = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            return ResetResult.Refused(
                MaintenanceResetErrorCodeV1.CALLER_NOT_ALLOWED.wire,
                "bind refused: ${e.message}",
                providerPackage,
            )
        }

        if (!bindRequested) {
            runCatching { context.unbindService(connection) }
            return ResetResult.NotBindable(listOf(providerPackage))
        }

        return try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return ResetResult.TimedOut(providerPackage, timeoutMs)
            }
            val live = binderRef.get() ?: return ResetResult.NotBindable(listOf(providerPackage))

            val service = IEnvironmentMaintenanceV1.Stub.asInterface(live)
            try {
                validate(service.scheduleReset(), providerPackage)
            } catch (t: Throwable) {
                ResetResult.Anomalous(
                    "maintenance call failed: ${t.javaClass.simpleName}: ${t.message}",
                    providerPackage,
                )
            }
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }

    /**
     * Carrier validation — schema version, result kind, and the invariant
     * tuples: OK carries version+ref; PUBLISH_FAILED carries the committed
     * version; every other ERROR carries neither. A violating carrier is an
     * anomaly, never laundered into success. Pure, so the JVM lane pins it.
     */
    internal fun validate(result: MaintenanceResultV1?, providerPackage: String): ResetResult {
        if (result == null) return ResetResult.Anomalous("null carrier", providerPackage)
        if (result.resultSchemaVersion != MaintenanceResultV1.SCHEMA_VERSION) {
            return ResetResult.Anomalous(
                "schema ${result.resultSchemaVersion} != ${MaintenanceResultV1.SCHEMA_VERSION}",
                providerPackage,
            )
        }
        val kind = result.resultKindOrNull()
            ?: return ResetResult.Anomalous("unknown resultKind ${result.resultKindWire}", providerPackage)
        return when (kind) {
            MaintenanceResultKindV1.OK -> {
                val version = result.scheduleVersionAfter
                val ref = result.republishedProfileRef
                if (version == null || ref == null) {
                    ResetResult.Anomalous("OK payload incomplete", providerPackage)
                } else {
                    ResetResult.ResetDone(version, ref, providerPackage)
                }
            }
            MaintenanceResultKindV1.ERROR -> {
                val code = result.errorCodeOrNull()
                    ?: return ResetResult.Anomalous(
                        "ERROR without a known code ${result.errorCodeWire}",
                        providerPackage,
                    )
                when (code) {
                    MaintenanceResetErrorCodeV1.PUBLISH_FAILED -> {
                        val version = result.scheduleVersionAfter
                        if (version == null) {
                            ResetResult.Anomalous("PUBLISH_FAILED without the committed version", providerPackage)
                        } else {
                            ResetResult.PublishFailed(version, providerPackage)
                        }
                    }
                    else -> {
                        if (result.scheduleVersionAfter != null || result.republishedProfileRef != null) {
                            ResetResult.Anomalous("ERROR carries payload fields", providerPackage)
                        } else {
                            ResetResult.Refused(code.wire, result.diagnosticMessage, providerPackage)
                        }
                    }
                }
            }
        }
    }

    companion object {
        /**
         * The same build-selected provider principal the control client binds —
         * the maintenance channel must never answer for a different identity
         * than the engine's trust gate.
         */
        val PROVIDER_PACKAGE: String = ProviderPrincipal.selected
    }
}
