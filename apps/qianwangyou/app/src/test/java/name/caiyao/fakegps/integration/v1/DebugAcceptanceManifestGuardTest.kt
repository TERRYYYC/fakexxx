package name.caiyao.fakegps.integration.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/**
 * Guard for the qwy DEBUG manifest's coexisting surfaces.
 *
 * WHY: ff59c27 REPLACED src/debug/AndroidManifest.xml wholesale to add the
 * pairing-approval surface, silently deleting the acceptance harness that was
 * already there: the RUN_HOOK_ACCEPTANCE signature permission, the
 * HookAcceptanceApplication application class, and both acceptance activities.
 * No git conflict, no test failure — three load-bearing debug surfaces gone
 * (Sol main-control mismatch ruling on the #7 handoff). This is the second
 * wholesale-replace-shared-config fault in this repo's history, so it gets a
 * guard: BOTH surface families must be declared, additions may not evict.
 *
 * STRUCTURAL ASSERTION, NOT STRING SEARCH
 * ----------------------------------------
 * An earlier version used `manifest.contains(needle)`, which passes when a
 * declaration is commented out in XML — the string is still present but the
 * element node is gone. This version parses the manifest as XML and uses XPath
 * to count real element nodes, so a "present but not effective" declaration is
 * correctly caught.
 *
 * Deliberately NOT a §10 ledger row — this guards build configuration, not
 * contract semantics.
 */
class DebugAcceptanceManifestGuardTest {

    private val moduleRoot: File = sequenceOf(File("."), File("app"), File("../app"))
        .map { it.absoluteFile.normalize() }
        .firstOrNull { File(it, "src/debug/AndroidManifest.xml").isFile }
        ?: error("cannot locate the app module root from ${File(".").absolutePath}")

    private val manifestFile: File = File(moduleRoot, "src/debug/AndroidManifest.xml")
    private val manifest: String = manifestFile.readText()

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

    /**
     * Assert that an XML element node exists (structurally, not as a string).
     * An element inside an XML comment, or whose attribute value changed, is
     * correctly reported as absent.
     */
    private fun mustHaveNode(xpathExpr: String, why: String) {
        val count = countNodes(xpathExpr)
        assertTrue(
            "debug manifest: expected ≥1 node for '$xpathExpr' but found $count — $why",
            count >= 1,
        )
    }

    /** Acceptance harness surface (pre-existing; deleted by ff59c27, restored after). */
    @Test
    fun acceptanceHarnessSurfaceIsDeclared() {
        // Signature permission
        mustHaveNode(
            "//permission[contains(@*[local-name()='name'], '.permission.RUN_HOOK_ACCEPTANCE')]",
            "the signature-level permission gating the hook acceptance probe",
        )
        // Application class
        mustHaveNode(
            "//application[contains(@*[local-name()='name'], '.probe.HookAcceptanceApplication')]",
            "the debug Application class the acceptance probes bootstrap through",
        )
        // Hook acceptance activity
        mustHaveNode(
            "//activity[contains(@*[local-name()='name'], '.probe.HookAcceptanceActivity')]",
            "the hook acceptance entry activity",
        )
        // Mock provider acceptance activity
        mustHaveNode(
            "//activity[contains(@*[local-name()='name'], '.mockprovider.MockProviderAcceptanceActivity')]",
            "the shell-only seam that seeds isolated .bench data and guarantees trap cleanup",
        )
    }

    /** Pairing approval surface (added by ff59c27; must coexist, not evict). */
    @Test
    fun pairingApprovalSurfaceIsDeclared() {
        mustHaveNode(
            "//activity[contains(@*[local-name()='name'], 'PairingApprovalActivity')]",
            "the §6.5 operator pairing approval entry — without it approve() has no call site",
        )
    }

    /**
     * Mutation self-check: wrapping a declaration in an XML comment must make the
     * structural assertion fail. This kills the old string-based guard's false green.
     *
     * Technique: take the real manifest XML, comment out PairingApprovalActivity's
     * element, re-parse, and verify the XPath returns 0 nodes — proving that a
     * "commented-out but string-present" state is caught by the structural check.
     */
    @Test
    fun commentedOutDeclarationIsDetected() {
        // Build a mutated manifest where PairingApprovalActivity is inside a comment.
        val activityTag = """<activity
            android:name="name.caiyao.fakegps.integration.v1.PairingApprovalActivity"
            android:exported="true"
            android:label="EC v1 pairing approval" />"""
        // The real manifest must contain this (as a substring — baseline check).
        assertTrue(
            "baseline: PairingApprovalActivity should be in the manifest source",
            manifest.contains("PairingApprovalActivity"),
        )

        // Wrap the activity element in a comment to simulate a structural deletion
        // that preserves the string.
        val mutated = manifest.replace(activityTag, "<!-- $activityTag -->")
        // Verify the string is still present (the old guard's blind spot).
        assertTrue(
            "mutation: PairingApprovalActivity string should still be present in commented XML",
            mutated.contains("PairingApprovalActivity"),
        )

        // Parse the mutated XML and verify the structural assertion catches it.
        val mutatedDoc = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(mutated.byteInputStream())
        val mutatedXpath = XPathFactory.newInstance().newXPath()
        val count = (mutatedXpath.evaluate(
            "//activity[contains(@*[local-name()='name'], 'PairingApprovalActivity')]",
            mutatedDoc, XPathConstants.NODESET
        ) as org.w3c.dom.NodeList).length

        assertEquals(
            "mutation: commented-out PairingApprovalActivity should yield 0 structural nodes, " +
                "proving the guard detects 'string present but declaration dead'",
            0, count,
        )
    }
}
