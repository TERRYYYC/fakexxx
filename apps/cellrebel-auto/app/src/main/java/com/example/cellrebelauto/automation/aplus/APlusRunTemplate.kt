package com.example.cellrebelauto.automation.aplus

/**
 * The frozen A+ run template (§2.2 / §3.1).
 *
 * A+ is a SINGLE sealed template — there is no open extension point, no plugin DAG, and no scriptable
 * workflow (§2.3 non-goals). The only valid A+ template is [TRUSTED_SYSTEM_MOCK_BATCH_V1].
 *
 * The template calls a FIXED sequence of typed steps (§3.1):
 *   discover → preflight → apply → observe(pre) → CellRebel → observe(post) → decide → count → release
 *
 * Each attempt driven by this template walks the §8.1 state machine from CREATED to CLOSED along one
 * of the three conditional receipt/advance paths in [canonicalAttemptPaths]. The release receipt is
 * not a bare event: [CanonicalAttemptPath.releaseRoute] carries the authoritative quota predicate
 * that selects CLOSED vs ADVANCE_PENDING.
 *
 * PRE-FREEZE SKELETON: the template carries only frozen DATA (the step + event sequence), which is spec
 * ground truth (§2.2 "冻结" / §3.1 step list / §8.1 table) — it declares NO behavior. The RED lives in
 * [AttemptTransitions.next], which is a no-op skeleton: driving this frozen sequence through the real
 * state machine does not advance. GREEN implements the §8.1 table; the template itself stays pure data.
 * No GREEN body is added here.
 *
 * # 冻结 A+ 模板骨架（sealed，唯一实例 TRUSTED_SYSTEM_MOCK_BATCH_V1，纯数据无行为；RED 在 AttemptTransitions）
 */
sealed class APlusRunTemplate {

    /** The frozen §3.1 typed-step sequence (product-level boundaries; the order is not a plugin point). */
    abstract val typedSteps: List<APlusTypedStep>

    /** The frozen §8.1 conditional paths the state machine must honor for this template. */
    abstract val canonicalAttemptPaths: List<CanonicalAttemptPath>

    /**
     * TRUSTED_SYSTEM_MOCK_BATCH_V1 — the single frozen A+ template (§2.2). It accepts only
     * `SYSTEM_MOCK_INDEPENDENTLY_VERIFIED` completions (§2.2).
     */
    object TRUSTED_SYSTEM_MOCK_BATCH_V1 : APlusRunTemplate() {
        override val typedSteps: List<APlusTypedStep> = APlusTypedStep.entries
        override val canonicalAttemptPaths: List<CanonicalAttemptPath> = listOf(
            CANONICAL_UNDER_QUOTA_PATH,
            CANONICAL_QUOTA_REACHED_NON_TERMINAL_PATH,
            CANONICAL_QUOTA_REACHED_EXHAUSTED_PATH
        )
    }

    companion object {
        /**
         * Shared §8.1 prefix, frozen from the transition table (CREATED → RELEASE_PENDING):
         *   CREATED –BEGIN_APPLY→ APPLY_PENDING –APPLY_RECEIPT→ ENV_APPLIED –PRE_OBSERVATION_OK→
         *   PRE_OBSERVED –START_CELLREBEL→ CELLREBEL_START_PENDING –NEW_RUN_OBSERVED→ CELLREBEL_RUNNING
         *   –COMPLETION_OBSERVED→ POST_OBSERVE_PENDING –POST_OBSERVATION_OK→ DECIDING –TRUST_POLICY_PASS→
         *   QUOTA_COMMITTED –BEGIN_RELEASE→ RELEASE_PENDING.
         *
         * The conditional receipt route and any advance-verification suffix live in the named path
         * objects below; collapsing them into one `RELEASE_RECEIPT → CLOSED` path would erase §8.1's
         * quota-reached branch.
         */
        val CANONICAL_RELEASE_PREFIX: List<AttemptEvent> = listOf(
            AttemptEvent.BEGIN_APPLY,
            AttemptEvent.APPLY_RECEIPT,
            AttemptEvent.PRE_OBSERVATION_OK,
            AttemptEvent.START_CELLREBEL,
            AttemptEvent.NEW_RUN_OBSERVED,
            AttemptEvent.COMPLETION_OBSERVED,
            AttemptEvent.POST_OBSERVATION_OK,
            AttemptEvent.TRUST_POLICY_PASS,
            AttemptEvent.BEGIN_RELEASE
        )

        /** Committed but under-quota: the durable release receipt closes without advancing. */
        val CANONICAL_UNDER_QUOTA_PATH = CanonicalAttemptPath(
            releaseRoute = ReleaseReceiptRoute.COMMITTED_UNDER_QUOTA,
            eventSequence = CANONICAL_RELEASE_PREFIX + AttemptEvent.RELEASE_RECEIPT
        )

        /** Quota reached, non-terminal advance: verify receipt, then independently match four legs. */
        val CANONICAL_QUOTA_REACHED_NON_TERMINAL_PATH = CanonicalAttemptPath(
            releaseRoute = ReleaseReceiptRoute.COMMITTED_QUOTA_REACHED,
            eventSequence = CANONICAL_RELEASE_PREFIX + listOf(
                AttemptEvent.RELEASE_RECEIPT,
                AttemptEvent.ADVANCE_RECEIPT_VERIFIED,
                AttemptEvent.OBSERVED_TUPLE_MATCHES
            )
        )

        /** Quota reached, exhausted advance: verify receipt, then independently confirm readback. */
        val CANONICAL_QUOTA_REACHED_EXHAUSTED_PATH = CanonicalAttemptPath(
            releaseRoute = ReleaseReceiptRoute.COMMITTED_QUOTA_REACHED,
            eventSequence = CANONICAL_RELEASE_PREFIX + listOf(
                AttemptEvent.RELEASE_RECEIPT,
                AttemptEvent.ADVANCE_EXHAUSTED_VERIFIED,
                AttemptEvent.EXHAUSTED_STATE_CONFIRMED
            )
        )

        /**
         * Every A+ template instance — exactly one (§2.2/§3.1). The `sealed` modifier (compile-time)
         * guarantees no instance exists outside this file; this getter pins the known set at runtime.
         *
         * NB: a computed getter (not a backing field) so the nested-object reference resolves at access
         * time — capturing it in a `val` initializer would read the nested object's INSTANCE field during
         * companion `<clinit>`, before the object has initialized, yielding `[null]`.
         */
        val ALL: List<APlusRunTemplate> get() = listOf(TRUSTED_SYSTEM_MOCK_BATCH_V1)
    }
}

/** Pure-data description of one conditional §8.1 CREATED→CLOSED path. */
data class CanonicalAttemptPath(
    val releaseRoute: ReleaseReceiptRoute,
    val eventSequence: List<AttemptEvent>
)

/**
 * A named boundary in the frozen A+ typed-step sequence (§3.1). The declaration order of [entries]
 * IS the frozen sequence; it is not a plugin point (§2.3).
 *
 * # A+ 固定 typed step（§3.1 冻结顺序，非插件点）
 */
enum class APlusTypedStep {
    DISCOVER,
    PREFLIGHT,
    APPLY,
    OBSERVE_PRE,
    CELLREBEL,
    OBSERVE_POST,
    DECIDE,
    COUNT,
    RELEASE
}
