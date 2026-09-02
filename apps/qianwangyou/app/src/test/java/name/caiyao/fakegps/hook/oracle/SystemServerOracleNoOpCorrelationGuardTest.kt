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

    @Test
    fun `one registered death token owns session and active mutation discontinuity`() {
        val source = binderSource

        assertTrue(
            "mutation begin must reject a token other than the registered session token",
            source.contains("qwySessionToken != clientDeathToken"),
        )
        assertEquals(
            "the registered session token needs exactly one death recipient",
            1,
            Regex("\\.linkToDeath\\(").findAll(source).count(),
        )
        assertFalse(
            "a second mutation death callback makes recipient ordering double-count death",
            source.contains("onQwyMutationDeath"),
        )
        val sessionDeath = braceDelimitedBlock(source, "private void onQwySessionDeath(")
        val bridgeDeath = braceDelimitedBlock(source, "void onBridgeDisconnected(")
        assertTrue(
            "session-token and bridge loss must share one generation-loss coalescer",
            sessionDeath.contains("markQwyGenerationLostLocked()") &&
                bridgeDeath.contains("markQwyGenerationLostLocked()"),
        )
        val coalescer = braceDelimitedBlock(source, "private void markQwyGenerationLostLocked()")
        assertTrue(coalescer.contains("if (qwyGenerationLossAccounted) return"))
        assertTrue(coalescer.contains("qwyGenerationLossAccounted = true"))
    }

    @Test
    fun `replacing a live session accounts its generation loss before registration`() {
        val registration = braceDelimitedBlock(binderSource, "public void registerQwySession(")
        val replacementGuard =
            "oldToken != null && oldToken != clientDeathToken"
        val guardedLoss = registration.indexOf("markQwyGenerationLostLocked()")
        val registrationBoundary = registration.indexOf("retireActiveQwyMutationsLocked()")

        assertTrue(
            "a distinct old token must be recognized as an unreported generation loss",
            registration.contains(replacementGuard),
        )
        assertTrue(
            "the old generation loss must be durably represented before registration's own boundary",
            guardedLoss >= 0 && registrationBoundary >= 0 && guardedLoss < registrationBoundary,
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
