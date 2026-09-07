package com.example.cellrebelauto.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.cellrebelauto.model.plan.PlanConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

/**
 * P0.1-4 Global buffer 默认值 oracle：DataStore 里没有 buffer 键时 UI 读到默认值 10（可改），
 * 改过的值跨进程（新 store 实例）回读。
 *
 * 实测痛点：buffer 空值卡死 Import（"Set global buffer first"），新机只能靠猜历史值 10。
 * Killing mutation: 缺省改回 null —— 第一条断言红；缺省改成非 10 —— 常量断言红。
 */
@RunWith(RobolectricTestRunner::class)
class PlanConfigBufferDefaultTest {

    private lateinit var file: File

    @Before
    fun setUp() {
        file = File(
            System.getProperty("java.io.tmpdir"),
            "plan-config-buffer-default-test-${UUID.randomUUID()}.preferences_pb"
        )
    }

    @After
    fun tearDown() {
        file.delete()
    }

    private fun newStore(scope: CoroutineScope) = PlanConfigStore(
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    )

    @Test
    fun `missing buffer key reads as the default 10`() = runTest {
        val config = newStore(backgroundScope).config.first()
        assertEquals(PlanConfig.DEFAULT_GLOBAL_BUFFER_SECONDS, config.globalBufferSeconds)
        assertEquals(10, config.globalBufferSeconds)
    }

    @Test
    fun `empty to default to changed to persisted readback - full chain`() = runTest {
        // 空 → 默认 10
        val scope1 = CoroutineScope(backgroundScope.coroutineContext + Job())
        val store1 = newStore(scope1)
        assertEquals(10, store1.config.first().globalBufferSeconds)
        // 改值（UI 可改）
        store1.setGlobalBufferSeconds(25)
        assertEquals(25, store1.config.first().globalBufferSeconds)
        scope1.cancel() // 模拟进程退出

        // 冷启动回读：把持久化字节搬到新文件（规避同文件双 DataStore 约束），
        // 全新实例从磁盘字节读回改过的 25，而不是默认。
        val file2 = File(
            System.getProperty("java.io.tmpdir"),
            "plan-config-buffer-default-readback-${UUID.randomUUID()}.preferences_pb"
        )
        file2.writeBytes(file.readBytes())
        try {
            val store2 = PlanConfigStore(
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { file2 }
                )
            )
            assertEquals(25, store2.config.first().globalBufferSeconds)
        } finally {
            file2.delete()
        }
    }
}
