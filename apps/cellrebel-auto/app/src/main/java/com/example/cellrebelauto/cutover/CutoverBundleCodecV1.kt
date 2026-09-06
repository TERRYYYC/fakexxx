package com.example.cellrebelauto.cutover

/**
 * The complete Room v8 entity census expected by a future #13 snapshot producer.
 *
 * This is deliberately a manifest, not a database exporter: Auto owns the eventual
 * transactionally-consistent Room + DataStore snapshot seam. Keeping the census here lets the
 * SAF carrier reject an incomplete or stale producer before it has an import surface.
 */
object CutoverSchemaV8 {
    const val ROOM_VERSION = 8

    /** SHA-256 of each v8 Room JSON entity's canonical table+column slice. */
    val expectedSchemaDigests: Map<String, String> = mapOf(
        "test_results" to "sha256:d30a1a2f1355b094e6ea085197994f9042ad6acc87b472c5d2f9bec333227270",
        "run_sessions" to "sha256:3ecd3405c92976857fb16526521e2ca1c89aba8b9957345e208fca51499a3b39",
        "location_plans" to "sha256:48df8fa192f0ef62548e0a0a467cb9cf51173a416bb3872d4d8e1a96e623e322",
        "location_tasks" to "sha256:c9aa5b57968349255c54c2947f8b17c03451426da47cbb7f290b0840e16bb728",
        "test_attempts" to "sha256:bc8ffe71f14f7408f65c8e8d9ba6df4d6ba019a8e00e8ffa68e530bbadc91833",
        "trusted_quota_entries" to "sha256:4dea0097bf13143fa202e331d3c9e02f506545365f4176e4ec7cbbf656bcb353",
        "cellrebel_executions" to "sha256:8062b222a0646e5ecb78b5b1e33759cd96a0deed506de8057413b5cb7cd47dec",
        "auto_audit_events" to "sha256:18bc51da6dddd702fcccc44d2e41eb4c36bbba4ae504905aa5a03211f7c407ef",
        "legacy_completion_snapshots" to "sha256:b3bc917d5a038c57cb2f3f155ecdcebf6a6777ae1130f7dbf7002aec911ec1ef",
        "provider_pairing_records" to "sha256:08fa0ff5429dcd9b90f8bb011713fc923dd0fcaca6b7766ab4d0221d62d4bbf9",
        "unverified_attempt_records" to "sha256:17e5d3d5240cd04a9e8bd67d92f2e4420cd5029c82b30aa102a5f9ced9431156",
        "durable_observation_records" to "sha256:52a4464083dc673b57803ed83667c692bd28fedefd09034f1a873917ce91d741",
        "durable_completion_receipts" to "sha256:39e2c849f880d3a5139106f26fe78b8ae42646eba53a3fae96fe7a048da9e77d",
        "operation_receipts" to "sha256:d47ecd9a2173ae120496309fc2fa0674ca5a61e7935b8d8436debf02bfe68a4b",
        "recovery_checkpoints" to "sha256:1be9e69611d8fedd3b0f3bdf228f8d8ac98ed24a84873758f16b34a7ce88d0b1",
        "release_receipts" to "sha256:51f387be95fb1025f8dc8d1abcc57437781fad6b548c3dff09ceaf31a8ec9edf",
        "advance_replay_carriers" to "sha256:9d7c5bfb6b995c08ff81c0d6c7735a6e1e8388c1361fe2052b626b9b1da08133",
        "advance_receipts" to "sha256:1a0606ab0bf6b76d2e7c647fd8adea78a8cdf904bb2a4a117b62151ffc4c59e3"
    )

    val requiredTables: Set<String> = expectedSchemaDigests.keys
}

data class CutoverTableSnapshot(
    val rowCount: Long,
    val schemaDigest: String,
    val rowDigest: String
)

/** A restored pairing is history only; a new active pairing must require fresh operator approval. */
data class CutoverPairingHistory(
    val signerDigest: String,
    val active: Boolean
)

/**
 * Metadata-only canonical carrier. It proves the future producer captured all Room v8 tables and
 * DataStore under one capture id, but cannot itself claim that the capture was atomic. That proof
 * remains the Auto-owned snapshot interface's responsibility.
 */
data class CutoverBundleV1(
    val sourcePackage: String,
    val captureId: String,
    val dataStoreDigest: String,
    val visibilityFenceRequired: Boolean,
    val eligibilityRecheckRequired: Boolean,
    val tables: Map<String, CutoverTableSnapshot>,
    val pairingHistory: List<CutoverPairingHistory>
)

/**
 * A dependency-free, line-oriented canonical envelope for the SAF carrier.
 *
 * Importers must re-check eligibility at their write boundary and retain a visibility fence until
 * every Room/DataStore write is durable. Both obligations are encoded as fail-closed requirements,
 * rather than being inferred from a successful parse.
 */
object CutoverBundleCodecV1 {
    private const val FORMAT = "cutover-bundle-v1"
    private const val LEGACY_PACKAGE = "com.example.cellrebelauto"
    private val token = Regex("[A-Za-z0-9._:-]+")

    fun encode(bundle: CutoverBundleV1): String {
        validate(bundle)
        return buildList {
            add(FORMAT)
            add("source=${bundle.sourcePackage}")
            add("capture=${bundle.captureId}")
            add("roomVersion=${CutoverSchemaV8.ROOM_VERSION}")
            add("dataStoreDigest=${bundle.dataStoreDigest}")
            add("visibilityFence=${bundle.visibilityFenceRequired}")
            add("eligibilityRecheck=${bundle.eligibilityRecheckRequired}")
            bundle.tables.toSortedMap().forEach { (table, snapshot) ->
                add("table=$table|${snapshot.rowCount}|${snapshot.schemaDigest}|${snapshot.rowDigest}")
            }
            bundle.pairingHistory.sortedBy { it.signerDigest }.forEach { pairing ->
                add("pairing=${pairing.signerDigest}|historical")
            }
        }.joinToString(separator = "\n")
    }

    fun decode(serialized: String): CutoverBundleV1 {
        val lines = serialized.split('\n')
        require(lines.isNotEmpty() && lines.first() == FORMAT) { "unsupported cutover bundle format" }
        require(lines.none { it.isBlank() }) { "cutover bundle cannot contain blank lines" }

        var sourcePackage: String? = null
        var captureId: String? = null
        var roomVersion: Int? = null
        var dataStoreDigest: String? = null
        var visibilityFenceRequired: Boolean? = null
        var eligibilityRecheckRequired: Boolean? = null
        val tables = linkedMapOf<String, CutoverTableSnapshot>()
        val pairingHistory = mutableListOf<CutoverPairingHistory>()

        lines.drop(1).forEach { line ->
            val separator = line.indexOf('=')
            require(separator > 0) { "invalid cutover bundle line" }
            val key = line.substring(0, separator)
            val value = line.substring(separator + 1)
            when (key) {
                "source" -> sourcePackage = unique(sourcePackage, value, key)
                "capture" -> captureId = unique(captureId, value, key)
                "roomVersion" -> roomVersion = unique(roomVersion, value.toIntOrNull(), key)
                "dataStoreDigest" -> dataStoreDigest = unique(dataStoreDigest, value, key)
                "visibilityFence" -> visibilityFenceRequired = unique(visibilityFenceRequired, parseBoolean(value, key), key)
                "eligibilityRecheck" -> eligibilityRecheckRequired = unique(eligibilityRecheckRequired, parseBoolean(value, key), key)
                "table" -> {
                    val fields = value.split('|')
                    require(fields.size == 4) { "invalid table snapshot" }
                    require(!tables.containsKey(fields[0])) { "duplicate table snapshot: ${fields[0]}" }
                    tables[fields[0]] = CutoverTableSnapshot(
                        rowCount = fields[1].toLongOrNull() ?: throw IllegalArgumentException("invalid table row count"),
                        schemaDigest = fields[2],
                        rowDigest = fields[3]
                    )
                }
                "pairing" -> {
                    val fields = value.split('|')
                    require(fields.size == 2 && fields[1] == "historical") { "pairing must be historical" }
                    pairingHistory += CutoverPairingHistory(fields[0], active = false)
                }
                else -> throw IllegalArgumentException("unknown cutover bundle field: $key")
            }
        }

        val bundle = CutoverBundleV1(
            sourcePackage = sourcePackage ?: throw IllegalArgumentException("missing source"),
            captureId = captureId ?: throw IllegalArgumentException("missing capture"),
            dataStoreDigest = dataStoreDigest ?: throw IllegalArgumentException("missing DataStore digest"),
            visibilityFenceRequired = visibilityFenceRequired
                ?: throw IllegalArgumentException("missing visibility fence"),
            eligibilityRecheckRequired = eligibilityRecheckRequired
                ?: throw IllegalArgumentException("missing eligibility recheck"),
            tables = tables,
            pairingHistory = pairingHistory
        )
        require(roomVersion == CutoverSchemaV8.ROOM_VERSION) { "unexpected Room version" }
        validate(bundle)
        require(encode(bundle) == serialized) { "cutover bundle is not canonical" }
        return bundle
    }

    private fun validate(bundle: CutoverBundleV1) {
        require(bundle.sourcePackage == LEGACY_PACKAGE) { "cutover export must originate from legacyId" }
        requireToken(bundle.captureId, "capture")
        requireToken(bundle.dataStoreDigest, "DataStore digest")
        require(bundle.visibilityFenceRequired) { "restore visibility fence is required" }
        require(bundle.eligibilityRecheckRequired) { "restore eligibility recheck is required" }
        require(bundle.tables.keys == CutoverSchemaV8.requiredTables) { "Room v8 table census is incomplete or stale" }
        bundle.tables.forEach { (table, snapshot) ->
            requireToken(table, "table")
            require(snapshot.rowCount >= 0) { "table row count cannot be negative" }
            requireToken(snapshot.schemaDigest, "schema digest")
            require(snapshot.schemaDigest == CutoverSchemaV8.expectedSchemaDigests.getValue(table)) {
                "unexpected schema digest for $table"
            }
            requireToken(snapshot.rowDigest, "row digest")
        }
        require(bundle.pairingHistory.none { it.active }) { "historical pairing cannot restore as active" }
        require(bundle.pairingHistory.map { it.signerDigest }.distinct().size == bundle.pairingHistory.size) {
            "duplicate pairing history"
        }
        bundle.pairingHistory.forEach { requireToken(it.signerDigest, "pairing digest") }
    }

    private fun requireToken(value: String, field: String) {
        require(token.matches(value)) { "invalid $field" }
    }

    private fun parseBoolean(value: String, field: String): Boolean = when (value) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("invalid $field")
    }

    private fun <T> unique(current: T?, next: T?, field: String): T {
        require(current == null && next != null) { "missing or duplicate $field" }
        return next
    }
}
