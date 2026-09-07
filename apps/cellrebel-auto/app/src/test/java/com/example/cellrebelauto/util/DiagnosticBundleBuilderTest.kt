package com.example.cellrebelauto.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * T7 P1.1 — the diagnostic bundle oracle.
 *
 * The bundle replaces "scroll to the bottom and screenshot": one zip with the
 * persisted rolling log, the engine-state JSON, the contract readback, and the
 * DB snapshot. A manifest entry enumerates the required sections, and the
 * builder REFUSES to emit a bundle missing any of them — an incomplete
 * diagnostic zip is worse than none.
 *
 * Killing mutations:
 *  - a builder that silently omits a failed section fails the
 *    missing-section-rejected test;
 *  - a manifest that drifts from the actual entries fails the
 *    manifest-lists-every-section test;
 *  - an engine-state JSON that drops the self-heal flags fails the field test.
 *
 * # 诊断包 oracle：清单枚举每项必在包内；缺项/空项在构造时拒绝；状态 JSON 字段可查
 */
@RunWith(RobolectricTestRunner::class)
class DiagnosticBundleBuilderTest {

    private fun fullSections(): Map<String, String> = mapOf(
        DiagnosticBundleBuilder.ENTRY_MANIFEST to
            DiagnosticBundleBuilder.REQUIRED_ENTRIES.joinToString("\n"),
        DiagnosticBundleBuilder.ENTRY_LOGS to
            "[00:00:01] Service connected\n[00:00:05] ERROR: provider schedule is EXHAUSTED\n",
        DiagnosticBundleBuilder.ENTRY_ENGINE to "{}",
        DiagnosticBundleBuilder.ENTRY_CONTRACT to
            "handshake=Connected serviceVersion=1.0 exhausted=false profileRefs=5\n",
        DiagnosticBundleBuilder.ENTRY_DB to
            "plan,worklist.csv,rows=2\nattempt,1,succeeded\n",
    )

    // ---- completeness ------------------------------------------------------------

    @Test
    fun `manifest entry lists every required section`() {
        val manifest = DiagnosticBundleBuilder.readEntry(
            DiagnosticBundleBuilder.build(fullSections()),
            DiagnosticBundleBuilder.ENTRY_MANIFEST
        )
        assertNotNull(manifest)
        for (entry in DiagnosticBundleBuilder.REQUIRED_ENTRIES) {
            assertTrue("manifest missing $entry", manifest!!.contains(entry))
        }
    }

    @Test
    fun `every required entry is present and non-blank in the bundle`() {
        val zip = DiagnosticBundleBuilder.build(fullSections())
        for (entry in DiagnosticBundleBuilder.REQUIRED_ENTRIES) {
            val content = DiagnosticBundleBuilder.readEntry(zip, entry)
            assertNotNull("entry $entry missing from bundle", content)
            assertTrue("entry $entry is blank", content!!.isNotBlank())
        }
    }

    @Test
    fun `missing a required section is rejected at build time`() {
        val sections = fullSections() - DiagnosticBundleBuilder.ENTRY_CONTRACT
        try {
            DiagnosticBundleBuilder.build(sections)
            fail("a bundle without the contract readback must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains(DiagnosticBundleBuilder.ENTRY_CONTRACT))
        }
    }

    @Test
    fun `blank section content is rejected too`() {
        val sections = fullSections().toMutableMap()
        sections[DiagnosticBundleBuilder.ENTRY_LOGS] = "   "
        try {
            DiagnosticBundleBuilder.build(sections)
            fail("a blank log section must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains(DiagnosticBundleBuilder.ENTRY_LOGS))
        }
    }

    @Test
    fun `bundle bytes carry the zip magic`() {
        val zip = DiagnosticBundleBuilder.build(fullSections())
        assertEquals('P'.code.toByte(), zip[0])
        assertEquals('K'.code.toByte(), zip[1])
    }

    // ---- engine-state JSON -----------------------------------------------------------

    @Test
    fun `engine state json carries the dashboard truth including self-heal flags`() {
        val json = DiagnosticBundleBuilder.engineStateJson(
            DiagnosticBundleBuilder.EngineStateDump(
                stateName = "PAUSED",
                isRunning = false,
                cycleCount = 7,
                currentTaskCsvRow = 3,
                lastFailureOrdinal = 4,
                lastFailureReason = "UNTRUSTED",
                serviceConnected = true,
                trustedDone = 2,
                trustedTotal = 10,
                startStatus = "Accepted(session=1)",
                attemptWatchdogEnabled = false,
                coordinateGuardEnabled = true,
                serviceReconnectAutoResumeEnabled = true,
                generatedAtMs = 1_700_000_000_000L,
            )
        )
        val obj = org.json.JSONObject(json)
        assertEquals("PAUSED", obj.getString("stateName"))
        assertEquals(false, obj.getBoolean("isRunning"))
        assertEquals(7, obj.getInt("cycleCount"))
        assertEquals(3, obj.getInt("currentTaskCsvRow"))
        assertEquals("UNTRUSTED", obj.getString("lastFailureReason"))
        assertEquals(true, obj.getBoolean("serviceConnected"))
        assertEquals(2, obj.getInt("trustedDone"))
        assertEquals(10, obj.getInt("trustedTotal"))
        // P1.3 self-heal trio must be in every diagnostic export.
        assertEquals(false, obj.getBoolean("attemptWatchdogEnabled"))
        assertEquals(true, obj.getBoolean("coordinateGuardEnabled"))
        assertEquals(true, obj.getBoolean("serviceReconnectAutoResumeEnabled"))
        assertEquals(1_700_000_000_000L, obj.getLong("generatedAtMs"))
    }

    @Test
    fun `engine state json tolerates null optionals`() {
        val json = DiagnosticBundleBuilder.engineStateJson(
            DiagnosticBundleBuilder.EngineStateDump(
                stateName = "IDLE", isRunning = false, cycleCount = 0,
                currentTaskCsvRow = null, lastFailureOrdinal = null, lastFailureReason = null,
                serviceConnected = false, trustedDone = 0, trustedTotal = 0,
                startStatus = "IDLE",
                attemptWatchdogEnabled = true, coordinateGuardEnabled = true,
                serviceReconnectAutoResumeEnabled = false,
                generatedAtMs = 1L,
            )
        )
        val obj = org.json.JSONObject(json)
        assertTrue(obj.isNull("currentTaskCsvRow"))
        assertTrue(obj.isNull("lastFailureReason"))
    }
}
