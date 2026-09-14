package com.example.cellrebelauto.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.plan.ParseResult
import com.example.cellrebelauto.model.plan.WorklistParser
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #190 导入链端到端 oracle：5 列 CI 契约 CSV → [WorklistParser] →
 * [PlanRepository.importPlan] → `location_tasks.expectedCi` 逐行落库；
 * 4 列旧契约走同一条链 ci=null（向后兼容）。fail-closed 值域校验
 * （0..268435455，#193 同款）在 parser 层已拒，仓库只收 Success 行。
 *
 * # 期望 ci 导入链 oracle：解析→仓库→DB，5 列落库 / 4 列 null
 */
@RunWith(RobolectricTestRunner::class)
class PlanImportExpectedCiTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: PlanRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).build()
        repo = PlanRepository(db, com.example.cellrebelauto.cutover.CutoverAccessGate.open())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun importPlan_persistsExpectedCi_fromCiContractCsv() = runTest {
        val parsed = WorklistParser.parse(
            """
            longitude,latitude,priority,required_successes,ci
            29.9243986,49.8714584,3,3,28918569
            29.94462,49.8240183,3,3,29592117
            """.trimIndent()
        )
        assertTrue(parsed is ParseResult.Success)

        val planId = repo.importPlan(
            sourceFileName = "plan.csv",
            globalBufferSeconds = 10,
            rows = (parsed as ParseResult.Success).rows,
            importedAt = 1_000L,
        )

        val tasks = repo.getTasks(planId).sortedBy { it.csvRow }
        assertEquals(2, tasks.size)
        assertEquals(28918569L, tasks[0].expectedCi)
        assertEquals(29592117L, tasks[1].expectedCi)
    }

    @Test
    fun importPlan_legacy4ColumnCsv_keepsExpectedCiNull() = runTest {
        val parsed = WorklistParser.parse(
            """
            longitude,latitude,priority,required_successes
            116.397,39.908,1,3
            """.trimIndent()
        )
        assertTrue(parsed is ParseResult.Success)

        val planId = repo.importPlan(
            sourceFileName = "legacy.csv",
            globalBufferSeconds = 10,
            rows = (parsed as ParseResult.Success).rows,
            importedAt = 2_000L,
        )

        val task = repo.getTasks(planId).single()
        assertNull("4 列旧清单 = 无期望（诚实缺席）", task.expectedCi)
    }

    @Test
    fun resetPlanAsFreshGeneration_copiesExpectedCi() = runTest {
        // 重置 = 同一 worklist 的新代际：期望 ci 是 worklist 定义的一部分，必须随行复制。
        val parsed = WorklistParser.parse(
            """
            longitude,latitude,priority,required_successes,ci
            29.9243986,49.8714584,3,1,28918569
            """.trimIndent()
        )
        val planId = repo.importPlan(
            sourceFileName = "plan.csv", globalBufferSeconds = 0,
            rows = (parsed as ParseResult.Success).rows, importedAt = 3_000L,
        )
        // 完成 trusted 配额（1/1）并把任务状态投影翻成 completed，让计划可重置。
        val taskId = repo.getTasks(planId).single().id
        db.trustedQuotaDao().insert(
            com.example.cellrebelauto.model.ledger.TrustedQuotaEntry(
                attemptId = 0, taskId = taskId,
                evidenceDigest = "d", committedAt = 1L,
            )
        )
        assertEquals(1, db.locationTaskDao().completeTaskIfQuotaReached(taskId))

        val outcome = repo.resetPlanAsFreshGeneration(nowMs = 9_000L)
        assertTrue(outcome is PlanRepository.PlanResetOutcome.Reset)
        val newPlanId = (outcome as PlanRepository.PlanResetOutcome.Reset).newPlanId
        assertEquals(28918569L, repo.getTasks(newPlanId).single().expectedCi)
    }
}
