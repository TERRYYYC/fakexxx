package com.example.cellrebelauto.integration.v1

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
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
 * package name differs between debug and release. Both are tried, in that order,
 * because a debug Auto talking to a release provider (or the reverse) is a real
 * configuration and silently failing to bind would look identical to "provider
 * not installed". Issue #13 (applicationId cutover) will eventually make this
 * single-valued; until then, guessing one name would make half the device
 * matrix invisible.
 */
class EnvironmentControlClient(private val context: Context) {

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
         * Bound and called, and the provider answered with a failure. Carried as
         * the raw throwable because §6.3.3's typed wire codes cannot currently
         * cross Binder (ServiceSpecificException is @hide in the public SDK —
         * issue #3), so the transport-level exception is the only honest signal.
         */
        data class Refused(val providerPackage: String, val cause: Throwable) : HandshakeResult
    }

    fun handshake(timeoutMs: Long = 5_000L): HandshakeResult {
        for (pkg in PROVIDER_PACKAGES) {
            when (val result = tryPackage(pkg, timeoutMs)) {
                // Only a genuine bind failure justifies moving to the next
                // candidate. A provider that bound and then refused has answered
                // — trying its sibling package would hide that answer.
                is HandshakeResult.NotBindable -> continue
                else -> return result
            }
        }
        return HandshakeResult.NotBindable(PROVIDER_PACKAGES.toList())
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
                binderRef.set(service)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binderRef.set(null)
                latch.countDown()
            }

            // The provider process died during binding. Releasing the latch here
            // rather than waiting out the timeout keeps a crash distinguishable
            // from a hang.
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
            ComponentName(providerPackage, PROVIDER_SERVICE_CLASS)
        )

        val bindRequested = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            // Visible but not permitted to bind: a real, distinct answer.
            return HandshakeResult.Refused(providerPackage, e)
        }

        if (!bindRequested) {
            context.unbindService(connection)
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
                val snapshot = result.capabilitySnapshot
                    ?: return HandshakeResult.Refused(
                        providerPackage,
                        IllegalStateException(
                            "discover returned kind=${result.resultKindWire}" +
                                " err=${result.errorCodeWire}" +
                                " diag=${result.diagnosticMessage}"
                        ),
                    )
                HandshakeResult.Connected(providerPackage, snapshot)
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

        /** Debug first: a developer device most often has the .bench build on it. */
        val PROVIDER_PACKAGES = arrayOf(
            ContractV1.PROVIDER_APPLICATION_ID_BENCH,
            ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION,
        )

        /** §6.8: the version this client speaks, surfaced for skew reporting. */
        val CLIENT_PROTOCOL_VERSION: Int get() = ContractV1.PROTOCOL_VERSION
    }
}
