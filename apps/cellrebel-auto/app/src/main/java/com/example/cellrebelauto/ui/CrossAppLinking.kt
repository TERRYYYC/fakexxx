package com.example.cellrebelauto.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.cellrebelauto.automation.ProviderPrincipal
import com.example.cellrebelauto.integration.v1.EnvironmentControlClient
import io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1

/**
 * T11c (UI-WORKFLOWS §2 跨 app 跳转): the cross-app deep links that close the
 * pairing loop in one hop — `fakexxx-auto://providers` (this app's Provider page)
 * and `fakexxx-map://pending` (QWY's pending-approval pairing section).
 *
 * SECURITY POSTURE — navigation only, by construction:
 *  - the incoming link is parsed to a SCREEN and nothing else: no extras are read,
 *    no action is executed, no state changes. A hostile app (or a browser) can at
 *    most open the Provider page — the same page reachable by one tap — and every
 *    privileged action on it stays behind the §6.5 approval gates.
 *  - the outgoing link targets the PAIRED provider principal explicitly
 *    ([Intent.setPackage]) so another app cannot hijack the `fakexxx-map` scheme;
 *    it carries no extras and holds no permission.
 *  - the manifest filter needs no permission guard: VIEW into a launcher screen
 *    is inert navigation.
 *
 * # 跨 app deep link：仅导航、不带数据、不执行动作；跳出处显式 setPackage 防劫持
 */
object CrossAppDeepLinks {

    /** This app's scheme: the Provider approval/revocation page (A2). */
    const val SCHEME_AUTO = "fakexxx-auto"
    const val HOST_PROVIDERS = "providers"

    /** The peer's (QWY / fakexxx-map) scheme: its settings pairing area's 待批准 list. */
    const val SCHEME_MAP = "fakexxx-map"
    const val HOST_PENDING = "pending"

    /** The one-tap jump target: QWY's pending-approval pairing section. */
    val MAP_PENDING_URI: Uri = Uri.parse("$SCHEME_MAP://$HOST_PENDING")

    /**
     * Incoming deep link → the target Screen, or null when the URI is not one of
     * ours (launcher intents have no data; foreign schemes/hosts never route).
     */
    fun routeToScreen(data: Uri?): Screen? = when {
        data == null -> null
        data.scheme == SCHEME_AUTO && data.host == HOST_PROVIDERS -> Screen.PROVIDERS
        else -> null
    }

    /**
     * The outbound intent behind the A2 "去 QWY 批准" button: VIEW
     * `fakexxx-map://pending` in THIS build's paired QWY principal
     * ([ProviderPrincipal.selected]), data-less.
     */
    fun peerPendingIntent(): Intent =
        Intent(Intent.ACTION_VIEW, MAP_PENDING_URI)
            .setPackage(ProviderPrincipal.selected)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Launches the paired QWY's pending-approval section. False when the peer is
     * not installed (or the system refuses) — the caller says so with a toast,
     * never a crash.
     */
    fun launchPeerPending(context: Context): Boolean = try {
        context.startActivity(peerPendingIntent())
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * Whether the PEER (QWY) has approved US — read from the EXISTING discover
 * channel, the same handshake the provider health lamp already rides. Until now
 * this state was a blind spot: Auto knew its OWN pending candidates (approve QWY),
 * but nothing told the operator that the reverse approval was missing, which is
 * exactly the W3 "provider discover failed (trust gate)" stall.
 *
 * # 对方批准状态：由既有 discover 握手投影，纯函数
 */
enum class PeerPairingStatus {

    /** discover succeeded — QWY approved us and the contract channel is usable. */
    PEER_LINK_UP,

    /**
     * discover answered the frozen NOT_PAIRED error — QWY recorded our candidate
     * but the operator has not approved it. THE todo: fixable in one hop via
     * `fakexxx-map://pending`.
     */
    WAITING_PEER_APPROVAL,

    /** QWY not installed / channel unreachable / refused for any other reason. */
    UNREACHABLE;

    companion object {

        fun fromHandshake(result: EnvironmentControlClient.HandshakeResult?): PeerPairingStatus? =
            when (result) {
                null -> null
                is EnvironmentControlClient.HandshakeResult.Connected -> PEER_LINK_UP
                is EnvironmentControlClient.HandshakeResult.Refused ->
                    // R1 (fresh-context review): match the WHOLE typed-outcome token, not a
                    // prefix. NOT_PAIRED.wire=1 and a raw startsWith("…PROVIDER_ERROR_1")
                    // also swallowed wire 10–17 (RELEASE_INCOMPLETE, INTERNAL_FAILURE, …) —
                    // a provider-side INTERNAL_FAILURE on discover was rendered as "对方还未
                    // 批准我方" with a jump button. Token equality keeps only the real
                    // NOT_PAIRED refusal on the todo path (PeerPairingStatusTest pins both).
                    if (refusalOutcomeToken(result.cause.message) == notPairedOutcomeToken()) {
                        WAITING_PEER_APPROVAL
                    } else {
                        UNREACHABLE
                    }
                is EnvironmentControlClient.HandshakeResult.NotBindable,
                is EnvironmentControlClient.HandshakeResult.TimedOut,
                -> UNREACHABLE
            }

        /**
         * The message head [EnvironmentControlClient] gives every validator-derived
         * discover refusal, before the typed outcome token.
         */
        private const val REFUSAL_MESSAGE_PREFIX = "discover validation failed: "

        /**
         * The typed outcome token of a refusal, e.g. "PROVIDER_ERROR_1" from
         * "discover validation failed: PROVIDER_ERROR_1 (kind=ERROR err=1 diag=…)".
         * The wire value comes from the frozen contract constant — never a literal.
         */
        internal fun notPairedOutcomeToken(): String =
            "PROVIDER_ERROR_${ContractErrorCodeV1.NOT_PAIRED.wire}"

        /** Extracts the outcome token from a [REFUSAL_MESSAGE_PREFIX] message; null otherwise. */
        internal fun refusalOutcomeToken(message: String?): String? =
            message
                ?.takeIf { it.startsWith(REFUSAL_MESSAGE_PREFIX) }
                ?.removePrefix(REFUSAL_MESSAGE_PREFIX)
                ?.substringBefore(' ')
    }
}

/**
 * The A2 Provider-page todo bar's render model (pure projection over
 * [PeerPairingStatus]). `isTodo` marks the one state where the cross-app jump
 * button is offered; every other state is an honest status line with no action.
 */
data class PeerApprovalTodoBar(
    val statusLine: String,
    val isTodo: Boolean,
    val peerDeepLink: Uri?,
    val actionLabel: String,
) {
    companion object {
        fun project(status: PeerPairingStatus?): PeerApprovalTodoBar = when (status) {
            null -> PeerApprovalTodoBar(
                statusLine = "对方（千网游）批准状态未知 — 探测失败或通道未就绪",
                isTodo = false, peerDeepLink = null, actionLabel = "",
            )
            PeerPairingStatus.PEER_LINK_UP -> PeerApprovalTodoBar(
                statusLine = "对方（千网游）已批准我方 — 契约通道可用",
                isTodo = false, peerDeepLink = null, actionLabel = "",
            )
            PeerPairingStatus.WAITING_PEER_APPROVAL -> PeerApprovalTodoBar(
                statusLine = "对方（千网游）还未批准我方 — 批准前引擎的契约调用会被拒绝",
                isTodo = true,
                peerDeepLink = CrossAppDeepLinks.MAP_PENDING_URI,
                actionLabel = "去 QWY 批准",
            )
            PeerPairingStatus.UNREACHABLE -> PeerApprovalTodoBar(
                statusLine = "对方（千网游）不可达 — 未安装或 discover 通道失败",
                isTodo = false, peerDeepLink = null, actionLabel = "",
            )
        }
    }
}

/**
 * The test-injectable seam over the discover channel (same shape as
 * [ProfileCountProbe] / [ProviderHealthProbe]): one synchronous handshake.
 * Null = the probe itself failed — projected as UNKNOWN, never a guessed state.
 */
fun interface PeerApprovalProbe {
    fun probe(): EnvironmentControlClient.HandshakeResult?
}

/** Production probe: one discover handshake on the caller's (IO) thread. */
class DiscoverPeerApprovalProbe(private val context: Context) : PeerApprovalProbe {
    override fun probe(): EnvironmentControlClient.HandshakeResult? = try {
        com.example.cellrebelauto.integration.v1.EnvironmentControlClient(context).handshake()
    } catch (_: Throwable) {
        null
    }
}
