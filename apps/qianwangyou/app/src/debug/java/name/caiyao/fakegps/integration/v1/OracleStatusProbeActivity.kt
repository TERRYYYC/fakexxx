package name.caiyao.fakegps.integration.v1

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Debug-only probe that renders the system_server continuity oracle window
 * (#153): the wire snapshot four-piece plus every intermediate predicate of
 * EnvironmentObserver's authoritativeWindowIsValid chain, on screen and in
 * logcat.
 *
 * WHY AN ACTIVITY AND WHY DEBUG-ONLY
 * ----------------------------------
 * The oracle is fail-closed SILENT by design: when coverage degrades to NONE
 * nothing on the device says which predicate failed (TRUST-RECOVERY-285 §3 —
 * the mi14 lane could not tell a missing hook group from a digest drift from a
 * stale replay without code-side instrumentation). This probe asks the live
 * process the question the operator could not ask from outside.
 *
 * It lives in `src/debug` so it cannot ship, and it is strictly READ-ONLY: it
 * goes through [ProviderRuntime.oracleWindowDiagnostics], i.e. the normal
 * single-writer composition root (same FileDurableKv, same tracker — no second
 * owner over the same directory), takes two oracle snapshots, and renders the
 * decomposition. It never bumps, acknowledges, or audits. Unlike the pairing
 * surface there is nothing here to approve — worst case it shows state an
 * operator could otherwise not see.
 *
 *   adb shell am start -n <pkg>/name.caiyao.fakegps.integration.v1.OracleStatusProbeActivity
 *
 * NOT a substitute for a green oracle: a probe run is a snapshot, not a
 * continuity proof — the FULL decision still belongs to observe() alone.
 */
class OracleStatusProbeActivity : Activity() {

    private lateinit var view: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = TextView(this).apply {
            textSize = 12f
            setPadding(24, 48, 24, 24)
            text = "oracle window probe — running…"
        }
        setContentView(ScrollView(this).apply { addView(view) })
        runProbe()
    }

    /**
     * F-11: singleTop + onNewIntent so repeated `adb shell am start` re-runs the
     * probe instead of silently bringing the stale result to front.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        runProbe()
    }

    private fun runProbe() {
        view.text = "oracle window probe — running…"
        // Binder + durable reads block; the main thread is not allowed to wait.
        thread(name = "oracle-status-probe") {
            val report = runCatching { buildReport() }
                .getOrElse { "FAILED: ${it::class.java.name}: ${it.message}" }
            // logcat carries it too, so the result survives the screen and can be
            // collected non-interactively over adb.
            Log.i(TAG, report)
            runOnUiThread { view.text = report }
        }
    }

    private fun buildReport(): String = buildString {
        appendLine("Authoritative oracle window probe (#153)")
        appendLine("-".repeat(48))
        val diagnostics = ProviderRuntime.oracleWindowDiagnostics(applicationContext)
        if (diagnostics == null) {
            appendLine("RESULT: NO AUTHORITATIVE SOURCE COMPOSED")
            appendLine()
            appendLine("ProviderRuntime composed without BinderAuthoritativeContinuitySource —")
            appendLine("this is the legacy-harness wiring, not a device production build.")
            return@buildString
        }
        appendLine("local generation : ${diagnostics.localGeneration}")
        appendLine("expected digest  : ${diagnostics.expectedDigest ?: "null"}")
        appendLine()
        appendWindowSnapshot("PRE", diagnostics.pre)
        appendWindowSnapshot("POST", diagnostics.post)
        appendLine("-".repeat(48))
        appendLine("WINDOW PREDICATES (EnvironmentObserver chain)")
        val t = diagnostics.trace
        appendLine("prePresent         : ${t.prePresent}")
        appendLine("postPresent        : ${t.postPresent}")
        appendLine("ownerConfigured    : ${t.ownerConfigured}")
        appendLine("ownerMatch         : ${t.ownerMatch}")
        appendLine("verdict            : ${t.verdict}")
        appendLine("digestMatch        : ${t.digestMatch}")
        appendLine("epochStable        : ${t.epochStable}")
        appendLine("staleReplayRejected: ${t.staleReplayRejected}")
        appendLine("acknowledgedCursor : ${diagnostics.acknowledgedCursor ?: "none"}")
        appendLine("-".repeat(48))
        if (t.windowValid) {
            appendLine("RESULT: WINDOW VALID — observe() would judge FULL (all else equal)")
        } else {
            appendLine("RESULT: WINDOW INVALID — reason: ${t.invalidReason()}")
            appendLine("This reason is the payloadDigest of the ORACLE_WINDOW_INVALID")
            appendLine("audit row the next observe() through this chain appends.")
        }
    }

    private fun StringBuilder.appendWindowSnapshot(label: String, s: AuthoritativeContinuitySnapshot?) {
        appendLine("-".repeat(48))
        appendLine("$label ENDPOINT SNAPSHOT")
        if (s == null) {
            appendLine("absent (source returned null — bridge unregistered or decode failure)")
            return
        }
        appendLine("protocolVersion : ${s.protocolVersion}")
        appendLine("bootId          : ${s.bootId}")
        appendLine("oracleInstanceId: ${s.oracleInstanceId}")
        appendLine("sequence        : ${s.sequence} (${if (s.sequence and 1L == 0L) "even/stable" else "ODD/mutating"})")
        appendLine("owner           : uid=${s.ownerUid} pkg=${s.ownerPackage}")
        appendLine("providers       : gps=${s.gpsProviderEnabled} network=${s.networkProviderEnabled}")
        appendLine("requiredMask    : 0x${java.lang.Long.toHexString(s.requiredCoverageMask)}")
        appendLine("installedMask   : 0x${java.lang.Long.toHexString(s.installedCoverageMask)}")
        appendLine("  installed bits: ${coverageBitNames(s.installedCoverageMask)}")
        appendLine("  missing bits  : ${coverageBitNames(s.requiredCoverageMask and s.installedCoverageMask.inv())}")
        appendLine("health          : ${s.health}")
        appendLine("qwySemanticDigest: ${s.qwySemanticDigest ?: "null"}")
        appendLine("lastCompletedQwyMutationId: ${s.lastCompletedQwyMutationId ?: "null"}")
    }

    private fun coverageBitNames(mask: Long): String {
        val names = listOf(
            AuthoritativeCoverageMask.APP_OPS_CHECKING_SERVICE_WRAPPER to "app_ops_wrapper",
            AuthoritativeCoverageMask.APP_OPS_ACCESS_CHECKING_DELEGATE to "access_checking_delegate",
            AuthoritativeCoverageMask.APP_OPS_LIFECYCLE_REMOVAL to "app_ops_lifecycle",
            AuthoritativeCoverageMask.LOCATION_PROVIDER_STATE to "provider_state",
            AuthoritativeCoverageMask.LOCATION_EFFECTIVE_ENABLED to "effective_enabled",
            AuthoritativeCoverageMask.QWY_SERVICE_GENERATION to "qwy_service_generation",
            AuthoritativeCoverageMask.QWY_SEMANTIC_MUTATION to "qwy_semantic_mutation",
            AuthoritativeCoverageMask.BRIDGE_SESSION to "bridge_session",
            AuthoritativeCoverageMask.BUILD_ATTESTATION to "build_attestation",
            AuthoritativeCoverageMask.LOCATION_SEMANTIC_COORDINATE to "semantic_coordinate",
        )
        return names.filter { (bit, _) -> mask and bit != 0L }
            .joinToString("|") { it.second }
            .ifEmpty { "none" }
    }

    private companion object {
        const val TAG = "OracleStatusProbe"
    }
}
