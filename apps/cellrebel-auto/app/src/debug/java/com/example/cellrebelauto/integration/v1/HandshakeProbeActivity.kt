package com.example.cellrebelauto.integration.v1

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Debug-only probe that performs one Environment Control handshake and reports
 * what happened, on screen and in logcat.
 *
 * WHY AN ACTIVITY AND WHY DEBUG-ONLY
 * ----------------------------------
 * §7 acceptance is about the pair of apps on real hardware, and the JVM lane
 * cannot reach the things that actually break there: PackageManager's view of
 * the caller, package visibility on API 30+, exported-service policy, Binder
 * marshalling of the frozen parcelables. Those need a process on a device.
 *
 * It lives in `src/debug` so it cannot ship. An exported entry point that runs
 * a privileged handshake is a liability in a release build, and this one exists
 * purely so an operator (or `adb shell am start`) can ask the device a question
 * and get an answer that is not a test double's opinion.
 *
 * It is NOT a substitute for the #7 acceptance suite. It answers exactly one
 * question — "can these two apps see and talk to each other at all" — which is
 * the question that had never been asked.
 */
class HandshakeProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply {
            textSize = 12f
            setPadding(24, 48, 24, 24)
            text = "Environment Control v1 handshake — running…"
        }
        setContentView(ScrollView(this).apply { addView(view) })

        // Binder calls block; the main thread is not allowed to wait on one.
        thread(name = "ec-handshake-probe") {
            val client = EnvironmentControlClient(applicationContext)
            val report = buildReport(client)
            // logcat carries it too, so the result survives the screen and can be
            // collected non-interactively over adb.
            Log.i(TAG, report)
            runOnUiThread { view.text = report }
        }
    }

    private fun buildReport(client: EnvironmentControlClient): String {
        val header = buildString {
            appendLine("Environment Control v1 — handshake probe")
            appendLine("client protocol version: ${EnvironmentControlClient.CLIENT_PROTOCOL_VERSION}")
            appendLine("candidate packages: ${EnvironmentControlClient.PROVIDER_PACKAGES.joinToString()}")
            appendLine("service: ${EnvironmentControlClient.PROVIDER_SERVICE_CLASS}")
            appendLine("-".repeat(48))
        }

        return header + when (val r = client.handshake()) {
            is EnvironmentControlClient.HandshakeResult.Connected -> buildString {
                val s = r.snapshot
                appendLine("RESULT: CONNECTED")
                appendLine("provider package : ${r.providerPackage}")
                appendLine("protocolVersion  : ${s.protocolVersion}")
                appendLine("serviceVersion   : ${s.serviceVersion}")
                appendLine("modes            : ${s.supportedModeWires}")
                appendLine("verification     : ${s.supportedVerificationLevelWires}")
                appendLine("continuity wire  : ${s.continuityCoverageWire}")
                appendLine("environmentRev   : ${s.environmentRevision}")
                appendLine("scheduleRefs     : ${s.scheduleRefs}")
                appendLine("currentScheduleId: ${s.currentScheduleId}")
                appendLine("currentItemId    : ${s.currentItemId}")
                appendLine("scheduleVersion  : ${s.scheduleVersion}")
                appendLine()
                // §6.8: version skew is a stop condition, not a warning to scroll past.
                if (s.protocolVersion != EnvironmentControlClient.CLIENT_PROTOCOL_VERSION) {
                    appendLine("!! PROTOCOL SKEW — client speaks " +
                        "${EnvironmentControlClient.CLIENT_PROTOCOL_VERSION}, provider speaks ${s.protocolVersion}")
                }
            }

            is EnvironmentControlClient.HandshakeResult.NotBindable -> buildString {
                appendLine("RESULT: NOT BINDABLE")
                appendLine("tried: ${r.triedPackages.joinToString()}")
                appendLine()
                appendLine("Either no provider build is installed, or <queries> visibility")
                appendLine("does not cover the installed package name (API 30+).")
            }

            is EnvironmentControlClient.HandshakeResult.TimedOut -> buildString {
                appendLine("RESULT: TIMED OUT after ${r.waitedMs} ms")
                appendLine("provider package: ${r.providerPackage}")
                appendLine("Bound, but discover() did not return — a live but stuck provider.")
            }

            is EnvironmentControlClient.HandshakeResult.Refused -> buildString {
                appendLine("RESULT: REFUSED")
                appendLine("provider package: ${r.providerPackage}")
                appendLine("cause: ${r.cause::class.java.name}: ${r.cause.message}")
                appendLine()
                appendLine("Reached the provider and got a failure. Pairing (§6.5) rejecting")
                appendLine("an unapproved caller is the EXPECTED first answer on a fresh")
                appendLine("device — that is fail-closed working, not a defect.")
            }
        }
    }

    private companion object {
        const val TAG = "ECHandshakeProbe"
    }
}
