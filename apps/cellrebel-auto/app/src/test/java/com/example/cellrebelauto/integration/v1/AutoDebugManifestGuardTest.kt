package com.example.cellrebelauto.integration.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/**
 * Guard for the cellrebel-auto DEBUG manifest's probe Activity declarations.
 *
 * WHY: qianwangyou already has [DebugAcceptanceManifestGuardTest] guarding its
 * debug manifest, but the cellrebel-auto side had NO equivalent — removing
 * `android:launchMode="singleTop"` from either probe Activity (or the Activity
 * declaration itself) would pass all existing tests. This is the "half-sided
 * guard" gap identified in R5 review.
 *
 * Mirrors the qianwangyou guard's structural assertion technique: XPath on the
 * parsed XML, not string search, so a commented-out declaration is correctly
 * caught.
 *
 * Deliberately NOT a §10 ledger row — this guards build configuration, not
 * contract semantics.
 */
class AutoDebugManifestGuardTest {

    private val moduleRoot: File = sequenceOf(File("."), File("app"), File("../app"))
        .map { it.absoluteFile.normalize() }
        .firstOrNull { File(it, "src/debug/AndroidManifest.xml").isFile }
        ?: error("cannot locate the app module root from ${File(".").absolutePath}")

    private val manifestFile: File = File(moduleRoot, "src/debug/AndroidManifest.xml")

    private val doc by lazy {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        factory.newDocumentBuilder().parse(manifestFile)
    }

    private val xpath by lazy { XPathFactory.newInstance().newXPath() }

    /**
     * Count XML element nodes matching an XPath expression in the parsed manifest.
     * Returns 0 for commented-out or structurally absent declarations.
     */
    private fun countNodes(xpathExpr: String): Int {
        val nodes = xpath.evaluate(xpathExpr, doc, XPathConstants.NODESET)
            as org.w3c.dom.NodeList
        return nodes.length
    }

    private fun mustHaveNode(xpathExpr: String, why: String) {
        val count = countNodes(xpathExpr)
        assertTrue(
            "debug manifest: expected ≥1 node for '$xpathExpr' but found $count — $why",
            count >= 1,
        )
    }

    /** Both probe Activities must be declared as structural XML elements. */
    @Test
    fun probeActivitiesAreDeclared() {
        mustHaveNode(
            "//activity[contains(@*[local-name()='name'], 'HandshakeProbeActivity')]",
            "the Environment Control handshake probe must be declared",
        )
        mustHaveNode(
            "//activity[contains(@*[local-name()='name'], 'FullLoopProbeActivity')]",
            "the Environment Control full loop probe must be declared",
        )
    }

    /**
     * Both probe Activities MUST have `android:launchMode="singleTop"`.
     *
     * Without singleTop, a second `adb shell am start` creates a new Activity
     * instance instead of delivering to `onNewIntent`. The extras (on PairingApproval)
     * or the re-run signal (on probes) are silently lost, and F-11 regresses.
     *
     * This guard covers the Auto side that the qianwangyou DebugAcceptanceManifestGuardTest
     * does not reach — the "half-sided guard" gap R5 identified.
     */
    @Test
    fun probeActivitiesHaveSingleTopLaunchMode() {
        // HandshakeProbeActivity must have singleTop
        val handshakeNodes = xpath.evaluate(
            "//activity[contains(@*[local-name()='name'], 'HandshakeProbeActivity')]" +
                "[@*[local-name()='launchMode']='singleTop']",
            doc, XPathConstants.NODESET,
        ) as org.w3c.dom.NodeList
        assertTrue(
            "HandshakeProbeActivity must have android:launchMode=\"singleTop\" " +
                "— without it, F-11 (extras dropped on second am start) regresses",
            handshakeNodes.length >= 1,
        )

        // FullLoopProbeActivity must have singleTop
        val fullLoopNodes = xpath.evaluate(
            "//activity[contains(@*[local-name()='name'], 'FullLoopProbeActivity')]" +
                "[@*[local-name()='launchMode']='singleTop']",
            doc, XPathConstants.NODESET,
        ) as org.w3c.dom.NodeList
        assertTrue(
            "FullLoopProbeActivity must have android:launchMode=\"singleTop\" " +
                "— without it, F-11 (extras dropped on second am start) regresses",
            fullLoopNodes.length >= 1,
        )
    }

    /**
     * Mutation self-check: removing launchMode="singleTop" must make the guard fail.
     *
     * Reads the manifest source, removes the `singleTop` attribute from one Activity,
     * re-parses, and verifies the XPath no longer matches — proving the guard has
     * killing power over F-11 regressions.
     */
    @Test
    fun removedSingleTopIsDetected() {
        val manifestSource = manifestFile.readText()
        // Baseline: the real manifest must contain singleTop for FullLoopProbeActivity
        assertTrue(
            "baseline: FullLoopProbeActivity should have singleTop in the manifest",
            manifestSource.contains("FullLoopProbeActivity") && manifestSource.contains("singleTop"),
        )

        // Mutate: remove singleTop from the FullLoopProbeActivity declaration
        val mutated = manifestSource.replace(
            """android:launchMode="singleTop"
            android:label="EC v1 full loop probe"""",
            """android:label="EC v1 full loop probe"""",
        )

        val mutatedDoc = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(mutated.byteInputStream())
        val mutatedXpath = XPathFactory.newInstance().newXPath()
        val count = (mutatedXpath.evaluate(
            "//activity[contains(@*[local-name()='name'], 'FullLoopProbeActivity')]" +
                "[@*[local-name()='launchMode']='singleTop']",
            mutatedDoc, XPathConstants.NODESET,
        ) as org.w3c.dom.NodeList).length

        assertEquals(
            "mutation: removing singleTop from FullLoopProbeActivity must yield 0 matches, " +
                "proving the guard detects F-11 regressions",
            0, count,
        )
    }
}
