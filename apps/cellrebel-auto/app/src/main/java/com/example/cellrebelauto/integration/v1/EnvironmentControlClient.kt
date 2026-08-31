package com.example.cellrebelauto.integration.v1

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.example.cellrebelauto.automation.ProviderPrincipal
import com.example.cellrebelauto.recovery.ContractResponseValidator
import com.example.cellrebelauto.recovery.ContractResponseValidator.ValidatedContractResponse
import io.github.terryyyc.fakexxx.contract.v1.CapabilitySnapshotV1
import io.github.terryyyc.fakexxx.contract.v1.ContractV1
import io.github.terryyyc.fakexxx.contract.v1.IEnvironmentControlV1
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Auto's client end of the Environment Control contract v1 (§6.1).
 *
 * WHY THIS EXISTS AND WHY IT IS SMALL
 * -----------------------------------
 * Until this file, the contract module was a DEPENDENCY of this app and nothing
 * more: `build.gradle.kts` pulled in `:environment-control-v1`, the manifest
 * declared `<queries>` visibility for both provider package names, and zero
 * Kotlin files referenced a single contract type. Sixty commits and four days of
 * work had gone into the provider side; the caller side had never been written.
 * So the "double-app integration" of §7 was not blocked by governance — it was
 * unreachable, because one end of the wire did not exist.
 *
 * This class is deliberately the SMALLEST thing that proves the wire is real:
 * bind, call [discover], report. No apply, no lease, no advance. Those are
 * meaningless until a handshake is demonstrated on a device, and each of them
 * would add a failure mode that makes a failed handshake harder to read.
 *
 * WHAT A SUCCESSFUL discover() ACTUALLY PROVES
 * -------------------------------------------
 * It is a walking skeleton, not a formality. One green call establishes, on real
 * hardware and in one shot:
 *  - the provider service is reachable and exported correctly (§6.1);
 *  - Binder transport carries the frozen parcelables in both directions;
 *  - the caller's UID resolves to exactly one package with a readable signer,
 *    and pairing accepts or fail-closes on it (§6.5) — the part the JVM lane
 *    provably cannot cover, since the thing under test is PackageManager itself;
 *  - protocol versions agree (§6.8 handshake).
 * A failure is equally informative, which is why the outcome below is typed
 * rather than a boolean.
 *
 * BINDING IS EXPLICIT, NOT DISCOVERED BY INTENT
 * ---------------------------------------------
 * The provider declares the service exported with NO intent-filter, so only an
 * explicit ComponentName can reach it. That is a security property, not an
 * inconvenience: an implicit intent could be intercepted by any app claiming the
 * action, and this contract's whole trust model rests on both ends knowing
 * exactly who they are talking to.
 *
 * The provider's debug build carries an `applicationIdSuffix ".bench"`, so the
 * package name differs between debug and release. The caller selects exactly one
 * identity before constructing this client. A failed selected bind is reported as
 * such; trying the sibling would silently change principals and is forbidden.
 */
class EnvironmentControlClient(
    private val context: Context,
    providerApplicationId: String = ProviderPrincipal.selected,
) {
    val targetApplicationId: String =
        ProviderPrincipal.requireKnownApplicationId(providerApplicationId)

    /**
     * Every way this call can end, named. A boolean would collapse "provider is
     * not installed" into "provider rejected you", and those demand opposite
     * responses from an operator standing in front of the device.
     */
    sealed interface HandshakeResult {
        data class Connected(
            val providerPackage: String,
            val snapshot: CapabilitySnapshotV1,
        ) : HandshakeResult

        /** No provider package on the device, or none whose service accepted a bind. */
        data class NotBindable(val triedPackages: List<String>) : HandshakeResult

        /** Bound, but the call did not return in time — a hung provider is not a missing one. */
        data class TimedOut(val providerPackage: String, val waitedMs: Long) : HandshakeResult

        /**
         * Bound and called, and the provider answered with a failure. This covers
         * both transport-level exceptions (Binder death, SecurityException) and
         * typed contract failures detected by [ContractResponseValidator]:
         * schema mismatch, unexpected result kind, foreign payloads, or error
         * responses with typed error codes.
         */
        data class Refused(val providerPackage: String, val cause: Throwable) : HandshakeResult
    }

    fun handshake(timeoutMs: Long = 5_000L): HandshakeResult {
        return tryPackage(targetApplicationId, timeoutMs)
    }

    private fun tryPackage(providerPackage: String, timeoutMs: Long): HandshakeResult {
        val latch = CountDownLatch(1)
        // The binder is published by a framework callback thread and read by
        // this one, so it needs a memory barrier. @Volatile cannot annotate a
        // local, and an unsynchronised var here would be a data race that
        // happens to work most of the time — the worst kind.
        val binderRef = AtomicReference<IBinder?>(null)

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (name != ComponentName(providerPackage, PROVIDER_SERVICE_CLASS)) return
                binderRef.set(service)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (name != ComponentName(providerPackage, PROVIDER_SERVICE_CLASS)) return
                binderRef.set(null)
                latch.countDown()
            }

            // The provider process died during binding. Releasing the latch here
            // rather than waiting out the timeout keeps a crash distinguishable
            // from a hang.
            override fun onBindingDied(name: ComponentName?) {
                if (name != ComponentName(providerPackage, PROVIDER_SERVICE_CLASS)) return
                binderRef.set(null)
                latch.countDown()
            }

            override fun onNullBinding(name: ComponentName?) {
                if (name != ComponentName(providerPackage, PROVIDER_SERVICE_CLASS)) return
                binderRef.set(null)
                latch.countDown()
            }
        }

        val intent = Intent().setComponent(
            ComponentName(providerPackage, PROVIDER_SERVICE_CLASS)
        )

        val bindRequested = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            // Visible but not permitted to bind: a real, distinct answer.
            return HandshakeResult.Refused(providerPackage, e)
        }

        if (!bindRequested) {
            // Android does not require (and may reject) unbinding a connection whose bind request
            // returned false. Keep this typed failure path stable across platform implementations.
            return HandshakeResult.NotBindable(listOf(providerPackage))
        }

        return try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return HandshakeResult.TimedOut(providerPackage, timeoutMs)
            }
            val live = binderRef.get() ?: return HandshakeResult.NotBindable(listOf(providerPackage))

            val service = IEnvironmentControlV1.Stub.asInterface(live)
            try {
                val result = service.discover()
                // Route through the unified validator: schema version, result kind,
                // payload exclusivity, and snapshot presence are all checked.
                when (val validated = ContractResponseValidator.validateDiscover(result)) {
                    is ValidatedContractResponse.Success ->
                        HandshakeResult.Connected(providerPackage, validated.payload)
                    is ValidatedContractResponse.Failure ->
                        HandshakeResult.Refused(
                            providerPackage,
                            IllegalStateException(
                                "discover validation failed: ${validated.typedOutcome}" +
                                    " (kind=${result.resultKindWire}" +
                                    " err=${result.errorCodeWire}" +
                                    " diag=${result.diagnosticMessage})"
                            ),
                        )
                }
            } catch (t: Throwable) {
                HandshakeResult.Refused(providerPackage, t)
            }
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }

    companion object {
        /**
         * §6.1 frozen service class; the package half varies by build type.
         *
         * References the contract-module constant so Auto source never contains
         * the provider package as a string literal (INV-01, M-BP-02).
         */
        const val PROVIDER_SERVICE_CLASS = ContractV1.SERVICE_CLASS_NAME

        /** The same build-selected principal used by trust and the production Binder executor. */
        val PROVIDER_PACKAGE: String = ProviderPrincipal.selected

        /** Immutable compatibility surface for reports that render the attempted package set. */
        val PROVIDER_PACKAGES: List<String> = listOf(PROVIDER_PACKAGE)

        /** §6.8: the version this client speaks, surfaced for skew reporting. */
        val CLIENT_PROTOCOL_VERSION: Int get() = ContractV1.PROTOCOL_VERSION
    }
}
