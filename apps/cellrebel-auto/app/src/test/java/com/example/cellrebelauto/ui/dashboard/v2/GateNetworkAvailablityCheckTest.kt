package com.example.cellrebelauto.ui.dashboard.v2

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkInfo
/**
 * 判网分歧（moto g54 冷启动瓦片不渲染修复，2026-09-13）的契约钉死。
 *
 * osmdroid 6.1.20 的 [NetworkAvailabliltyCheck] 用 legacy
 * `getActiveNetworkInfo().isConnected()`：网络处于 VALIDATING（isConnected=false）
 * 但 `activeNetwork != null` 时它回答"离线"，downloader 便静默拒绝一切瓦片
 * （HTTP 根本不发起）。而选卡策略用的 [TileNetworkGate]（`activeNetwork != null`）
 * 同一时刻回答"在线"→ 卡片选了瓦片图却永远等不到第一张瓦片 → sticky 永久降级。
 *
 * 契约：[GateNetworkAvailablityCheck] 必须与 [TileNetworkGate] 在一切状态下
 * 同信号；在"VALIDATING 但 activeNetwork 存在"的 g54 场景必须回答在线
 * （让 downloader 真正发起 HTTP），而 osmdroid 原厂检查在同一场景回答离线。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // getActiveNetwork 需要 API 23+；默认 SDK 是 21
class GateNetworkAvailablityCheckTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun cm() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun setActive(networkState: NetworkInfo.DetailedState, isConnected: Boolean) {
        val shadow = shadowOf(cm())
        val info = ShadowNetworkInfo.newInstance(
            networkState,
            ConnectivityManager.TYPE_WIFI,
            0,
            isConnected,
            if (isConnected) NetworkInfo.State.CONNECTED else NetworkInfo.State.CONNECTING,
        )
        // ShadowConnectivityManager.getActiveNetwork() 需要 defaultNetworkActive 且
        // netIdToNetwork[type] 有值：netId 取 TYPE_WIFI 使 activeNetwork 非空
        shadow.setDefaultNetworkActive(true)
        shadow.addNetwork(ShadowNetwork.newInstance(ConnectivityManager.TYPE_WIFI), info)
        shadow.setActiveNetworkInfo(info)
    }

    private fun setNoNetwork() {
        val shadow = shadowOf(cm())
        shadow.setDefaultNetworkActive(false)
        shadow.setActiveNetworkInfo(null)
    }

    @Test
    fun `gate check always agrees with the card policy signal`() {
        setActive(NetworkInfo.DetailedState.CONNECTED, isConnected = true)
        assertEquals(TileNetworkGate.isOnline(context), GateNetworkAvailablityCheck(context).networkAvailable)
        assertTrue("connected state is online for both signals", TileNetworkGate.isOnline(context))

        setActive(NetworkInfo.DetailedState.DISCONNECTED, isConnected = false)
        assertEquals(TileNetworkGate.isOnline(context), GateNetworkAvailablityCheck(context).networkAvailable)

        setNoNetwork()
        assertFalse("no active network = offline for both signals", TileNetworkGate.isOnline(context))
        assertFalse(GateNetworkAvailablityCheck(context).networkAvailable)
    }

    @Test
    fun `network not yet connected answers online - the g54 cold-start condition`() {
        // g54 冷启动场景：activeNetwork 存在但连接栈未到 CONNECTED。
        // 注：Robolectric shadow 把 activeNetwork 与 NetworkInfo 耦合，无法在 JVM 上
        // 复现真机上 osmdroid 原厂 NetworkAvailabliltyCheck 返回 false 的分歧状态
        // （真机证据见 TilePlanMapCard KDoc：首瓦片 67ms 静默失败、全程零网络日志
        // ——downloader 唯一静默路径就是判网 false）。这里钉死我方契约：只要
        // 选卡信号（TileNetworkGate）在线，判网检查就不得拒绝下载。
        setActive(NetworkInfo.DetailedState.OBTAINING_IPADDR, isConnected = false)

        assertTrue(
            "policy signal stays online before CONNECTED (activeNetwork != null)",
            TileNetworkGate.isOnline(context),
        )
        assertTrue(
            "our injected check must mirror the policy signal so the downloader actually attempts HTTP",
            GateNetworkAvailablityCheck(context).networkAvailable,
        )
    }
}
