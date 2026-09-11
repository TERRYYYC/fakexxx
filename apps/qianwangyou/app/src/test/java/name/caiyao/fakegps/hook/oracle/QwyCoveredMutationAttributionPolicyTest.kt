package name.caiyao.fakegps.hook.oracle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #170 direction A: the attribution tag leg is demoted from a required match to a
 * NON-CONTRADICTING CONFIRMATION. Real-device evidence (mi14, two rounds): the app-side
 * attribution tag never reaches the system_server provenance on HyperOS's LocationManager
 * binder path — not even with #171's createAttributionContext patch installed — so the
 * QWY app's own nested platform calls (contract gateway, 1Hz refresh) arrived with a null
 * tag, were judged foreign, cleared lastCompletedQwyMutationId, and dead-ended the #167
 * ack chain. Within an ACTIVE bracket window the same uid/pid/package triple can only
 * belong to the QWY contract gateway or the app's own refresh tick (external apps cannot
 * share the triple, and QWY runs no other LM path during a bracket), so the triple is the
 * trust boundary and the tag only needs to not contradict it.
 */
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
    fun `own untagged call inside an active bracket is attributed - the mi14 #170 shape`() {
        // HyperOS does not propagate the app-side attribution tag into the system_server
        // provenance: the app's own bracketed platform calls arrive with a null tag.
        assertTrue(attributed(attributionTag = null))
        assertTrue(attributed(attributionTag = ""))
    }

    @Test
    fun `non-empty mismatching attribution tag is foreign - strong discrimination retained`() {
        // A non-null, non-matching tag proves a different attribution context: it must
        // still fail attribution even when the triple matches.
        assertFalse(attributed(attributionTag = "ordinary-location-call"))
        assertFalse(attributed(attributionTag = "qwy_authoritative_continuity "))
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
