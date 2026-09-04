package com.example.cellrebelauto.environment

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #10 RED: ProviderTrustGate rejections must be TYPED and OBSERVABLE.
 *
 * Device truth: a mis-touch on the Provider page's revoke button made the gate silently
 * intercept every contract call — discover returned null and the engine log said only
 * "provider discover failed or protocol incompatible (v1 required)". The gate must record its
 * latest rejection (applicationId + signer + because) so logcat AND the engine pause message
 * can name the real cause ("signer not an approved active principal").
 *
 * Killing mutation: making record() a no-op / latestRejection() always null fails every test.
 *
 * # gate 拒绝记录 oracle：撤销后 discover=null 的真因必须可查（appId/signer/typed 原因）
 */
@RunWith(RobolectricTestRunner::class)
class ProviderTrustGateRejectionTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        ProviderTrustRejections.reset()
    }

    @After
    fun tearDown() {
        db.close()
        ProviderTrustRejections.reset()
    }

    @Test
    fun `a rotated-away signer is rejected AND recorded with the typed principal cause`() = runTest {
        val store = ProviderTrustStore(db.providerPairingDao())
        store.approve("name.caiyao.fakegps.bench", "sha256:approved", versionCode = 1, approvedAt = 1000L)
        val gate = ProviderTrustGate(store) { "sha256:rotated" }

        assertFalse(gate.isCurrentSignerTrusted("name.caiyao.fakegps.bench"))

        val rejection = ProviderTrustRejections.latestRejection()
        assertNotNull("the rejection must be recorded for logcat/engine diagnosis", rejection)
        assertEquals("name.caiyao.fakegps.bench", rejection!!.applicationId)
        assertEquals("sha256:rotated", rejection.signerDigest)
        assertTrue(
            "the recorded cause must be the typed principal sentence",
            rejection.because.contains("signer not an approved active principal"),
        )
    }

    @Test
    fun `an unresolvable signer is rejected AND recorded as unresolvable`() = runTest {
        val store = ProviderTrustStore(db.providerPairingDao())
        val gate = ProviderTrustGate(store) { null }

        assertFalse(gate.isCurrentSignerTrusted("name.caiyao.fakegps.bench"))

        val rejection = ProviderTrustRejections.latestRejection()
        assertNotNull(rejection)
        assertEquals("name.caiyao.fakegps.bench", rejection!!.applicationId)
        assertNull("no signer was resolved at all", rejection.signerDigest)
        assertTrue(rejection.because.contains("unresolvable"))
    }

    @Test
    fun `a trusted check records nothing`() = runTest {
        val store = ProviderTrustStore(db.providerPairingDao())
        store.approve("name.caiyao.fakegps.bench", "sha256:approved", versionCode = 1, approvedAt = 1000L)
        val gate = ProviderTrustGate(store) { "sha256:approved" }

        assertTrue(gate.isCurrentSignerTrusted("name.caiyao.fakegps.bench"))
        assertNull("a pass must not pollute the rejection record", ProviderTrustRejections.latestRejection())
    }
}
