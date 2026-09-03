package name.caiyao.fakegps.integration.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemMockFormatterDiagnosticsTest {
    @Test
    fun `formatter exposes classified raw metadata and not coordinates`() {
        val diagnostic = SystemMockEvaluationDiagnostics(
            sources = listOf(
                SystemMockSourceEvaluationDiagnostic(
                    SystemMockSourceReadDiagnostic(
                        "gps", SystemMockSourceReadStatus.SAMPLE,
                        providerEnabled = false, isMock = true, sourceElapsedRealtimeMs = 99L,
                    ),
                    SystemMockSampleFreshness.BEFORE_PUBLISH,
                ),
                SystemMockSourceEvaluationDiagnostic(
                    SystemMockSourceReadDiagnostic(
                        "network", SystemMockSourceReadStatus.CACHE_QUERY_FAILED,
                        providerEnabled = true, failure = SystemMockReadFailure.SECURITY,
                    ),
                    SystemMockSampleFreshness.UNASSESSED,
                ),
            ),
            publishAnchorElapsedRealtimeMs = 100L,
        )

        val lines = SystemMockDiagnosticFormatter.lines(diagnostic)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("source=gps status=SAMPLE enabled=false mock=true source_elapsed_ms=99"))
        assertTrue(lines[0].contains("publish_anchor_ms=100 freshness=BEFORE_PUBLISH"))
        assertTrue(lines[1].contains("status=CACHE_QUERY_FAILED"))
        assertTrue(lines[1].contains("failure=SECURITY"))
        assertFalse(lines.joinToString().contains("latitude"))
        assertFalse(lines.joinToString().contains("longitude"))
    }

    @Test
    fun `untrusted provider text cannot smuggle coordinates or newlines into logs`() {
        val diagnostic = SystemMockEvaluationDiagnostics(
            listOf(
                SystemMockSourceEvaluationDiagnostic(
                    SystemMockSourceReadDiagnostic(
                        "gps\nLocation[lat=31.2304 lon=121.4737] ${31.2304.toBits()}",
                        SystemMockSourceReadStatus.UNREPORTED,
                    ),
                    SystemMockSampleFreshness.UNASSESSED,
                ),
            ),
            100L,
            SystemMockReadFailure.OTHER,
        )
        val line = SystemMockDiagnosticFormatter.lines(diagnostic).single()
        assertTrue(line.contains("source=other"))
        assertTrue(line.contains("reader_failure=OTHER"))
        listOf("\n", "Location[", "31.2304", "121.4737", "${31.2304.toBits()}").forEach {
            assertFalse("untrusted text leaked: $it", line.contains(it))
        }
    }
}
