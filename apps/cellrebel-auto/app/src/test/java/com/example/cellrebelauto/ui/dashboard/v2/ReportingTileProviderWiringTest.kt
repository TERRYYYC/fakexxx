package com.example.cellrebelauto.ui.dashboard.v2

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.modules.MapTileDownloader
import org.robolectric.RobolectricTestRunner

/**
 * 注入 wiring 的回归钉（PR #183 评审 nit，2026-09-13）。
 *
 * [ReportingTileProvider] 必须走 osmdroid 5 参 `MapTileProviderBasic` 构造，把
 * [GateNetworkAvailablityCheck] 注到 [MapTileDownloader] 的判网点上。若将来
 * 有人把 provider 回退到 2 参构造，osmdroid 会在构造器内部静默
 * `new NetworkAvailabliltyCheck(context)`（legacy 判网 = g54 冷启动瓦片永不
 * 渲染的病根）——两个既有测试类此时都不会红，因为它们只测 check 自身的语义，
 * 不测"check 真的到达了 downloader"。本测试补上这一环：反射读 downloader 持
 * 有的 check 实例并断言其类型，wiring 回退必红。
 *
 * ReportingTileProvider 是文件私有（Kotlin file-private），测试经反射构造；
 * osmdroid 6.1.20 的模块链与判网字段均为 private/protected，同样经反射读取
 * （字段名钉 6.1.20：`MapTileProviderArray.mTileProviderList`、
 * `MapTileDownloader.mNetworkAvailablityCheck`——osmdroid 原文即拼作
 * Availablity）。
 */
@RunWith(RobolectricTestRunner::class)
class ReportingTileProviderWiringTest {

    @Test
    fun `downloader holds the gate check - wiring must not regress to osmdroid's 2-arg constructor`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        OsmdroidBootstrap.ensure(context)

        // ReportingTileProvider 文件私有：反射取主构造（Context + 2 个首瓦回调
        // + graceMs + 时钟），实参 = 生产默认（FIRST_TILE_GRACE_MS、uptime 时钟等价物）
        val providerClass = Class.forName(
            "com.example.cellrebelauto.ui.dashboard.v2.ReportingTileProvider",
        )
        val ctor = providerClass.declaredConstructors.first { it.parameterTypes.size == 5 }
        ctor.isAccessible = true
        val provider = ctor.newInstance(context, { }, { }, 10_000L, { 0L })

        // 5 参构造把模块链挂在 MapTileProviderArray.mTileProviderList 上
        val modulesField = MapTileProviderArray::class.java.getDeclaredField("mTileProviderList")
        modulesField.isAccessible = true
        val modules = modulesField.get(provider) as List<*>
        val downloader = modules.filterIsInstance<MapTileDownloader>().first()
        assertTrue(
            "module chain must contain the network downloader (osmdroid 6.1.20 layout)",
            modules.isNotEmpty(),
        )

        val checkField = MapTileDownloader::class.java.getDeclaredField("mNetworkAvailablityCheck")
        checkField.isAccessible = true
        val check = checkField.get(downloader)

        assertTrue(
            "MapTileDownloader must hold OUR GateNetworkAvailablityCheck; osmdroid's " +
                "legacy NetworkAvailabliltyCheck here means the 2-arg constructor " +
                "regressed (the g54 cold-start silent-refusal root cause)",
            check is GateNetworkAvailablityCheck,
        )
    }
}
