package com.example.cellrebelauto.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context

/**
 * Issue #9: device-readiness projection for the Plan surface.
 *
 * WHY: the bench Moto/Android 15 rolls back `settings put secure enabled_accessibility_services`
 * unconditionally, and force-stop / install -r clear the accessibility enablement. The engine
 * depends on AutomationService (accessibility); when it is off, the Plan surface showed a bare
 * " Service OFF" chip with the Start button grey and NO explanation — the operator could not tell
 * WHAT to re-enable. This projection turns the enabled-service list into a readable action line.
 * Pure logic lives in [DeviceReadinessProjection]; [DeviceReadinessProbe] is the Android binding.
 *
 * # 设备就绪投影：无障碍启用列表 → 可读指引行；OEM 脆弱不可修，只做可见性
 */
object DeviceReadinessProjection {

    /** AutomationService's class (same class name in every build; the PACKAGE differs per lane). */
    const val OWN_SERVICE_CLASS = "com.example.cellrebelauto.automation.AutomationService"

    /** ComponentName.flattenToString form ("pkg/cls") — the same form the enabled list uses. */
    fun ownFlatComponent(packageName: String): String = "$packageName/$OWN_SERVICE_CLASS"

    /** True iff OUR accessibility service is in the system's enabled list. */
    fun accessibilityEnabled(enabledFlatComponents: Set<String>, ownFlatComponent: String): Boolean =
        ownFlatComponent in enabledFlatComponents

    /**
     * The readable service status line for the Plan surface. Null = healthy (no line).
     * - not connected + not enabled → the exact settings path with THIS build's display name;
     * - not connected + enabled → the service died without the switch moving (reconnect hint);
     * - unknown enablement → stay silent, never guess.
     */
    fun statusLine(
        serviceConnected: Boolean,
        accessibilityEnabled: Boolean?,
        appDisplayName: String,
    ): String? = when {
        serviceConnected -> null
        accessibilityEnabled == false ->
            "无障碍服务未启用 — 设置 → 无障碍 → $appDisplayName → 重新打开服务" +
                "（OEM 系统可能在 force-stop / 重新安装后自动关闭它）"
        accessibilityEnabled == true ->
            "无障碍服务已启用但未连接 — 请在 设置 → 无障碍 → $appDisplayName 中关闭再重新开启"
        else -> null
    }
}

/** The Android binding for [DeviceReadinessProjection]: AccessibilityManager + PackageManager. */
object DeviceReadinessProbe {

    /** The system's enabled accessibility services, flattened to "pkg/cls". Empty on any failure. */
    fun enabledAccessibilityComponents(context: Context): Set<String> = try {
        val manager = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            ?: return emptySet()
        manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .mapNotNull { info ->
                val service = info.resolveInfo?.serviceInfo ?: return@mapNotNull null
                ComponentName(service.packageName, service.name).flattenToString()
            }
            .toSet()
    } catch (failure: Throwable) {
        emptySet()
    }

    /** True iff this build's own AutomationService is enabled system-wide. */
    fun accessibilityEnabled(context: Context): Boolean =
        DeviceReadinessProjection.accessibilityEnabled(
            enabledAccessibilityComponents(context),
            DeviceReadinessProjection.ownFlatComponent(context.packageName),
        )

    /**
     * This build's user-visible app label (lane builds carry distinct labels, e.g.
     * "CellRebel Auto·GLM测试"). Falls back to the package name — the line must still render.
     */
    fun appDisplayName(context: Context): String = try {
        context.packageManager.getApplicationLabel(context.applicationInfo).toString()
    } catch (failure: Throwable) {
        context.packageName
    }
}
