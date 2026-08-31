package com.example.cellrebelauto.environment

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * R44 (Sol GREEN-review-3 F4): the provider-trust principal is the FROZEN pair
 * (applicationId, currentSignerDigest) (§6.5.4). The pre-fix schema put a single-column UNIQUE on
 * applicationId, which (a) blocked signer rotation coexistence, (b) made post-revocation
 * re-approval hit the UNIQUE constraint (M-PA-10 unexecutable), and (c) let callers authorize by
 * applicationId alone — never by the CURRENT signer.
 *
 * Killing mutations: reverting the index to applicationId-only UNIQUE throws on the rotation /
 * re-approval inserts; reverting the DAO to applicationId-only lookups authorizes the WRONG signer
 * (test 1) and revokes across principals (test 5).
 *
 * # 信任 principal 二元 key oracle：按当前 signer 精确授权；轮转并存；撤销后重批（M-PA-10）
 */
@RunWith(RobolectricTestRunner::class)
class ProviderTrustStorePrincipalTest {

    private val signerA = "sha256:70506b15b8a45e1147ade558c1869420b8cd4c65e9590647e09b5c816b58975c"
    private val signerB = "sha256:4412f6d72f33cc7f8e643f7624d4ec743f5389185fc952366da015a6ab6c8a63"
    private val oldSigner = "sha256:cba06b5736faf67e54b07b561eae94395e774c517a7d910a54369e1263ccfbd4"
    private val newSigner = "sha256:11507a0e2f5e69d5dfa40a62a1bd7b6ee57e6bcd85c67c9b8431b36fff21c437"

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `authorization is precise to the current signer - a different signer of the same app is NOT trusted`() = runTest {
        val store = ProviderTrustStore(db.providerPairingDao())
        store.approve("com.qwy.app", signerA, versionCode = 10, approvedAt = 1000L)
        assertNotNull(
            "the approved (app, signerA) principal must be active",
            store.findActive("com.qwy.app", signerA)
        )
        assertNull(
            "the same applicationId with a DIFFERENT current signer must NOT be trusted (§6.5.4)",
            store.findActive("com.qwy.app", signerB)
        )
    }

    @Test
    fun `signer rotation coexists - revoking the old principal never blocks the new one`() = runTest {
        val store = ProviderTrustStore(db.providerPairingDao())
        store.approve("com.qwy.app", oldSigner, 10, 1000L)
        store.revoke("com.qwy.app", oldSigner, 2000L)
        val rotated = store.approve("com.qwy.app", newSigner, 11, 3000L)
        assertNotNull("the rotated principal must be active", rotated)
        assertNull("the old signer stays revoked", store.findActive("com.qwy.app", oldSigner))
        assertNotNull(store.findActive("com.qwy.app", newSigner))
        assertEquals(
            "both principals survive as history rows (never a delete)",
            2, db.providerPairingDao().byApplicationId("com.qwy.app").size
        )
    }

    @Test
    fun `M-PA-10 re-approval after revocation appends a new row and re-activates the principal`() = runTest {
        val store = ProviderTrustStore(db.providerPairingDao())
        val first = store.approve("com.qwy.app", signerA, 10, 1000L)
        store.revoke("com.qwy.app", signerA, 2000L)
        assertNull(store.findActive("com.qwy.app", signerA))
        val reApproved = store.approve("com.qwy.app", signerA, 10, 3000L)
        assertNotNull("re-approval after revocation must succeed (M-PA-10)", reApproved)
        assertTrue("the re-approval is a NEW row, never a resurrection", reApproved.id != first.id)
        assertNotNull(store.findActive("com.qwy.app", signerA))
        assertEquals(
            "the revoked row is preserved as history",
            2, db.providerPairingDao().byApplicationId("com.qwy.app").size
        )
        assertEquals(
            "exactly one row is active (the new one)",
            1, db.providerPairingDao().byApplicationId("com.qwy.app").count { it.revokedAt == null }
        )
    }

    @Test
    fun `approving an already-active principal is idempotent - never a duplicate row`() = runTest {
        val store = ProviderTrustStore(db.providerPairingDao())
        val first = store.approve("com.qwy.app", signerA, 10, 1000L)
        val second = store.approve("com.qwy.app", signerA, 10, 2000L)
        assertEquals("re-approving an ACTIVE principal returns the same record", first.id, second.id)
        assertEquals(1, db.providerPairingDao().count())
    }

    @Test
    fun `revoke is scoped to the exact principal - a coexisting signer row is untouched`() = runTest {
        val store = ProviderTrustStore(db.providerPairingDao())
        store.approve("com.qwy.app", signerA, 10, 1000L)
        store.approve("com.qwy.app", signerB, 10, 1500L)
        assertTrue(store.revoke("com.qwy.app", signerA, 2000L))
        assertNull(store.findActive("com.qwy.app", signerA))
        assertNotNull(
            "the other principal's active row must survive a scoped revoke",
            store.findActive("com.qwy.app", signerB)
        )
    }

    @Test
    fun `signer digests normalize before durable trust lookup`() = runTest {
        val store = ProviderTrustStore(db.providerPairingDao())
        val nonCanonical = "  ${signerA.uppercase()}  "

        val approved = store.approve("com.qwy.app", nonCanonical, 10, 1000L)

        assertEquals(signerA, approved.currentSignerDigest)
        assertNotNull(store.findActive("com.qwy.app", nonCanonical))
        assertTrue(store.revoke("com.qwy.app", nonCanonical, 2000L))
        assertNull(store.findActive("com.qwy.app", signerA))
    }

    @Test
    fun `malformed signer digests fail closed`() = runTest {
        val store = ProviderTrustStore(db.providerPairingDao())

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                store.approve("com.qwy.app", "sha256:not-a-certificate-digest", 10, 1000L)
            }
        }
        assertNull(store.findActive("com.qwy.app", "sha256:not-a-certificate-digest"))
        assertEquals(false, store.revoke("com.qwy.app", "sha256:not-a-certificate-digest", 2000L))
        assertEquals(0, db.providerPairingDao().count())
    }
}
