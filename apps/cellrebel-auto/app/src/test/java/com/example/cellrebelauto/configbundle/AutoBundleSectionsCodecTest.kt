package com.example.cellrebelauto.configbundle

import com.example.cellrebelauto.model.plan.PlanConfig
import com.example.cellrebelauto.model.plan.ParseResult
import com.example.cellrebelauto.model.plan.WorklistParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T8: Auto section codecs. The plan section REUSES the T2 CSV semantics verbatim —
 * the exported plan.csv starts with the exact [WorklistParser.HEADER] and must parse
 * back through [WorklistParser] (atomic validation) with identical rows.
 * # 计划区段逐字复用 T2 的 CSV 语义；导出的 plan.csv 必须能被 WorklistParser 原样读回
 */
class AutoBundleSectionsCodecTest {

    private val rows = listOf(
        WorklistRowData(longitude = 30.11, latitude = 50.11, priority = 1, requiredSuccesses = 2),
        WorklistRowData(longitude = -0.5, latitude = 51.5, priority = 0, requiredSuccesses = 1),
    )

    // ---- plan section: T2 CSV semantics round trip ----

    @Test
    fun `exported plan csv starts with the exact WorklistParser header`() {
        val csv = AutoBundleSections.encodePlanCsv(rows)

        assertEquals(WorklistParser.HEADER, csv.lines().first())
    }

    @Test
    fun `exported plan csv parses back through WorklistParser with identical rows`() {
        val parsed = WorklistParser.parse(AutoBundleSections.encodePlanCsv(rows))

        assertTrue("expected Success, got $parsed", parsed is ParseResult.Success)
        val back = (parsed as ParseResult.Success).rows
        assertEquals(rows.size, back.size)
        assertEquals(30.11, back[0].longitude, 0.0)
        assertEquals(50.11, back[0].latitude, 0.0)
        assertEquals(1, back[0].priority)
        assertEquals(2, back[0].requiredSuccesses)
        assertEquals(1, back[0].csvRow)
        assertEquals(2, back[1].csvRow)
    }

    // ---- plan config section ----

    @Test
    fun `plan config round trips buffer timeout settle and stage toggles`() {
        val config = PlanConfig(
            globalBufferSeconds = 42,
            testTimeoutSeconds = 120,
            gpsSettleSeconds = 30,
            locationStageEnabled = false,
            testStageEnabled = true,
        )

        val back = AutoBundleSections.decodePlanConfig(
            AutoBundleSections.encodePlanConfig(config, planSourceFileName = "worklist.csv")
                .toByteArray(Charsets.UTF_8),
        )

        assertTrue(back is AutoBundleSections.PlanConfigResult.Ok)
        back as AutoBundleSections.PlanConfigResult.Ok
        assertEquals(config, back.config)
        assertEquals("worklist.csv", back.planSourceFileName)
    }

    @Test
    fun `plan config decode rejects malformed json`() {
        assertTrue(
            AutoBundleSections.decodePlanConfig("not json".toByteArray(Charsets.UTF_8))
                is AutoBundleSections.PlanConfigResult.Error,
        )
    }

    // ---- pairing fingerprints: privacy red line ----

    @Test
    fun `pairing section carries only the fingerprint triple - no trust state`() {
        val json = AutoBundleSections.encodePairing(
            listOf(
                ProviderFingerprint(
                    applicationId = "test.bundle.provider",
                    signerDigest = "ab12cd34",
                    approvedVersionCode = 7,
                ),
            ),
        )

        val provider = JSONObject(json).getJSONArray("providers").getJSONObject(0)
        val keys = mutableListOf<String>()
        val it = provider.keys()
        while (it.hasNext()) keys.add(it.next())
        assertEquals(
            setOf("applicationId", "currentSignerDigest", "approvedVersionCode"),
            keys.toSet(),
        )
    }
}

/** Export-side row shape; csvRow is assigned by the section codec (data row ordinal). */
data class WorklistRowData(
    val longitude: Double,
    val latitude: Double,
    val priority: Int,
    val requiredSuccesses: Int,
)
