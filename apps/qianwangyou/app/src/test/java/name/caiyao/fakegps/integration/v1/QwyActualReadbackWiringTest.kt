package name.caiyao.fakegps.integration.v1

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM wiring guard for the Android-bound production controller.
 *
 * This module intentionally has no Robolectric dependency, so LocationManager
 * cannot be executed in the local unit lane. The behavior policy is exercised
 * in [SystemMockTrustPolicyTest]; this guard pins the remaining production
 * connection that previously replayed `lastApplied` as though it were an OS
 * observation.
 */
class QwyActualReadbackWiringTest {

    private val controllerSource: String by lazy {
        val relative =
            "src/main/java/name/caiyao/fakegps/integration/v1/QwyEnvironmentController.kt"
        sequenceOf(File(relative), File("app/$relative"))
            .firstOrNull(File::isFile)
            ?.readText()
            ?: error("cannot locate QwyEnvironmentController.kt")
    }

    @Test
    fun `apply receipt projects actual provider readback rather than desired coordinates`() {
        val body = methodBody("override fun applyEnvironment(")

        assertTrue(body.contains("systemMockTrustPolicy?.evaluate("))
        assertTrue(body.contains("effectiveLatitude = readback?.latitude"))
        assertTrue(body.contains("effectiveLongitude = readback?.longitude"))
        assertFalse(body.contains("effectiveLatitude = coords.first"))
        assertFalse(body.contains("effectiveLongitude = coords.second"))
    }

    @Test
    fun `observe cannot replay lastApplied desired coordinates as effective state`() {
        val body = methodBody("override fun observeEffective()")

        assertTrue(body.contains("systemMockTrustPolicy.evaluate("))
        assertTrue(body.contains("latitude = readback?.latitude"))
        assertTrue(body.contains("longitude = readback?.longitude"))
        assertFalse(Regex("""(?:lastApplied|appliedCommand)\.(?:latitude|longitude)""")
            .containsMatchIn(body))
        assertFalse(body.contains("ConfigPrefsSync.readPublished("))
    }

    private fun methodBody(anchor: String): String {
        val declaration = controllerSource.indexOf(anchor)
        assertTrue("declaration not found: $anchor", declaration >= 0)
        val openBrace = controllerSource.indexOf('{', declaration)
        assertTrue("body not found: $anchor", openBrace >= 0)
        var depth = 0
        for (index in openBrace until controllerSource.length) {
            when (controllerSource[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return controllerSource.substring(openBrace, index + 1)
                    }
                }
            }
        }
        error("unbalanced method body: $anchor")
    }
}
