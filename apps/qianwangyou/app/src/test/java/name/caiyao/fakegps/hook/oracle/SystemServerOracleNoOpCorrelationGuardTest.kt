package name.caiyao.fakegps.hook.oracle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SystemServerOracleNoOpCorrelationGuardTest {

    @Test
    fun `proved no-op preserves the previously published mutation correlation`() {
        val finishBody = braceDelimitedBlock(
            source = binderSource,
            anchor = "private void finishMutationLocked(",
        )
        val changedBranch = braceDelimitedBlock(
            source = finishBody,
            anchor = "if (aggregateChanged) {",
        )
        val assignment = Regex("""lastCompletedQwyMutationId\s*=""")

        assertEquals(
            "the completed correlation has one publication site",
            1,
            assignment.findAll(finishBody).count(),
        )
        assertTrue(
            "correlation may be replaced only when the stable sequence advances",
            assignment.containsMatchIn(changedBranch),
        )
        assertFalse(
            "the proved-no-op path must retain the prior correlation",
            assignment.containsMatchIn(finishBody.replace(changedBranch, "")),
        )
        assertTrue(
            "the same condition that gates correlation replacement must gate the +2 publish",
            finishBody.contains(
                "sequence = aggregateChanged ? outerBaseSequence + 2L : outerBaseSequence;",
            ),
        )
    }

    private fun braceDelimitedBlock(source: String, anchor: String): String {
        val declaration = source.indexOf(anchor)
        assertTrue("declaration not found: $anchor", declaration >= 0)
        val openBrace = source.indexOf('{', declaration)
        assertTrue("body not found: $anchor", openBrace >= 0)
        var depth = 0
        for (index in openBrace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(openBrace, index + 1)
                }
            }
        }
        error("unbalanced body: $anchor")
    }

    private val binderSource: String by lazy {
        val relative =
            "src/main/java/name/caiyao/fakegps/hook/oracle/SystemServerOracleBinder.java"
        sequenceOf(File(relative), File("app/$relative"))
            .firstOrNull(File::isFile)
            ?.readText()
            ?: error("cannot locate SystemServerOracleBinder.java")
    }
}
