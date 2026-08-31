package com.example.cellrebelauto.automation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.integration.v1.EnvironmentControlClient
import com.example.cellrebelauto.recovery.BinderExternalApplyExecutor
import io.github.terryyyc.fakexxx.contract.v1.ContractV1
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * RED (G2 provider principal routing).
 *
 * Device truth 2026-08-30: the engine DID start (run_sessions 0->1) and then paused before the
 * first attempt with `provider discover failed or protocol incompatible (v1 required)` — three
 * times, deterministically. Root cause: the §6.5.3 trust principal and the Binder ComponentName
 * are two INDEPENDENT values that each default to PRODUCTION, while every active pairing on the
 * device lives on `.bench` (the production pairing is REVOKED). Accepted G2 scope is PROD=G3,
 * i.e. bench.
 *
 * Sol's specified invariant: debug/acceptance selects bench on BOTH legs, release selects
 * production on BOTH legs, and the two legs cannot fork. A half-fix that trusts bench while
 * still binding production reproduces the exact observed failure, so the fork is made
 * STRUCTURALLY impossible here rather than left as a wiring convention.
 *
 * # provider principal 单一选择：信任腿与 Binder 腿同源，且不可分叉
 */
@RunWith(RobolectricTestRunner::class)
class ProviderPrincipalRoutingRedTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `selection is one pure function of build type`() {
        assertEquals(
            "debug/acceptance must select the bench provider",
            ContractV1.PROVIDER_APPLICATION_ID_BENCH,
            ProviderPrincipal.resolve(isDebugBuild = true)
        )
        assertEquals(
            "release must keep selecting the production provider",
            ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION,
            ProviderPrincipal.resolve(isDebugBuild = false)
        )
        assertEquals(
            "the compiled variant must consume the same build-type selection",
            ProviderPrincipal.resolve(ProviderPrincipalBuild.isDebugBuild),
            ProviderPrincipal.selected
        )
    }

    @Test
    fun `the Binder leg carries no independent default`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        assertEquals(
            "BinderExternalApplyExecutor must take its target from the single selection",
            ProviderPrincipal.selected,
            BinderExternalApplyExecutor(app).targetApplicationId
        )
    }

    @Test
    fun `diagnostic client cannot fall back to a different provider identity`() {
        assertEquals(
            "probe routing must consume the same single principal as the engine",
            listOf(ProviderPrincipal.selected),
            EnvironmentControlClient.PROVIDER_PACKAGES
        )
    }

    @Test
    fun `trust principal and Binder target cannot fork`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val forked = BinderExternalApplyExecutor(
            app,
            providerApplicationId = ContractV1.PROVIDER_APPLICATION_ID_BENCH
        )
        try {
            APlusComposition.productionBackend(
                app,
                db,
                providerApplicationId = ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION,
                attemptValidityTimeoutMs = 90_000L,
                serviceLifecycleExecutor = forked
            )
            fail(
                "composition must fail closed when the trust principal and the Binder target " +
                    "disagree — either direction recreates the split-principal failure, and a " +
                    "green here would let a half-fix ship"
            )
        } catch (expected: IllegalArgumentException) {
            // structural rejection is the contract
        }
    }
}
