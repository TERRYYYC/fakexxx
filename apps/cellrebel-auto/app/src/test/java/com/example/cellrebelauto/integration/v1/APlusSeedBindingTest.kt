package com.example.cellrebelauto.integration.v1

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * PR #62 merge-gate P1 (codex inline 3898022696, re-verified by Sol at faf561d) — the
 * repeated-seed / stale-id counterexample, red-first.
 *
 * The false green: `seed_plan` twice leaves TWO FX-G2-10A plans in the DB, both satisfying
 * `verifyPlanTopology`, so a `start_run` that only checks topology would start the first (stale)
 * plan — whose tasks may already carry statuses, attempts and trusted-quota rows — while reporting
 * the run as plan-bound. The fix binds start_run to the exact latest seed identity
 * (plan_id AND seed_token) through [APlusSeedBinding]; this test seeds through the same
 * parse → toPlan/toTasks → insertPlanWithTasks path the Activity uses and asserts the binding
 * refuses everything except the latest (id, token).
 */
@RunWith(RobolectricTestRunner::class)
class APlusSeedBindingTest {

    private lateinit var db: AppDatabase
    private lateinit var prefs: SharedPreferences
    private val frozenQuotas = listOf(2, 1, 3, 1, 2, 1, 1, 3, 1, 2)

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        prefs = APlusSeedBinding.prefs(ctx)
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun itemJson(index: Int, quota: Int): String = """
        {
          "fixtureIndex": $index, "journeyCaseId": "J10A-${"%02d".format(index)}",
          "expectedScheduleItemId": "profile-$index", "requiredSuccesses": $quota,
          "addname": "G2-A10-${"%02d".format(index)} Place", "latitude": ${50.4 + index * 0.001},
          "longitude": ${30.5 + index * 0.001}, "altitude": 150.0, "accuracy": 3.0,
          "tac": ${27100 + index}, "wifiSsid": "G2-A10-${"%02d".format(index)}"
        }
    """.trimIndent()

    private fun payload(): String = """
        {
          "fixtureId": "FX-G2-10A",
          "scheduleId": "qwy-default-schedule",
          "totalRequiredSuccesses": 17,
          "items": [${(1..10).joinToString(",") { itemJson(it, frozenQuotas[it - 1]) }}]
        }
    """.trimIndent()

    /** Seed exactly the way APlusSeedActivity.seedPlan does: parse → toPlan/toTasks → insertPlanWithTasks. */
    private fun seedOnce(): Long = runBlocking {
        val items = APlus10APlanSeed.parsePayload(payload())
        db.planDao().insertPlanWithTasks(APlus10APlanSeed.toPlan(items), APlus10APlanSeed.toTasks(items))
    }

    private fun topologyMismatch(planId: Long): String? = runBlocking {
        val plan = db.planDao().getPlanById(planId) ?: return@runBlocking "plan $planId missing"
        APlus10APlanSeed.verifyPlanTopology(plan, db.locationTaskDao().getTasksForPlan(planId))
    }

    @Test
    fun `two seeds - the first plan passes topology but is refused as stale, only the latest id plus token starts`() {
        val p1 = seedOnce()
        val t1 = APlusSeedBinding.newToken()
        APlusSeedBinding.record(prefs, p1, t1, "digest", seededAtElapsedMs = 1_000L)
        val p2 = seedOnce()
        val t2 = APlusSeedBinding.newToken()
        APlusSeedBinding.record(prefs, p2, t2, "digest", seededAtElapsedMs = 2_000L)
        assertNotEquals("a second seed_plan must produce a new plan row", p1, p2)

        // The false green Sol reproduced at faf561d: BOTH plans satisfy the registered topology.
        assertNull("stale plan still matches topology", topologyMismatch(p1))
        assertNull("latest plan matches topology", topologyMismatch(p2))

        // Identity binding is what refuses the stale one — even with the token its own report printed.
        val stale = APlusSeedBinding.verifyLatestSeed(prefs, p1, t1)
        assertNotNull("stale plan id must be refused", stale)
        assertTrue("refusal must name the stale-seed shape: $stale", stale!!.contains("is not the latest seed"))

        assertNull("the latest seed (id + token) must be accepted", APlusSeedBinding.verifyLatestSeed(prefs, p2, t2))
        assertEquals(2L, APlusSeedBinding.latest(prefs)!!.generation)
    }

    @Test
    fun `latest plan id with a wrong or missing token is refused`() {
        val p1 = seedOnce()
        val t1 = APlusSeedBinding.newToken()
        APlusSeedBinding.record(prefs, p1, t1, "digest", seededAtElapsedMs = 1_000L)

        val wrong = APlusSeedBinding.verifyLatestSeed(prefs, p1, APlusSeedBinding.newToken())
        assertNotNull(wrong)
        assertTrue("wrong token: $wrong", wrong!!.contains("seed_token does not match"))

        val missing = APlusSeedBinding.verifyLatestSeed(prefs, p1, null)
        assertNotNull(missing)
        assertTrue("missing token: $missing", missing!!.contains("needs --es seed_token"))

        val empty = APlusSeedBinding.verifyLatestSeed(prefs, p1, "")
        assertNotNull(empty)
        assertTrue("empty token: $empty", empty!!.contains("needs --es seed_token"))
    }

    @Test
    fun `no recorded seed refuses every plan id - a plan that merely exists is not a seed`() {
        val p1 = seedOnce()
        assertNull("the plan itself is well-formed", topologyMismatch(p1))
        val refused = APlusSeedBinding.verifyLatestSeed(prefs, p1, "anything")
        assertNotNull(refused)
        assertTrue("no-record shape: $refused", refused!!.contains("no verified seed recorded"))
    }

    @Test
    fun `clearing the record (pm clear shape) fails closed for the previously latest seed`() {
        val p1 = seedOnce()
        val t1 = APlusSeedBinding.newToken()
        APlusSeedBinding.record(prefs, p1, t1, "digest", seededAtElapsedMs = 1_000L)
        assertNull(APlusSeedBinding.verifyLatestSeed(prefs, p1, t1))

        prefs.edit().clear().commit()
        val refused = APlusSeedBinding.verifyLatestSeed(prefs, p1, t1)
        assertNotNull(refused)
        assertTrue(refused!!.contains("no verified seed recorded"))
    }

    @Test
    fun `record is durable and generation increments per seed`() {
        val t1 = APlusSeedBinding.newToken()
        val t2 = APlusSeedBinding.newToken()
        assertEquals(1L, APlusSeedBinding.record(prefs, 11L, t1, "d1", 10L).generation)
        assertEquals(2L, APlusSeedBinding.record(prefs, 12L, t2, "d2", 20L).generation)
        val latest = APlusSeedBinding.latest(prefs)!!
        assertEquals(12L, latest.planId)
        assertEquals(t2, latest.token)
        assertEquals("d2", latest.fixtureDigest)
        assertEquals(20L, latest.seededAtElapsedMs)
        assertEquals(32, t1.length)
        assertNotEquals(t1, t2)
    }
}
