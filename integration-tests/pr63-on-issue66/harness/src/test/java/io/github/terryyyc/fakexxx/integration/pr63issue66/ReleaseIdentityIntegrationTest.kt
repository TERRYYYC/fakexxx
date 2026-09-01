package io.github.terryyyc.fakexxx.integration.pr63issue66

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.recovery.RoomDurableRecoveryLog
import io.github.terryyyc.fakexxx.contract.v1.ContractV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReleaseIdentityIntegrationTest {

    @Test
    fun `release lease scope includes provider and signer while operation keys stay global`() =
        kotlinx.coroutines.test.runTest {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
            try {
                val logA = RoomDurableRecoveryLog(
                    db.operationReceiptDao(),
                    db.recoveryCheckpointRoomDao(),
                    db.releaseReceiptDao(),
                    SIGNER_A,
                )
                val logB = RoomDurableRecoveryLog(
                    db.operationReceiptDao(),
                    db.recoveryCheckpointRoomDao(),
                    db.releaseReceiptDao(),
                    SIGNER_B,
                )
                val sharedLease = "lease-valid-in-two-provider-namespaces"
                val digest = APlusOperationIdentity.releaseDigest(sharedLease)

                val production = logA.recordReleaseReceipt(
                    "global-release-key",
                    sharedLease,
                    digest,
                    "RELEASED",
                    1L,
                    PRODUCTION,
                )
                assertNotNull(production)
                assertEquals(
                    "exact tuple replay returns the canonical stored row",
                    production,
                    logA.recordReleaseReceipt(
                        "global-release-key",
                        sharedLease,
                        digest,
                        "RELEASED",
                        99L,
                        PRODUCTION,
                    ),
                )
                assertNull(
                    "the global key cannot be replayed by a different signer even with the same tuple",
                    logB.recordReleaseReceipt(
                        "global-release-key",
                        sharedLease,
                        digest,
                        "RELEASED",
                        2L,
                        PRODUCTION,
                    ),
                )
                assertNull(
                    "the global key cannot be replayed with a different release digest",
                    logA.recordReleaseReceipt(
                        "global-release-key",
                        sharedLease,
                        APlusOperationIdentity.releaseDigest("different-lease"),
                        "RELEASED",
                        2L,
                        PRODUCTION,
                    ),
                )

                assertNull(
                    "same (P,lease) cannot be claimed by another signer under a new key",
                    logB.recordReleaseReceipt(
                        "foreign-signer-key",
                        sharedLease,
                        digest,
                        "RELEASED",
                        2L,
                        PRODUCTION,
                    ),
                )
                assertNull(
                    "the same operation key cannot move to another provider",
                    logA.recordReleaseReceipt(
                        "global-release-key",
                        "other-lease",
                        APlusOperationIdentity.releaseDigest("other-lease"),
                        "RELEASED",
                        3L,
                        BENCH,
                    ),
                )

                val bench = logA.recordReleaseReceipt(
                    "bench-release-key",
                    sharedLease,
                    digest,
                    "RELEASED",
                    4L,
                    BENCH,
                )
                assertNotNull("another P may own the same lease id under a different key", bench)
                assertEquals(SIGNER_A, db.releaseReceiptDao().byKey("bench-release-key")?.providerSignerDigest)
                assertEquals(PRODUCTION, db.releaseReceiptDao().byKey("global-release-key")?.providerApplicationId)
                assertEquals(SIGNER_A, db.releaseReceiptDao().byKey("global-release-key")?.providerSignerDigest)
                assertEquals(1, db.releaseReceiptDao().allByLease(sharedLease, PRODUCTION).size)
                assertEquals(1, db.releaseReceiptDao().allByLease(sharedLease, BENCH).size)
                assertEquals(2, db.releaseReceiptDao().allByLease(sharedLease, PRODUCTION).size +
                    db.releaseReceiptDao().allByLease(sharedLease, BENCH).size)
            } finally {
                db.close()
            }
        }

    private companion object {
        const val BENCH = ContractV1.PROVIDER_APPLICATION_ID_BENCH
        const val PRODUCTION = ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION
        const val SIGNER_A =
            "sha256:7a598cbe6fb816ba74f01b58e3f43b8ff0f463989157e590ebd86c89b53f7e41"
        const val SIGNER_B =
            "sha256:3b20b06be2531a128426fcf6d873eb2ce27f086b7a0e6ef0f20586076e5f3cd3"
    }
}
