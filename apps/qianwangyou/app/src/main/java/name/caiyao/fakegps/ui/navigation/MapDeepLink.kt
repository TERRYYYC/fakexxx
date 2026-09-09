package name.caiyao.fakegps.ui.navigation

import android.content.Intent

/**
 * T11c: the `fakexxx-map://pending` deep link — the one-hop entry into this app's
 * pairing approval area (设置页 · Auto 协作 · 待批准的 Auto), fired by the Auto
 * app's Provider page (its "去 QWY 批准" button).
 *
 * SECURITY POSTURE — navigation only, by construction: the URI is parsed into a
 * (destination, anchor) pair and NOTHING else. No extras are read, no candidate is
 * approved, no state changes — landing here merely shows the pending-caller list
 * the operator could reach by hand, and approval still requires the explicit
 * per-candidate confirm dialog. The manifest filter therefore needs no permission.
 *
 * # deep link 仅导航：解析成 (目标页, 锚点)，不带数据、不执行动作
 */
object MapDeepLink {

    /** This app's deep link scheme (fakexxx-map · QWY). */
    const val SCHEME = "fakexxx-map"

    /** The only registered host: the pending-approval pairing area. */
    const val HOST_PENDING = "pending"

    /**
     * Where a recognized deep link lands: the settings page, anchored (scrolled +
     * highlighted) onto the 待批准的 Auto pairing section.
     */
    data class Route(
        val destination: Screen,
        val highlightPendingPairing: Boolean,
    )

    /** Intent → route; null when the intent is not this app's pending deep link. */
    fun route(intent: Intent?): Route? {
        val data = intent?.data ?: return null
        if (data.scheme != SCHEME || data.host != HOST_PENDING) return null
        return Route(destination = Screen.Settings, highlightPendingPairing = true)
    }
}
