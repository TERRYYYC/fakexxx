package name.caiyao.fakegps.integration.v1

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins one process-wide lock order for every handler claim and mutation. */
class HandlerWriterLockOrderGuardTest {
    private val source: String by lazy {
        val relative =
            "src/main/java/name/caiyao/fakegps/integration/v1/EnvironmentControlHandler.kt"
        sequenceOf(File(relative), File("app/$relative"))
            .firstOrNull(File::isFile)
            ?.readText()
            ?: error("cannot locate EnvironmentControlHandler.kt")
    }

    @Test
    fun `all handler fences acquire writer selection before owner state`() {
        val selectedOwner = source.substringAfter("private fun <T> withSelectedOwnerLock(")
            .substringBefore("private fun <T> withOwnerFence(")
        val selectionAt = selectedOwner.indexOf("QwySemanticWriterRuntime.serializeSelection")
        val ownerAt = selectedOwner.indexOf("synchronized(ownerLock")
        assertTrue(selectionAt >= 0 && ownerAt > selectionAt)

        val fence = source.substringAfter("private fun <T> withOwnerFence(")
            .substringBefore("companion object")
        assertTrue(fence.contains("withSelectedOwnerLock"))
        assertTrue(fence.contains("settlePendingAdvance()"))

        val advanceDeclaration = source.substringAfter("fun completeAndAdvance(")
            .substringBefore("/**\n     * §6.7.5")
        assertTrue(advanceDeclaration.contains("): AdvanceReceiptV1 = withOwnerFence {"))
        assertFalse(
            "advance must not invert selection and owner with an extra outer wrapper",
            advanceDeclaration.substringBefore("withOwnerFence {")
                .contains("serializeSelection"),
        )

        val startup = source.substringAfter("fun onOwnerProcessStart(")
            .substringBefore("/**\n     * Operator revokes")
        assertTrue(startup.contains("): Unit = withSelectedOwnerLock {"))
        val reconcileAt = startup.indexOf("environment.reconcileProjectionOnOwnerStart()")
        val semanticDigestAt = startup.indexOf("environment.authoritativeSemanticDigest(")
        assertTrue("startup projection reconciliation is missing", reconcileAt >= 0)
        assertTrue(
            "cold projection must be reconciled before semantic registration",
            semanticDigestAt > reconcileAt,
        )
    }

    private fun methodBody(anchor: String): String {
        val declaration = source.indexOf(anchor)
        assertTrue("declaration not found: $anchor", declaration >= 0)
        val equals = source.indexOf('=', declaration)
        val opening = source.indexOf('{', equals)
        assertTrue("body not found: $anchor", opening >= 0)
        var depth = 0
        for (index in opening until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(opening, index + 1)
                }
            }
        }
        error("unbalanced body: $anchor")
    }
}
