package name.caiyao.fakegps.integration.v1

import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_PKG
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_SIGNER
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_UID
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.OTHER_PKG
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.OTHER_SIGNER
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.OTHER_UID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * SUPPLEMENTARY RED LANE — provider-side apply/release crash convergence and
 * idempotency-store scope (§6.3.4, §7.2 OperationReceipt, §8.4; INV-13/INV-28).
 *
 * NOT ledger rows and NOT counted as ledger coverage (same discipline as
 * AdvanceProviderRedTest: Opus5 allocates IDs in one batch — no self-assigned
 * IDs, and deleting this file would still leave the verifier green).
 *
 * Provenance: dsf advisory on af06993 — F-6 asked for write-fault crash cases
 * at ALL THREE mutation points (apply / release / advance); the advance point
 * landed with the F-2 closure (AdvanceProviderRedTest.advance_crashBetweenWrites),
 * these are the remaining two. F-9 asked for cross-caller idempotency-key
 * isolation. Both are contract-decoupled (they pin §7.2 store semantics and
 * §10.1 fault-injection charter already frozen at this branch's base), so they
 * are consumed BEFORE the #3 interface freeze; the contract-coupled findings
 * (F-4 ledger binding, F-7 §6.7.4b order flip, digest-mismatch case) stay
 * parked for the post-freeze rebase.
 *
 * Grounding for the convergence predicates:
 *  - §7.2 OperationReceipt: "无中间态" — same key + same digest replays the
 *    ORIGINAL receipt; the receipt row's whole reason to exist is that a crash
 *    retry is indistinguishable from a first attempt to the caller (INV-13).
 *  - §10 M-CR-01/02 expected terminals (Auto-side rows): "同键 apply，最多一个
 *    lease" / "同键返回原 receipt" — the provider-side halves below pin the
 *    same device truths from the owner's side.
 *  - INV-28: ANY non-RELEASED lease blocks a new apply, sole exception the
 *    same-caller same-key replay. A torn apply (lease committed, receipt not)
 *    would leave the device permanently wedged: the replay finds no receipt,
 *    re-executes, and collides with its own half-write — so lease + receipt
 *    must commit as one durable fact, exactly like §6.7.5's pointer+receipt.
 */
class ApplyReleaseProviderRedTest {

    private fun harness(): ProviderHarness {
        val h = ProviderHarness.create()
        h.pair(AUTO_PKG, AUTO_SIGNER)
        return h
    }

    // ------------------------------------------------------- crash: apply

    /**
     * dsf F-6 apply half: a write fault at the receipt write crashes apply
     * mid-flight. Post-restart the same-key replay must converge to EXACTLY
     * ONE usable lease (M-CR-01's device truth, provider side): either nothing
     * committed (clean re-execution) or everything did (stored receipt) —
     * never a lease without a retrievable receipt, which would wedge the
     * device behind INV-28 with no legal exit.
     */
    @Test
    fun apply_crashBetweenWrites_convergesToSingleUsableLease() {
        val h = harness()

        h.kv.failOnWrite = { namespace, _ ->
            namespace == DurableIdempotencyStore.RECEIPT_NAMESPACE
        }
        try {
            h.apply(key = "apl-crash")
            fail("injected write fault must surface, not be swallowed")
        } catch (expected: RuntimeException) {
            // SimulatedWriteCrash (or a transport wrapper) — NOT a typed
            // ContractException: a crash is not a business answer.
        }
        h.kv.failOnWrite = null

        h.restart(cleanlinessProvable = true)

        // Same-key replay converges to one lease...
        val replay = h.apply(key = "apl-crash")
        // ...is durable-stable (byte-identical receipt on re-replay)...
        val replay2 = h.apply(key = "apl-crash")
        assertEquals("stored receipt, not a second execution", replay, replay2)

        // ...and the lease it names is REAL: releasable, after which the
        // device accepts a fresh apply (not wedged behind a torn half-write).
        h.release(replay.leaseId, key = "apl-crash-rel")
        val next = h.apply(key = "apl-next", intent = h.intent(attemptId = "att-2"))
        assertNotEquals("fresh lease after convergence", replay.leaseId, next.leaseId)
    }

    // ----------------------------------------------------- crash: release

    /**
     * dsf F-6 release half: a write fault at the receipt write crashes release
     * mid-flight. Whatever the implementation committed before the fault
     * (nothing, or a RELEASING transition the §8.4 recovery table replays as
     * M-LS-17), the caller-visible terminal after restart is single-shaped:
     * the same-key replay returns a durable receipt, and the lease stops
     * blocking new applies — a release that neither completes nor frees the
     * device is the INV-21 false-green this row exists to kill.
     */
    @Test
    fun release_crashBetweenWrites_convergesToTerminalRelease() {
        val h = harness()
        val receipt = h.apply(key = "rel-crash-apply")

        h.kv.failOnWrite = { namespace, _ ->
            namespace == DurableIdempotencyStore.RECEIPT_NAMESPACE
        }
        try {
            h.release(receipt.leaseId, key = "rel-crash")
            fail("injected write fault must surface, not be swallowed")
        } catch (expected: RuntimeException) {
            // SimulatedWriteCrash (or a transport wrapper), see above.
        }
        h.kv.failOnWrite = null

        h.restart(cleanlinessProvable = true)

        // Same-key replay converges and is durable-stable.
        val replay = h.release(receipt.leaseId, key = "rel-crash")
        val replay2 = h.release(receipt.leaseId, key = "rel-crash")
        assertEquals("stored receipt, not a second release", replay, replay2)

        // Device converged: the released lease no longer blocks a new apply.
        val next = h.apply(key = "rel-next", intent = h.intent(attemptId = "att-2"))
        assertNotEquals(receipt.leaseId, next.leaseId)
    }

    // ------------------------------------------------- idempotency scope

    /**
     * dsf F-9: the §7.2 lookup scope is (caller, operation, idempotencyKey) —
     * the KEY is caller-local, not device-global. Two callers reusing the same
     * key string must not see each other's receipts. The mutant this kills: a
     * key-global store would answer caller B's DIFFERENT request under A's key
     * with IDEMPOTENCY_CONFLICT (same key, different digest — wire 12) or,
     * worse, replay A's receipt to B.
     */
    @Test
    fun idempotencyScope_sameKeyAcrossCallers_isIsolated() {
        val h = harness()
        h.pair(OTHER_PKG, OTHER_SIGNER)

        // Caller A earns and releases under key "shared-k".
        val a = h.apply(uid = AUTO_UID, key = "shared-k")
        h.release(a.leaseId, uid = AUTO_UID, key = "shared-k-rel")

        // Caller B reuses the SAME key string for a DIFFERENT request:
        // (B, APPLY, shared-k) finds nothing — fresh execution, fresh lease.
        val bIntent = h.intent(attemptId = "att-b")
        val b = h.apply(uid = OTHER_UID, key = "shared-k", intent = bIntent)
        assertNotEquals("B gets its own lease, not A's replay", a.leaseId, b.leaseId)
        assertEquals("both applies really executed", 2, h.env.applyCount)

        // B's replay under its own scope returns B's receipt — and only B's.
        val bReplay = h.apply(uid = OTHER_UID, key = "shared-k", intent = bIntent)
        assertEquals("per-caller replay returns the caller's own receipt", b, bReplay)
        assertEquals("replay executed nothing", 2, h.env.applyCount)
    }

    // ------------------------------------------------ durable codec totality

    /**
     * §6.3.4 idempotency keys are caller FREE strings, and the stored receipt
     * payload embeds them. Same fault class as the round-4 marker finding: a
     * separator-framed payload codec shifts every following field when the key
     * contains the separator, so the REPLAY (which deserializes the stored
     * payload) corrupts or crashes instead of returning the original receipt.
     * The codec must be total over all strings — "IDs are printable" is exactly
     * the assumption §6.7.3 removed.
     */
    @Test
    fun applyRelease_tabInIdempotencyKey_replayRoundTripsReceipt() {
        val h = harness()
        val hostileApplyKey = "k\t1:hostile"

        val first = h.apply(key = hostileApplyKey)
        val replay = h.apply(key = hostileApplyKey)
        assertEquals("stored apply receipt must round-trip byte-identically", first, replay)
        assertEquals("replay executed nothing", 1, h.env.applyCount)

        val hostileReleaseKey = "rk\t:x"
        val rel = h.release(first.leaseId, key = hostileReleaseKey)
        val relReplay = h.release(first.leaseId, key = hostileReleaseKey)
        assertEquals("stored release receipt must round-trip byte-identically", rel, relReplay)
        assertEquals("cleanup ran once", 1, h.env.cleanupCount)
    }
}
