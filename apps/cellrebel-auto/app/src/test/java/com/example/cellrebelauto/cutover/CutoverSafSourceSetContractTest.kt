package com.example.cellrebelauto.cutover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

class CutoverSafSourceSetContractTest {

    private val moduleRoot: File = sequenceOf(File("."), File("app"), File("../app"))
        .map { it.absoluteFile.normalize() }
        .firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
        ?: error("cannot locate app module root from ${File(".").absolutePath}")

    @Test
    fun directionalCapabilitiesExistOnlyInTheirOwningSourceSet() {
        val legacySources = kotlinSources("legacyId")
        val productSources = kotlinSources("productId")
        val mainSources = kotlinSources("main")

        assertTrue(legacySources.any { it.name == "LegacyCutoverExporter.kt" })
        assertTrue(legacySources.any { it.name == "CutoverSafSurface.kt" })
        assertTrue(legacySources.joinToString("\n") { it.readText() }
            .contains("ActivityResultContracts.CreateDocument"))
        assertFalse(legacySources.any { it.name.contains("Importer") })
        assertFalse(legacySources.joinToString("\n") { it.readText() }
            .contains("ActivityResultContracts.OpenDocument"))

        assertTrue(productSources.any { it.name == "ProductCutoverImporter.kt" })
        assertTrue(productSources.any { it.name == "CutoverSafSurface.kt" })
        assertTrue(productSources.joinToString("\n") { it.readText() }
            .contains("ActivityResultContracts.OpenDocument"))
        assertFalse(productSources.any { it.name.contains("Exporter") })
        assertFalse(productSources.joinToString("\n") { it.readText() }
            .contains("ActivityResultContracts.CreateDocument"))

        val mainText = mainSources.joinToString("\n") { it.readText() }
        assertFalse(mainText.contains("LegacyCutoverExporter"))
        assertFalse(mainText.contains("ProductCutoverImporter"))
    }

    @Test
    fun manifestsDeclareOneDirectionAndOnlyProductCanQueryTheLegacyInstall() {
        val legacy = manifest("legacyId")
        val product = manifest("productId")

        assertEquals("legacy-export", carrierDirection(legacy))
        assertEquals("product-import", carrierDirection(product))
        assertEquals(0, packageQueryCount(legacy, "com.example.cellrebelauto"))
        assertEquals(1, packageQueryCount(product, "com.example.cellrebelauto"))
    }

    @Test
    fun directionalTransfersHaveNoFileOrLoggingEscapeHatch() {
        val directionalText = (kotlinSources("legacyId") + kotlinSources("productId"))
            .joinToString("\n") { it.readText() }

        assertFalse(directionalText.contains("java.io.File"))
        assertFalse(directionalText.contains("File("))
        assertFalse(directionalText.contains("android.util.Log"))
        assertFalse(directionalText.contains("Log."))
        assertTrue(directionalText.contains("openOutputStream"))
        assertTrue(directionalText.contains("openInputStream"))
    }

    @Test
    fun cutoverEntryPointRemainsReachableOutsideTheProtectedDataBoundary() {
        val activity = File(
            moduleRoot,
            "src/main/java/com/example/cellrebelauto/ui/MainActivity.kt"
        ).readText()
        val planBranch = activity.substringAfter("Screen.PLAN ->")

        assertTrue(planBranch.indexOf("CutoverSafSurface(") >= 0)
        assertTrue(
            planBranch.indexOf("CutoverSafSurface(") <
                planBranch.indexOf("CutoverDataBoundary(planState)")
        )
    }

    private fun kotlinSources(sourceSet: String): List<File> =
        File(moduleRoot, "src/$sourceSet").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun manifest(sourceSet: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(File(moduleRoot, "src/$sourceSet/AndroidManifest.xml"))

    private fun carrierDirection(document: org.w3c.dom.Document): String {
        val xpath = XPathFactory.newInstance().newXPath()
        return xpath.evaluate(
            "string(//meta-data[@*[local-name()='name']='com.example.cellrebelauto.cutover.CARRIER_DIRECTION']/@*[local-name()='value'])",
            document
        )
    }

    private fun packageQueryCount(document: org.w3c.dom.Document, packageName: String): Int {
        val xpath = XPathFactory.newInstance().newXPath()
        val nodes = xpath.evaluate(
            "//queries/package[@*[local-name()='name']='$packageName']",
            document,
            XPathConstants.NODESET
        ) as org.w3c.dom.NodeList
        return nodes.length
    }
}
