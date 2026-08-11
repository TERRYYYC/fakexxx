package name.caiyao.fakegps.data.importer

import java.nio.charset.StandardCharsets
import name.caiyao.fakegps.data.model.FieldSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileImportTemplateTest {
    @Test
    fun `CSV template is BOM-safe and follows the complete canonical field order`() {
        val expectedHeaders = listOf("addname") +
            FieldSpec.allCategories().values.flatten().map { it.dbColumn }

        assertEquals(expectedHeaders, ProfileImportTemplate.headers())
        assertEquals(86, expectedHeaders.size)
        assertEquals(expectedHeaders.size, expectedHeaders.distinct().size)
        assertFalse(expectedHeaders.contains("id"))

        val bytes = ProfileImportTemplate.csvBytes()
        assertTrue(bytes.take(3) == listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        assertEquals(
            expectedHeaders.joinToString(",") + "\r\n",
            bytes.copyOfRange(3, bytes.size).toString(StandardCharsets.UTF_8),
        )
    }

    @Test
    fun `downloaded template becomes importable after the user fills one profile row`() {
        val edited = ProfileImportTemplate.csvBytes() +
            "示例档案,39.908823,116.397470\r\n".toByteArray(StandardCharsets.UTF_8)

        val analysis = ProfileArchiveParser().parse(ProfileImportTemplate.DEFAULT_FILE_NAME, edited)

        assertTrue(analysis is ProfileImportAnalysis.Ready)
        val ready = analysis as ProfileImportAnalysis.Ready
        assertEquals(1, ready.records.size)
        assertEquals("示例档案", ready.records.single().addname)
        assertEquals(39.908823, ready.records.single().latitude!!, 0.0)
        assertEquals(116.397470, ready.records.single().longitude!!, 0.0)
    }
}
