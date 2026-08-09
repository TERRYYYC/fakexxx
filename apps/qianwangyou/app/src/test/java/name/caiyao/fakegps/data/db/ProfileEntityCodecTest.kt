package name.caiyao.fakegps.data.db

import name.caiyao.fakegps.data.model.FieldSpec
import name.caiyao.fakegps.data.model.FieldType
import name.caiyao.fakegps.data.model.ProfileFieldValueValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileEntityCodecTest {
    @Test
    fun `all configurable fields normalize and round trip through the one entity boundary`() {
        val specs = FieldSpec.allCategories().values.flatten()
        assertEquals(85, specs.size)
        assertEquals(specs.size, specs.map { it.dbColumn }.toSet().size)

        val normalized = specs.associate { spec ->
            val result = ProfileFieldValueValidator.normalize(spec.dbColumn, validValue(spec))
            assertNull("${spec.dbColumn}: ${result.error}", result.error)
            spec.dbColumn to requireNotNull(result.value)
        }

        val entity = ProfileEntityCodec.fromDraft(normalized, id = 42L, addname = "全字段档案")
        assertEquals(normalized, ProfileEntityCodec.toDraft(entity))
        assertEquals(0L, ProfileEntityCodec.canonical(entity).id)
        assertTrue(entity.addname == "全字段档案")
    }

    private fun validValue(spec: FieldSpec): String = when (spec.dbColumn) {
        "neighbor_cells_json" -> "[]"
        "gsm_rssi", "wcdma_rssi", "wcdma_rscp", "lte_rssi", "lte_rsrp",
        "nr_ss_rsrp", "nr_csi_rsrp" -> "-80"
        "wcdma_ecno", "lte_rsrq", "nr_ss_rsrq", "nr_csi_rsrq" -> "-10"
        "wifi_rssi" -> "-60"
        "wifi_frequency" -> "2412"
        else -> when (spec.type) {
            FieldType.TEXT -> "sample"
            FieldType.BOOLEAN -> "true"
            FieldType.INTEGER -> "1"
            FieldType.DOUBLE, FieldType.FLOAT -> "1"
        }
    }
}
