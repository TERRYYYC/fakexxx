package com.example.cellrebelauto.cutover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CutoverBundleCodecTest {

    @Test
    fun `canonical bundle matches the checked-in golden carrier`() {
        assertEquals(GOLDEN, CutoverBundleCodecV1.encode(goldenBundle()))
        assertEquals(goldenBundle(), CutoverBundleCodecV1.decode(GOLDEN))
    }

    @Test
    fun `encoder rejects an incomplete Room v8 table census`() {
        val missingOne = goldenBundle().copy(
            tables = goldenBundle().tables - "advance_receipts"
        )

        assertThrows(IllegalArgumentException::class.java) {
            CutoverBundleCodecV1.encode(missingOne)
        }
    }

    @Test
    fun `encoder refuses to restore a pairing as active`() {
        val activePairing = goldenBundle().copy(
            pairingHistory = listOf(CutoverPairingHistory("sha256-legacy-pairing", active = true))
        )

        assertThrows(IllegalArgumentException::class.java) {
            CutoverBundleCodecV1.encode(activePairing)
        }
    }

    @Test
    fun `decoder rejects a carrier without the restore visibility fence`() {
        assertThrows(IllegalArgumentException::class.java) {
            CutoverBundleCodecV1.decode(GOLDEN.replace("visibilityFence=true", "visibilityFence=false"))
        }
    }

    @Test
    fun `decoder rejects a table outside the Room v8 census`() {
        assertThrows(IllegalArgumentException::class.java) {
            CutoverBundleCodecV1.decode(
                GOLDEN.replace(
                    "pairing=sha256-legacy-pairing|historical",
                    "table=unowned_table|0|schema-unowned|rows-unowned\npairing=sha256-legacy-pairing|historical"
                )
            )
        }
    }

    @Test
    fun `decoder rejects a changed Room column schema digest`() {
        assertThrows(IllegalArgumentException::class.java) {
            CutoverBundleCodecV1.decode(
                GOLDEN.replace(
                    "sha256:c9aa5b57968349255c54c2947f8b17c03451426da47cbb7f290b0840e16bb728",
                    "sha256:0000000000000000000000000000000000000000000000000000000000000000"
                )
            )
        }
    }

    private fun goldenBundle() = CutoverBundleV1(
        sourcePackage = "com.example.cellrebelauto",
        captureId = "capture-001",
        dataStoreDigest = "data-store-001",
        visibilityFenceRequired = true,
        eligibilityRecheckRequired = true,
        tables = CutoverSchemaV8.requiredTables.associateWith { table ->
            CutoverTableSnapshot(
                rowCount = 1,
                schemaDigest = CutoverSchemaV8.expectedSchemaDigests.getValue(table),
                rowDigest = "rows-$table"
            )
        },
        pairingHistory = listOf(CutoverPairingHistory("sha256-legacy-pairing", active = false))
    )

    private companion object {
        val GOLDEN = """
            cutover-bundle-v1
            source=com.example.cellrebelauto
            capture=capture-001
            roomVersion=8
            dataStoreDigest=data-store-001
            visibilityFence=true
            eligibilityRecheck=true
            table=advance_receipts|1|sha256:1a0606ab0bf6b76d2e7c647fd8adea78a8cdf904bb2a4a117b62151ffc4c59e3|rows-advance_receipts
            table=advance_replay_carriers|1|sha256:9d7c5bfb6b995c08ff81c0d6c7735a6e1e8388c1361fe2052b626b9b1da08133|rows-advance_replay_carriers
            table=auto_audit_events|1|sha256:18bc51da6dddd702fcccc44d2e41eb4c36bbba4ae504905aa5a03211f7c407ef|rows-auto_audit_events
            table=cellrebel_executions|1|sha256:8062b222a0646e5ecb78b5b1e33759cd96a0deed506de8057413b5cb7cd47dec|rows-cellrebel_executions
            table=durable_completion_receipts|1|sha256:39e2c849f880d3a5139106f26fe78b8ae42646eba53a3fae96fe7a048da9e77d|rows-durable_completion_receipts
            table=durable_observation_records|1|sha256:52a4464083dc673b57803ed83667c692bd28fedefd09034f1a873917ce91d741|rows-durable_observation_records
            table=legacy_completion_snapshots|1|sha256:b3bc917d5a038c57cb2f3f155ecdcebf6a6777ae1130f7dbf7002aec911ec1ef|rows-legacy_completion_snapshots
            table=location_plans|1|sha256:48df8fa192f0ef62548e0a0a467cb9cf51173a416bb3872d4d8e1a96e623e322|rows-location_plans
            table=location_tasks|1|sha256:c9aa5b57968349255c54c2947f8b17c03451426da47cbb7f290b0840e16bb728|rows-location_tasks
            table=operation_receipts|1|sha256:d47ecd9a2173ae120496309fc2fa0674ca5a61e7935b8d8436debf02bfe68a4b|rows-operation_receipts
            table=provider_pairing_records|1|sha256:08fa0ff5429dcd9b90f8bb011713fc923dd0fcaca6b7766ab4d0221d62d4bbf9|rows-provider_pairing_records
            table=recovery_checkpoints|1|sha256:1be9e69611d8fedd3b0f3bdf228f8d8ac98ed24a84873758f16b34a7ce88d0b1|rows-recovery_checkpoints
            table=release_receipts|1|sha256:51f387be95fb1025f8dc8d1abcc57437781fad6b548c3dff09ceaf31a8ec9edf|rows-release_receipts
            table=run_sessions|1|sha256:3ecd3405c92976857fb16526521e2ca1c89aba8b9957345e208fca51499a3b39|rows-run_sessions
            table=test_attempts|1|sha256:bc8ffe71f14f7408f65c8e8d9ba6df4d6ba019a8e00e8ffa68e530bbadc91833|rows-test_attempts
            table=test_results|1|sha256:d30a1a2f1355b094e6ea085197994f9042ad6acc87b472c5d2f9bec333227270|rows-test_results
            table=trusted_quota_entries|1|sha256:4dea0097bf13143fa202e331d3c9e02f506545365f4176e4ec7cbbf656bcb353|rows-trusted_quota_entries
            table=unverified_attempt_records|1|sha256:17e5d3d5240cd04a9e8bd67d92f2e4420cd5029c82b30aa102a5f9ced9431156|rows-unverified_attempt_records
            pairing=sha256-legacy-pairing|historical
        """.trimIndent()
    }
}
