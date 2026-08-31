package name.caiyao.fakegps.integration.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthoritativeContinuityOracleTest {

    @Test
    fun `owner away then restore cannot alias the original stable sequence`() {
        val initial = completeState()
        val oracle = oracle(initial)
        val pre = oracle.snapshot()

        oracle.finishMutation(
            token = oracle.beginMutation(),
            outcome = AuthoritativeMutationOutcome.CHANGED,
            state = initial.copy(ownerUid = 20002, ownerPackage = "other.owner"),
        )
        oracle.finishMutation(
            token = oracle.beginMutation(),
            outcome = AuthoritativeMutationOutcome.CHANGED,
            state = initial,
        )

        val post = oracle.snapshot()
        assertEquals(pre.sequence + 4L, post.sequence)
        assertEquals(pre.ownerUid, post.ownerUid)
        assertEquals(pre.ownerPackage, post.ownerPackage)
        assertEquals(
            AuthoritativeWindowVerdict.MUTATING_OR_CHANGED,
            classifyAuthoritativeWindow(pre, post, QWY_PACKAGE, QWY_UID),
        )
    }

    @Test
    fun `provider disable then enable cannot alias the original stable sequence`() {
        val initial = completeState()
        val oracle = oracle(initial)
        val pre = oracle.snapshot()

        oracle.finishMutation(
            token = oracle.beginMutation(),
            outcome = AuthoritativeMutationOutcome.CHANGED,
            state = initial.copy(gpsProviderEnabled = false),
        )
        oracle.finishMutation(
            token = oracle.beginMutation(),
            outcome = AuthoritativeMutationOutcome.CHANGED,
            state = initial,
        )

        val post = oracle.snapshot()
        assertEquals(pre.sequence + 4L, post.sequence)
        assertTrue(post.gpsProviderEnabled)
        assertTrue(post.networkProviderEnabled)
        assertEquals(
            AuthoritativeWindowVerdict.MUTATING_OR_CHANGED,
            classifyAuthoritativeWindow(pre, post, QWY_PACKAGE, QWY_UID),
        )
    }

    @Test
    fun `coalesced coordinate away then restore cannot alias the original stable sequence`() {
        val initial = completeState().copy(qwySemanticDigest = "coordinate-a")
        val oracle = oracle(initial)
        val pre = oracle.snapshot()
        val away = oracle.beginMutation()
        val restore = oracle.beginMutation()

        oracle.finishMutation(
            token = away,
            outcome = AuthoritativeMutationOutcome.CHANGED,
            state = initial.copy(qwySemanticDigest = "coordinate-b"),
        )
        val post = oracle.finishMutation(
            token = restore,
            outcome = AuthoritativeMutationOutcome.CHANGED,
            state = initial,
        )

        assertEquals(pre.sequence + 2L, post.sequence)
        assertEquals(pre.qwySemanticDigest, post.qwySemanticDigest)
        assertEquals(
            AuthoritativeWindowVerdict.MUTATING_OR_CHANGED,
            classifyAuthoritativeWindow(pre, post, QWY_PACKAGE, QWY_UID),
        )
    }

    @Test
    fun `nested concurrent mutations expose one odd interval and publish one stable advance`() {
        val initial = completeState()
        val oracle = oracle(initial)
        val outer = oracle.beginMutation()
        val inner = oracle.beginMutation()

        val whileBothActive = oracle.snapshot()
        assertEquals(1L, whileBothActive.sequence)

        val afterInner = oracle.finishMutation(
            token = inner,
            outcome = AuthoritativeMutationOutcome.CHANGED,
            state = initial.copy(networkProviderEnabled = false),
        )
        assertEquals(1L, afterInner.sequence)

        val afterOuter = oracle.finishMutation(
            token = outer,
            outcome = AuthoritativeMutationOutcome.PROVED_NO_OP,
            state = initial.copy(networkProviderEnabled = false),
        )
        assertEquals(2L, afterOuter.sequence)
        assertFalse(afterOuter.networkProviderEnabled)
    }

    @Test
    fun `proved no-op restores the prior stable sequence`() {
        val initial = completeState()
        val oracle = oracle(initial)
        val before = oracle.snapshot()
        val token = oracle.beginMutation()

        assertEquals(before.sequence + 1L, oracle.snapshot().sequence)

        val after = oracle.finishMutation(
            token = token,
            outcome = AuthoritativeMutationOutcome.PROVED_NO_OP,
            state = initial,
        )
        assertEquals(before, after)
        assertEquals(
            AuthoritativeWindowVerdict.VALID,
            classifyAuthoritativeWindow(before, after, QWY_PACKAGE, QWY_UID),
        )
    }

    @Test
    fun `a claimed no-op with a changed endpoint advances and poisons health`() {
        val initial = completeState()
        val oracle = oracle(initial)

        val after = oracle.finishMutation(
            token = oracle.beginMutation(),
            outcome = AuthoritativeMutationOutcome.PROVED_NO_OP,
            state = initial.copy(qwySemanticDigest = "digest-b"),
        )

        assertEquals(2L, after.sequence)
        assertEquals(AuthoritativeOracleHealth.INVARIANT_FAILED, after.health)
        assertFalse(after.isStableCompleteFor(QWY_PACKAGE, QWY_UID))
    }

    @Test
    fun `an observation during mutation is never a valid proof window`() {
        val oracle = oracle(completeState())
        oracle.beginMutation()
        val odd = oracle.snapshot()

        assertFalse(odd.isStableCompleteFor(QWY_PACKAGE, QWY_UID))
        assertEquals(
            AuthoritativeWindowVerdict.MUTATING_OR_CHANGED,
            classifyAuthoritativeWindow(odd, odd, QWY_PACKAGE, QWY_UID),
        )
    }

    @Test
    fun `boot and same-boot instance changes have a dedicated fail-closed verdict`() {
        val stable = oracle(completeState()).snapshot()

        assertEquals(
            AuthoritativeWindowVerdict.BOOT_OR_INSTANCE_CHANGED,
            classifyAuthoritativeWindow(
                stable,
                stable.copy(bootId = "boot-b"),
                QWY_PACKAGE,
                QWY_UID,
            ),
        )
        assertEquals(
            AuthoritativeWindowVerdict.BOOT_OR_INSTANCE_CHANGED,
            classifyAuthoritativeWindow(
                stable,
                stable.copy(oracleInstanceId = "instance-b"),
                QWY_PACKAGE,
                QWY_UID,
            ),
        )
    }

    @Test
    fun `same-instance stable sequence regression is classified separately`() {
        val stable = oracle(completeState()).snapshot()
        val pre = stable.copy(sequence = 8L)
        val post = stable.copy(sequence = 6L)

        assertEquals(
            AuthoritativeWindowVerdict.SEQUENCE_REGRESSION,
            classifyAuthoritativeWindow(pre, post, QWY_PACKAGE, QWY_UID),
        )
    }

    @Test
    fun `missing mask health owner or provider prevents complete proof`() {
        val stable = oracle(completeState()).snapshot()
        val invalidSnapshots = listOf(
            stable.copy(installedCoverageMask = stable.installedCoverageMask xor AuthoritativeCoverageMask.LOCATION_EFFECTIVE_ENABLED),
            stable.copy(installedCoverageMask = stable.installedCoverageMask xor AuthoritativeCoverageMask.LOCATION_SEMANTIC_COORDINATE),
            stable.copy(installedCoverageMask = stable.installedCoverageMask or (1L shl 40)),
            stable.copy(requiredCoverageMask = stable.requiredCoverageMask or (1L shl 62)),
            stable.copy(health = AuthoritativeOracleHealth.HOOKS_INCOMPLETE),
            stable.copy(ownerUid = null),
            stable.copy(ownerPackage = null),
            stable.copy(ownerUid = QWY_UID + 1),
            stable.copy(ownerPackage = "other.owner"),
            stable.copy(gpsProviderEnabled = false),
            stable.copy(networkProviderEnabled = false),
            stable.copy(qwySemanticDigest = null),
            stable.copy(qwySemanticDigest = ""),
        )

        invalidSnapshots.forEach { invalid ->
            assertFalse(invalid.isStableCompleteFor(QWY_PACKAGE, QWY_UID))
            assertEquals(
                AuthoritativeWindowVerdict.UNHEALTHY,
                classifyAuthoritativeWindow(invalid, invalid, QWY_PACKAGE, QWY_UID),
            )
        }
    }

    @Test
    fun `identical complete snapshots form a valid proof window`() {
        val stable = oracle(
            completeState(lastCompletedQwyMutationId = "mutation-42"),
        ).snapshot()

        assertTrue(stable.isStableCompleteFor(QWY_PACKAGE, QWY_UID))
        assertEquals(
            AuthoritativeWindowVerdict.VALID,
            classifyAuthoritativeWindow(stable, stable.copy(), QWY_PACKAGE, QWY_UID),
        )
        assertEquals("mutation-42", stable.lastCompletedQwyMutationId)
    }

    @Test
    fun `semantic digest drift inside an otherwise identical window is a change`() {
        val pre = oracle(completeState()).snapshot()
        val post = pre.copy(qwySemanticDigest = "digest-b")

        assertEquals(
            AuthoritativeWindowVerdict.MUTATING_OR_CHANGED,
            classifyAuthoritativeWindow(pre, post, QWY_PACKAGE, QWY_UID),
        )
    }

    @Test
    fun `refresh reads without a semantic mutation leave sequence and snapshot unchanged`() {
        val oracle = oracle(completeState())
        val before = oracle.snapshot()

        repeat(100) {
            assertEquals(before, oracle.snapshot())
        }
        assertEquals(0L, oracle.snapshot().sequence)
    }

    @Test
    fun `uncertain mutation advances and cannot return healthy history`() {
        val oracle = oracle(completeState())

        val after = oracle.finishMutation(
            token = oracle.beginMutation(),
            outcome = AuthoritativeMutationOutcome.UNCERTAIN,
            state = completeState(),
        )

        assertEquals(2L, after.sequence)
        assertEquals(AuthoritativeOracleHealth.SESSION_UNCERTAIN, after.health)
        assertNotEquals(AuthoritativeOracleHealth.HEALTHY, after.health)
    }

    @Test
    fun `explicit session registration advances and clears only session uncertainty`() {
        val oracle = oracle(completeState())
        oracle.finishMutation(
            token = oracle.beginMutation(),
            outcome = AuthoritativeMutationOutcome.UNCERTAIN,
            state = completeState(),
        )

        val recovered = oracle.registerRecoveredSession(
            completeState().copy(qwySemanticDigest = "digest-generation-2"),
        )

        assertEquals(4L, recovered.sequence)
        assertEquals(AuthoritativeOracleHealth.HEALTHY, recovered.health)
        assertEquals("digest-generation-2", recovered.qwySemanticDigest)
    }

    private fun oracle(initial: AuthoritativeContinuityState) =
        AuthoritativeContinuityOracle(
            bootId = "boot-a",
            oracleInstanceId = "instance-a",
            initialState = initial,
        )

    private fun completeState(
        lastCompletedQwyMutationId: String? = null,
    ) = AuthoritativeContinuityState(
        ownerUid = QWY_UID,
        ownerPackage = QWY_PACKAGE,
        gpsProviderEnabled = true,
        networkProviderEnabled = true,
        installedCoverageMask = AuthoritativeCoverageMask.REQUIRED_V1,
        health = AuthoritativeOracleHealth.HEALTHY,
        qwySemanticDigest = "digest-a",
        lastCompletedQwyMutationId = lastCompletedQwyMutationId,
    )

    private companion object {
        const val QWY_UID = 10001
        const val QWY_PACKAGE = "name.caiyao.fakegps"
    }
}
