package com.example.cellrebelauto.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.cellrebelauto.cutover.CutoverAccessGate
import com.example.cellrebelauto.cutover.CutoverAccessUnavailableException
import com.example.cellrebelauto.cutover.CutoverArchiveV2
import com.example.cellrebelauto.cutover.CutoverExclusiveAdmission
import com.example.cellrebelauto.cutover.CutoverExclusiveRelease
import com.example.cellrebelauto.cutover.CutoverPlanConfigSchema
import com.example.cellrebelauto.cutover.CutoverPreferenceEntry
import com.example.cellrebelauto.cutover.CutoverRestoreIdentity
import com.example.cellrebelauto.cutover.CutoverRestoreJournal
import com.example.cellrebelauto.cutover.CutoverRestorePhase
import com.example.cellrebelauto.cutover.CutoverUnavailableReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

/**
 * PlanConfig persistence tests (AC-B5, design gate: buffer first-run required).
 * Uses a real DataStore file per test via PreferenceDataStoreFactory.
 * # 计划配置持久化测试：缓冲首启必填；每个测试使用独立的真实 DataStore 文件
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PlanConfigStoreTest {

    private lateinit var file: File

    @Before
    fun setUp() {
        file = File(
            System.getProperty("java.io.tmpdir"),
            "plan-config-test-${UUID.randomUUID()}.preferences_pb"
        )
    }

    @After
    fun tearDown() {
        file.delete()
    }

    private fun newStore(
        scope: CoroutineScope,
        gate: CutoverAccessGate = CutoverAccessGate.open()
    ) = PlanConfigStore(
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }),
        gate
    )

    @Test
    fun `default buffer is null until first set`() = runTest {
        val store = newStore(backgroundScope)
        assertNull(store.config.first().globalBufferSeconds)
    }

    @Test
    fun `timeout and settle have defaults 90 and 60`() = runTest {
        val config = newStore(backgroundScope).config.first()
        assertNull(config.globalBufferSeconds)
        assertEquals(90, config.testTimeoutSeconds)
        assertEquals(60, config.gpsSettleSeconds)
    }

    @Test
    fun `buffer emits value after set`() = runTest {
        val store = newStore(backgroundScope)
        store.setGlobalBufferSeconds(120)
        assertEquals(120, store.config.first().globalBufferSeconds)
    }

    @Test
    fun `timeout and settle are independently writable`() = runTest {
        val store = newStore(backgroundScope)
        store.setTestTimeoutSeconds(45)
        var config = store.config.first()
        assertEquals(45, config.testTimeoutSeconds)
        assertEquals(60, config.gpsSettleSeconds)

        store.setGpsSettleSeconds(30)
        config = store.config.first()
        assertEquals(45, config.testTimeoutSeconds)
        assertEquals(30, config.gpsSettleSeconds)
    }

    @Test
    fun `values persist across a new store instance over the same file`() = runTest {
        // # 第一阶段写入，然后关掉该 DataStore 作用域模拟进程退出
        val scope1 = CoroutineScope(backgroundScope.coroutineContext + Job())
        val store1 = newStore(scope1)
        store1.setGlobalBufferSeconds(120)
        store1.setTestTimeoutSeconds(45)
        scope1.cancel()

        // # 第二阶段：同一文件上的全新 store 实例应读到已持久化的值
        val store2 = newStore(backgroundScope)
        val config = store2.config.first()
        assertEquals(120, config.globalBufferSeconds)
        assertEquals(45, config.testTimeoutSeconds)
        assertEquals(60, config.gpsSettleSeconds)
    }

    @Test
    fun `stage toggles default to ON`() = runTest {
        // # F003 AC-F3-1：两个开关默认都开
        val config = newStore(backgroundScope).config.first()
        assertEquals(true, config.locationStageEnabled)
        assertEquals(true, config.testStageEnabled)
    }

    @Test
    fun `stage toggles are independently writable`() = runTest {
        // # F003 AC-F3-1：两个开关互不影响
        val store = newStore(backgroundScope)
        store.setLocationStageEnabled(false)
        var config = store.config.first()
        assertEquals(false, config.locationStageEnabled)
        assertEquals(true, config.testStageEnabled)

        store.setTestStageEnabled(false)
        config = store.config.first()
        assertEquals(false, config.locationStageEnabled)
        assertEquals(false, config.testStageEnabled)

        store.setLocationStageEnabled(true)
        config = store.config.first()
        assertEquals(true, config.locationStageEnabled)
        assertEquals(false, config.testStageEnabled)
    }

    @Test
    fun `stage toggles persist across a new store instance over the same file`() = runTest {
        // # F003 AC-F3-1/5：跨实例持久化（与上方 persistence 测试同模式）
        val scope1 = CoroutineScope(backgroundScope.coroutineContext + Job())
        val store1 = newStore(scope1)
        store1.setLocationStageEnabled(true)
        store1.setTestStageEnabled(false)
        scope1.cancel()

        val store2 = newStore(backgroundScope)
        val persisted = store2.config.first()
        assertEquals(true, persisted.locationStageEnabled)
        assertEquals(false, persisted.testStageEnabled)
    }

    @Test
    fun `normal writer is rejected before DataStore side effects while cutover owns admission`() = runTest {
        val gate = CutoverAccessGate.open()
        val store = newStore(backgroundScope, gate)
        val lease = (gate.acquireExclusive(identity) as CutoverExclusiveAdmission.Granted).lease

        val failure = runCatching { store.setGlobalBufferSeconds(120) }.exceptionOrNull()

        assertTrue(failure is CutoverAccessUnavailableException)
        assertEquals(CutoverUnavailableReason.CUTOVER_IN_PROGRESS, (failure as CutoverAccessUnavailableException).reason)
        assertTrue(lease.release(CutoverExclusiveRelease.OPEN))
        assertNull(store.config.first().globalBufferSeconds)
    }

    @Test
    fun `config flow waits while recovery is closed then reads the restored generation`() = runTest {
        val journal = CutoverRestoreJournal(identity, CutoverRestorePhase.STAGED)
        val gate = CutoverAccessGate.fromJournal(journal)
        val store = newStore(backgroundScope, gate)
        val pending = async { store.config.first() }
        runCurrent()
        assertFalse(pending.isCompleted)
        val lease = (gate.acquireExclusive(identity) as CutoverExclusiveAdmission.Granted).lease

        store.restore(archive(globalBufferSeconds = 77))
        runCurrent()
        assertFalse(pending.isCompleted)
        assertTrue(lease.release(CutoverExclusiveRelease.OPEN))

        assertEquals(77, pending.await().globalBufferSeconds)
    }

    private val identity = CutoverRestoreIdentity(
        archiveDigest = "sha256:${"d".repeat(64)}",
        captureId = "plan-config-gate"
    )

    private fun archive(globalBufferSeconds: Int): CutoverArchiveV2 = CutoverArchiveV2(
        sourcePackage = "com.example.cellrebelauto",
        captureId = identity.captureId,
        schemaVersion = 9,
        tables = emptyList(),
        preferences = CutoverPlanConfigSchema.preferenceTypes.map { (key, type) ->
            CutoverPreferenceEntry(
                key = key,
                type = type,
                present = key == "global_buffer_seconds",
                value = if (key == "global_buffer_seconds") globalBufferSeconds.toString() else null
            )
        }
    )
}
