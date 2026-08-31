package name.caiyao.fakegps.hook.oracle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QwyCoveredMutationAttributionPolicyTest {
    private val expectedUid = 10_301
    private val expectedPid = 4_201
    private val expectedPackage = "name.caiyao.fakegps"
    private val reservedTag = Android15OracleHookPlan.QWY_MUTATION_ATTRIBUTION_TAG

    @Test
    fun `exact live writer provenance retains QWY correlation`() {
        assertTrue(attributed())
    }

    @Test
    fun `same process call without reserved attribution is foreign`() {
        assertFalse(attributed(attributionTag = null))
        assertFalse(attributed(attributionTag = "ordinary-location-call"))
    }

    @Test
    fun `same uid from another process is foreign`() {
        assertFalse(attributed(callingPid = expectedPid + 1))
    }

    @Test
    fun `package mismatch is foreign`() {
        assertFalse(attributed(callingPackage = "$expectedPackage.remote"))
    }

    @Test
    fun `inactive session or missing parent token is foreign`() {
        assertFalse(attributed(qwySessionActive = false))
        assertFalse(attributed(qwyMutationActive = false))
    }

    private fun attributed(
        callingUid: Int = expectedUid,
        callingPid: Int = expectedPid,
        callingPackage: String? = expectedPackage,
        attributionTag: String? = reservedTag,
        qwySessionActive: Boolean = true,
        qwyMutationActive: Boolean = true,
    ): Boolean = QwyCoveredMutationAttributionPolicy.isAttributed(
        expectedUid,
        expectedPid,
        expectedPackage,
        callingUid,
        callingPid,
        callingPackage,
        attributionTag,
        qwySessionActive,
        qwyMutationActive,
    )
}
