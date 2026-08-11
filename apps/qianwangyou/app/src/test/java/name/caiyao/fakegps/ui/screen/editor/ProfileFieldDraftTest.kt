package name.caiyao.fakegps.ui.screen.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import name.caiyao.fakegps.data.db.ProfileEntity
import name.caiyao.fakegps.data.db.ProfileEntityCodec

class ProfileFieldDraftTest {

    @Test
    fun `editor reference routes WCDMA dbm to the configured RSCP column`() {
        val columns = referenceColumns(mapOf("wcdma_rscp" to "-88", "tac" to "7"))
        assertEquals(
            "wcdma_rscp",
            name.caiyao.fakegps.verify.DeviceObserver.wcdmaDbmColumn(columns),
        )
    }

    @Test
    fun `blank unavailable and concrete values are mutually exclusive`() {
        var draft = ProfileFieldDraft.update(emptyMap(), "tac", "--")
        assertEquals("--", draft["tac"])

        draft = ProfileFieldDraft.update(draft, "tac", "4095")
        assertEquals("4095", draft["tac"])

        draft = ProfileFieldDraft.update(draft, "tac", "")
        assertFalse(draft.containsKey("tac"))
    }

    @Test
    fun `unsupported token remains visible and blocks save instead of leaving a dash fragment`() {
        val afterFirstDash = ProfileFieldDraft.update(emptyMap(), "latitude", "-")
        val afterSecondDash = ProfileFieldDraft.update(afterFirstDash, "latitude", "--")

        assertEquals("--", afterSecondDash["latitude"])
        assertTrue(ProfileFieldDraft.validationErrors(afterSecondDash).containsKey("latitude"))
    }

    @Test
    fun `invalid numeric text blocks save instead of degrading to passthrough`() {
        val draft = ProfileFieldDraft.update(emptyMap(), "latitude", "-")
        assertTrue(ProfileFieldDraft.validationErrors(draft).containsKey("latitude"))
    }

    @Test
    fun `editor and importer share range boolean JSON and 36-bit NCI validation`() {
        val errors = ProfileFieldDraft.validationErrors(
            mapOf(
                "latitude" to "91",
                "wifi_hidden" to "2",
                "neighbor_cells_json" to "{}",
                "nci" to "68719476735",
            ),
        )

        assertTrue(errors.containsKey("latitude"))
        assertTrue(errors.containsKey("wifi_hidden"))
        assertTrue(errors.containsKey("neighbor_cells_json"))
        assertFalse(errors.containsKey("nci"))
        assertEquals(68_719_476_735L, mapToEntity(mapOf("nci" to "68719476735"), 0L).nci)
    }

    @Test
    fun `GSM BER accepts measured values and unknown sentinel but rejects reserved gap`() {
        assertFalse(ProfileFieldDraft.validationErrors(mapOf("gsm_ber" to "7")).containsKey("gsm_ber"))
        assertFalse(ProfileFieldDraft.validationErrors(mapOf("gsm_ber" to "99")).containsKey("gsm_ber"))
        assertTrue(ProfileFieldDraft.validationErrors(mapOf("gsm_ber" to "8")).containsKey("gsm_ber"))
    }

    @Test
    fun `text fields preserve significant leading and trailing whitespace`() {
        val entity = mapToEntity(
            mapOf("wifi_ssid" to "  cafe network  ", "operator_name" to " Carrier "),
            0L,
        )

        assertEquals("  cafe network  ", entity.wifiSsid)
        assertEquals(" Carrier ", entity.operatorName)
    }

    @Test
    fun `editing an imported profile preserves its custom archive name`() {
        val entity = mapToEntity(
            draft = mapOf("latitude" to "51", "longitude" to "31"),
            id = 17L,
            addname = "Imported favorite",
        )

        assertEquals("Imported favorite", entity.addname)
        assertEquals("Imported favorite", profileNameOverride(entity))
        assertNull(
            profileNameOverride(
                entity.copy(addname = ProfileEntityCodec.generatedName(entity.latitude, entity.longitude)),
            ),
        )
    }

    @Test
    fun `PLMN unavailable is one paired state`() {
        val unavailable = ProfileFieldDraft.update(emptyMap(), "mcc", " -- ")
        assertEquals("--", unavailable["mcc"])
        assertEquals("--", unavailable["mnc"])

        val concrete = ProfileFieldDraft.update(unavailable, "mcc", "460")
        assertEquals("460", concrete["mcc"])
        assertFalse(concrete.containsKey("mnc"))
    }

    @Test
    fun `split never leaks display token into typed values`() {
        val split = ProfileFieldDraft.split(mapOf(
            "tac" to "--",
            "operator_name" to "Test Carrier",
        ))

        assertEquals(setOf("tac"), split.unavailable)
        assertEquals(mapOf("operator_name" to "Test Carrier"), split.values)
        assertFalse(split.values.values.contains("--"))
    }

    @Test
    fun `loading overlays unavailable state on concrete map`() {
        val shown = ProfileFieldDraft.forDisplay(
            values = mapOf("operator_name" to "Test Carrier"),
            unavailable = setOf("tac"),
        )
        assertEquals("--", shown["tac"])
        assertEquals("Test Carrier", shown["operator_name"])
        assertTrue(shown.size == 2)
    }

    @Test
    fun `entity boundary preserves value unavailable and passthrough states`() {
        val entity = mapToEntity(
            mapOf("tac" to "--", "operator_name" to "Carrier"),
            id = 7L,
        )

        assertEquals(7L, entity.id)
        assertEquals(null, entity.tac)
        assertEquals("Carrier", entity.operatorName)
        assertEquals("[\"tac\"]", entity.unavailableFields)
        assertEquals("--", entityToMap(entity)["tac"])

        val passthrough = mapToEntity(ProfileFieldDraft.update(entityToMap(entity), "tac", ""), 7L)
        assertEquals(null, passthrough.tac)
        assertEquals(null, passthrough.unavailableFields)
    }

    @Test
    fun `corrupt metadata can be dropped without losing ordinary values`() {
        val entity = ProfileEntity(id = 9L, tac = 123, unavailableFields = "not-json")
        val recovered = entityToMap(entity.copy(unavailableFields = null))
        assertEquals("123", recovered["tac"])
    }
}
