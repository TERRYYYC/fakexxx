package name.caiyao.fakegps.config

import name.caiyao.fakegps.data.model.FieldSpec
import name.caiyao.fakegps.data.model.FieldType
import name.caiyao.fakegps.verify.VerificationEngine
import name.caiyao.fakegps.verify.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam between two contracts that were developed in separate PRs.
 *
 *  - PR #4 owns publication success: [ConfigPublicationContract] — true only when the write is
 *    cross-process readable AND committed. Its durable-restore transaction depends on
 *    "false == cross-process publication failed" being a fact.
 *  - PR #3 owns the propagation window: a mismatch seen within the longest supported cadence is
 *    staleness, not failure, because the hook may still be sleeping on the previous timer.
 *
 * Composed, they must satisfy: **a failed publication can never soften a mismatch into "pending"**.
 * Otherwise a permanently broken transport (unreadable payload — commits fine, but the hook's UID
 * can never read it) would render as "配置刚保存，稍等一下", forever, instead of a visible failure.
 *
 * PR #23 review (Sol) note: the durable outcome is now written FAIL-CLOSED as a state machine —
 * pre-marked failed before the payload commit, flipped to success only after verification — so these
 * helpers route through the SAME transitions [ConfigPrefsSync] applies, and additionally pin the
 * interruption window (commit done, verification not yet) as a failure, not a stale success.
 */
class PublicationPendingSeamTest {

    private val specs = linkedMapOf(
        "c" to listOf(FieldSpec("tac", "TAC", "", FieldType.INTEGER)),
    )

    /**
     * Mirrors ConfigPrefsSync#sync as the real state machine: pre-mark fail-closed before the payload
     * commit, then flip to the verified outcome. [interruptedBeforeVerify] returns the durable state
     * at the window between payload commit and verification (a process death there).
     */
    private fun durableStateAfterSync(
        crossProcessReadable: Boolean,
        committed: Boolean,
        nowMs: Long,
        interruptedBeforeVerify: Boolean = false,
    ): ConfigPublicationContract.PublishState {
        val prior = ConfigPublicationContract.PublishState(
            publishedAtMs = 500L,
            publishFailed = false,
            activeProfileId = 3L,
        )
        val precommit = ConfigPublicationContract.preCommitFailClosed(prior)
        if (interruptedBeforeVerify) return precommit
        return if (ConfigPublicationContract.isCrossProcessPublishSuccessful(crossProcessReadable, committed)) {
            ConfigPublicationContract.onVerifiedPublish(nowMs, publishedProfileId = 3L)
        } else {
            ConfigPublicationContract.onVerifiedFailure(precommit)
        }
    }

    private fun timestampAfterSync(crossProcessReadable: Boolean, committed: Boolean, nowMs: Long): Long? =
        durableStateAfterSync(crossProcessReadable, committed, nowMs).publishedAtMs

    private fun statusFor(publishedAtMs: Long?, nowMs: Long): VerificationStatus =
        VerificationEngine.buildReport(
            configured = mapOf("tac" to "12345"),
            observed = mapOf("tac" to "26999"),   // the configured value did NOT take effect
            propagationPending = PublishPropagation.isPending(publishedAtMs, nowMs),
            specs = specs,
        ).summary.status

    @Test
    fun `cross-process-unreadable payload means a mismatch is a real failure, never pending`() {
        // Verification proved the committed file is not other-readable: the spoof can never reach the
        // hook, and the user must see that instead of a perpetual "刚保存，稍等".
        val ts = timestampAfterSync(crossProcessReadable = false, committed = true, nowMs = 1_000)
        assertFalse("a failed publication must not record a propagation timestamp", ts != null)
        assertEquals(VerificationStatus.FAILING, statusFor(ts, nowMs = 1_000))
    }

    @Test
    fun `a failed commit likewise cannot mask the mismatch`() {
        val ts = timestampAfterSync(crossProcessReadable = true, committed = false, nowMs = 1_000)
        assertEquals(VerificationStatus.FAILING, statusFor(ts, nowMs = 1_000))
    }

    @Test
    fun `an interruption after commit but before verification is a failure, not a stale success`() {
        // PR #23 review P1: a process death here must not leave a live published_at beside a payload
        // whose readability was never verified.
        val ts = durableStateAfterSync(
            crossProcessReadable = true, committed = true, nowMs = 1_000, interruptedBeforeVerify = true,
        ).publishedAtMs
        assertFalse("no live timestamp may survive an interruption before verify", ts != null)
        assertEquals(VerificationStatus.FAILING, statusFor(ts, nowMs = 1_000))
    }

    @Test
    fun `a successful publish does soften the mismatch inside the window`() {
        // The legitimate case this whole mechanism exists for: saved seconds ago, hook has not
        // re-read yet, so "未生效" would be a lie.
        val ts = timestampAfterSync(crossProcessReadable = true, committed = true, nowMs = 1_000)
        assertTrue(ts != null)
        assertEquals(VerificationStatus.PENDING_PROPAGATION, statusFor(ts, nowMs = 1_000))
    }

    @Test
    fun `a successful publish stops softening once the window elapses`() {
        val ts = timestampAfterSync(crossProcessReadable = true, committed = true, nowMs = 1_000)
        val afterWindow = 1_000L + PublishPropagation.MAX_PROPAGATION_DELAY_MS
        assertEquals(VerificationStatus.FAILING, statusFor(ts, afterWindow))
    }

    /**
     * Models what actually lands in the outcome store across successive publishes.
     *
     * [prior] is the timestamp already on disk. A failed publish must CLEAR it, not merely decline to
     * write a new one — the store is persistent, so "don't write" leaves the previous success's
     * timestamp behind and resurrects the false-red.
     */
    private fun storedTimestampAfter(prior: Long?, crossProcessReadable: Boolean, nowMs: Long): Long? {
        val priorState = ConfigPublicationContract.PublishState(
            publishedAtMs = prior,
            publishFailed = false,
            activeProfileId = 3L,
        )
        val precommit = ConfigPublicationContract.preCommitFailClosed(priorState)
        return if (crossProcessReadable) {
            ConfigPublicationContract.onVerifiedPublish(nowMs, publishedProfileId = 3L).publishedAtMs
        } else {
            ConfigPublicationContract.onVerifiedFailure(precommit).publishedAtMs
        }
    }

    @Test
    fun `a failed publish clears the timestamp left by a previous successful one`() {
        // review 4822122472 P1. Reachable sequence: publish succeeds at T, then 5s later the payload
        // lands unreadable. The NEW payload is unreadable cross-process, but the OLD timestamp is
        // still on disk and still inside its window — so the UI borrows it and reports
        // "配置刚保存，尚未生效" for a publication that can never succeed.
        val priorSuccess = 1_000L
        val stored = storedTimestampAfter(prior = priorSuccess, crossProcessReadable = false, nowMs = 5_000)

        assertEquals("a failed publish must clear the stale timestamp", null, stored)
        assertFalse(PublishPropagation.isPending(stored, nowMs = 5_000))
        assertEquals(VerificationStatus.FAILING, statusFor(stored, nowMs = 5_000))
    }

    @Test
    fun `back-to-back successful publishes keep refreshing the window`() {
        val stored = storedTimestampAfter(prior = 1_000L, crossProcessReadable = true, nowMs = 5_000)
        assertEquals(5_000L, stored)
        assertEquals(VerificationStatus.PENDING_PROPAGATION, statusFor(stored, nowMs = 5_000))
    }

    @Test
    fun `publication contract is exactly cross-process-readable AND committed`() {
        // Guards the assumption the seam is built on. If #4 ever widens this, the outcome state
        // machine in ConfigPrefsSync#sync must be revisited in the same change.
        assertTrue(ConfigPublicationContract.isCrossProcessPublishSuccessful(true, true))
        assertFalse(ConfigPublicationContract.isCrossProcessPublishSuccessful(false, true))
        assertFalse(ConfigPublicationContract.isCrossProcessPublishSuccessful(true, false))
        assertFalse(ConfigPublicationContract.isCrossProcessPublishSuccessful(false, false))
    }
}
