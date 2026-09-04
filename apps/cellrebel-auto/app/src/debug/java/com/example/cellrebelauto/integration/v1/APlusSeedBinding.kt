package com.example.cellrebelauto.integration.v1

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/**
 * PR #62 merge-gate P1 (codex inline 3898022696, re-verified by Sol at faf561d): bind `start_run`
 * to the EXACT latest `seed_plan` invocation — not to "any plan whose topology matches".
 *
 * `seed_plan` inserts a NEW plan on every call (PlanDao.insertPlanWithTasks is a bare @Insert) and
 * never deletes earlier FX-G2-10A plans. Every one of them satisfies verifyPlanTopology, so a stale
 * planId replayed from an older seed report — whose tasks may already carry statuses, attempts and
 * trusted-quota rows — would start mid-plan while the surface reports the run as plan-bound. That is
 * a harness false green, independent of #79 (ordered-readback INCOMPLETE) and #80 (non-atomic start).
 *
 * Carrier: a debug-only, app-private SharedPreferences record written by seed_plan AFTER the seed
 * is proven complete (seedReport throws on a partial seed), holding
 * {planId, seedToken, fixtureDigest, generation, seededAtElapsedMs}. Each seed_plan overwrites it
 * (generation++). start_run must present BOTH the plan_id AND the seed_token printed by the latest
 * seed report; anything else fails closed:
 *   - no record (fresh install / `pm clear` / seed never verified)  → refuse ("no verified seed recorded")
 *   - plan_id != latest.planId (stale or foreign id)                → refuse ("is not the latest seed")
 *   - seed_token != latest.token (replayed older report, a guess)   → refuse ("seed_token does not match")
 * Topology (structure) is still verified separately; this binds IDENTITY.
 *
 * Debug source set only — zero src/main. `pm clear` wipes this record together with the DB, which is
 * the correct fail-closed state (the seeded plan is gone too).
 */
object APlusSeedBinding {
    const val PREFS_NAME = "aplus_seed_binding_debug"
    const val KEY_PLAN_ID = "latest_seed_plan_id"
    const val KEY_TOKEN = "latest_seed_token"
    const val KEY_DIGEST = "latest_seed_fixture_digest"
    const val KEY_GENERATION = "latest_seed_generation"
    const val KEY_SEEDED_AT_ELAPSED_MS = "latest_seed_seeded_at_elapsed_ms"

    /** Intent extra start_run must carry: `--es seed_token <token from the latest seed_plan report>`. */
    const val EXTRA_SEED_TOKEN = "seed_token"

    data class LatestSeed(
        val planId: Long,
        val token: String,
        val fixtureDigest: String,
        val generation: Long,
        val seededAtElapsedMs: Long,
    )

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 128-bit random token, lowercase hex; one per seed_plan invocation. */
    fun newToken(random: SecureRandom = SecureRandom()): String =
        ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /**
     * Overwrite the latest-seed record (generation = previous + 1). Synchronous commit: the token is
     * printed only after the record is durable, so a printed token is always startable exactly once
     * per seed generation.
     */
    fun record(
        prefs: SharedPreferences,
        planId: Long,
        token: String,
        fixtureDigest: String,
        seededAtElapsedMs: Long,
    ): LatestSeed {
        val generation = prefs.getLong(KEY_GENERATION, 0L) + 1
        val committed = prefs.edit()
            .putLong(KEY_PLAN_ID, planId)
            .putString(KEY_TOKEN, token)
            .putString(KEY_DIGEST, fixtureDigest)
            .putLong(KEY_GENERATION, generation)
            .putLong(KEY_SEEDED_AT_ELAPSED_MS, seededAtElapsedMs)
            .commit()
        check(committed) { "latest-seed record commit failed — refusing to print a token that is not durable" }
        return LatestSeed(planId, token, fixtureDigest, generation, seededAtElapsedMs)
    }

    fun latest(prefs: SharedPreferences): LatestSeed? {
        if (!prefs.contains(KEY_PLAN_ID) || !prefs.contains(KEY_TOKEN)) return null
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        return LatestSeed(
            planId = prefs.getLong(KEY_PLAN_ID, -1L),
            token = token,
            fixtureDigest = prefs.getString(KEY_DIGEST, "") ?: "",
            generation = prefs.getLong(KEY_GENERATION, 0L),
            seededAtElapsedMs = prefs.getLong(KEY_SEEDED_AT_ELAPSED_MS, 0L),
        )
    }

    /** null iff (planId, token) is EXACTLY the latest verified seed; otherwise a human-readable refusal. */
    fun verifyLatestSeed(prefs: SharedPreferences, planId: Long, token: String?): String? {
        val latest = latest(prefs)
            ?: return "no verified seed recorded on this install — run cmd=seed_plan first " +
                "(a plan that merely exists in the DB is not a seed)"
        if (token.isNullOrEmpty()) {
            return "start_run needs --es $EXTRA_SEED_TOKEN <token from the latest seed_plan report> " +
                "(latest generation=${latest.generation} plan=${latest.planId})"
        }
        if (planId != latest.planId) {
            return "plan $planId is not the latest seed (latest generation=${latest.generation} " +
                "plan=${latest.planId}) — stale or foreign plan ids are refused even when their topology matches"
        }
        if (!constantTimeEquals(token, latest.token)) {
            return "seed_token does not match the latest seed (generation=${latest.generation} " +
                "plan=${latest.planId}) — a replayed or guessed token is refused"
        }
        return null
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
