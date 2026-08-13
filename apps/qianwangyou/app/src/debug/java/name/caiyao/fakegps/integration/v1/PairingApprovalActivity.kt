package name.caiyao.fakegps.integration.v1

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Debug-only operator surface for §6.5 pairing approval.
 *
 * WHY THIS EXISTS
 * ---------------
 * [PairingStore] has had approve / recordCandidate / pendingCandidates since the
 * beginning, and nothing in the app ever called approve. The consequence is not
 * cosmetic: a first handshake correctly fail-closes with NOT_PAIRED, records the
 * candidate, and then stays refused forever, because no operator can reach the
 * one action that would resolve it. The security model was complete and the
 * decision surface was missing, so the system could only ever say no.
 *
 * Listing is separate from approving on purpose. §6.5 forbids silent or
 * automatic TOFU, and "approve whatever is pending" would be TOFU wearing an
 * operator's hat. So approval requires naming BOTH halves of the principal —
 * applicationId AND signer digest — which means the digest has to be read off
 * this screen (or logcat) and handed back deliberately.
 *
 *   list:    adb shell am start -n <pkg>/name.caiyao.fakegps.integration.v1.PairingApprovalActivity
 *   approve: ... --es approve_application_id <appId> --es approve_signer_digest <sha256>
 *
 * src/debug only. An exported activity that can grant environment-control
 * access has no business in a release build; the real operator UI is a
 * product surface, and this is the thing that unblocks §7 acceptance today
 * without pretending to be that surface.
 */
class PairingApprovalActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply {
            textSize = 12f
            setPadding(24, 48, 24, 24)
            text = "pairing — working…"
        }
        setContentView(ScrollView(this).apply { addView(view) })

        val approveId = intent?.getStringExtra(EXTRA_APP_ID)
        val approveDigest = intent?.getStringExtra(EXTRA_SIGNER)

        // Touching the durable store must not run on the main thread.
        thread(name = "ec-pairing-approval") {
            val report = runCatching { buildReport(approveId, approveDigest) }
                .getOrElse { "FAILED: ${it::class.java.name}: ${it.message}" }
            Log.i(TAG, report)
            runOnUiThread { view.text = report }
        }
    }

    private fun buildReport(approveId: String?, approveDigest: String?): String = buildString {
        appendLine("Environment Control v1 — pairing (§6.5)")
        appendLine("-".repeat(48))

        if (approveId != null && approveDigest != null) {
            val ok = ProviderRuntime.approveCaller(applicationContext, approveId, approveDigest)
            appendLine(if (ok) "APPROVED: $approveId" else "NO MATCH: $approveId / ${approveDigest.take(16)}…")
            if (!ok) {
                appendLine("Nothing pending matches that exact (applicationId, signerDigest).")
                appendLine("Approval is deliberately not fuzzy — see §6.5 TOFU prohibition.")
            }
            appendLine()
        } else if (approveId != null || approveDigest != null) {
            // Half a principal is not a weaker request, it is a different one.
            appendLine("REFUSED: approval needs BOTH --es $EXTRA_APP_ID and --es $EXTRA_SIGNER")
            appendLine()
        }

        val pending = ProviderRuntime.pendingCallers(applicationContext)
        if (pending.isEmpty()) {
            appendLine("no pending callers")
            appendLine()
            appendLine("Either nothing has tried to bind yet, or everything that has")
            appendLine("is already paired. Run the Auto-side handshake probe first.")
        } else {
            appendLine("pending callers (${pending.size}):")
            pending.forEach { c ->
                appendLine()
                appendLine("  applicationId : ${c.callerApplicationId}")
                appendLine("  signerDigest  : ${c.currentSignerDigest}")
                appendLine("  versionCode   : ${c.observedVersionCode}")
                appendLine("  firstSeen(ert): ${c.firstSeenAtElapsedRealtimeMs}")
            }
        }
    }

    private companion object {
        const val TAG = "ECPairingApproval"
        const val EXTRA_APP_ID = "approve_application_id"
        const val EXTRA_SIGNER = "approve_signer_digest"
    }
}
