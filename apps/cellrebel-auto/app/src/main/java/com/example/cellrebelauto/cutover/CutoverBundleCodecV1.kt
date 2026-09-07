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

    /**
     * SHA-256 of each v8 Room JSON entity's UTF-8 canonical preimage:
     * `table=<name>\\n` followed by its Room JSON field-order
     * `column=<name>|<affinity>|<notNull>\\n` lines.  The frozen source is
     * e2444fdfcabb87711544951d5ed62661b452ebb6's AppDatabase/8.json; tests
     * independently recompute these values from its checked-in projection.
     */
    val expectedSchemaDigests: Map<String, String> = mapOf(
        "test_results" to "sha256:3f2e7f966388a6f42937adc45fb50b7c1ab31c92ca13775a452cbebb51e93412",
        "run_sessions" to "sha256:cacc3969ff552f0e851c3c19e96d253c7b3ee6ddac2f7516976468ec92048df9",
        "location_plans" to "sha256:59a9321e1ee1de8530edd7b4c0111d4638de89beb4d99b3b5cd0e53ccbe90e58",
        "location_tasks" to "sha256:d2b3d56f8fcaa9f58d7b1760c54ae96dfd2fe283c5db8c0f978d7a493331a0c6",
        "test_attempts" to "sha256:84ecf5e3decff2a8879c884272cb61c7032fc784fd8a97996a93158b4d7c9607",
        "trusted_quota_entries" to "sha256:03d39ce7ea6b04caba2a3346bd2662cbbd2f1d530407a4019e14c3fb29db3baf",
        "cellrebel_executions" to "sha256:e511bc7aa14753b18ffdfcef07d812ddb0aec42f9c9134d2de428c9f94aaca1c",
        "auto_audit_events" to "sha256:ce3346416ac5a59ef4129ebaf6ff190bf76a19a928aa6cbddf560cca58507d6e",
        "legacy_completion_snapshots" to "sha256:1a7f9d3a72a5415738bf9b21cb3337954ebcf6efa183c80428e6402a0453825b",
        "provider_pairing_records" to "sha256:b8ed6e924367d3031891d6eb043da54d06fc5615a3eb3ebc06c16d46ae816279",
        "unverified_attempt_records" to "sha256:cbc2f7130ff46c9228ca59b0188feb848045535a74a5de25414d375e2ada2f0b",
        "durable_observation_records" to "sha256:778a5eb7775556a711b0c69d7a8ee0410c0852a799746bb92e92d4bdd9bffacf",
        "durable_completion_receipts" to "sha256:815f8bcffafc3ebe57ce4758242c54ee9877ef120d3c2f7d96ad9845cfbf769b",
        "operation_receipts" to "sha256:780c72af84c3903d2d27b5f86af1b43e2e2624dea1e07d60a94877e97fc08824",
        "recovery_checkpoints" to "sha256:572b2e34e698023d237a61d5ab851359141fb82763a7c622a7dc30b7cbd5105f",
        "release_receipts" to "sha256:7b620faba8fe876e59ea95c796b5fe0552847807e8a83b8b3fc69feeeb61bf58",
        "advance_replay_carriers" to "sha256:c36572903d00490233023ca2f714ca9540ce4de19fbc4827fe3586245831433c",
        "advance_receipts" to "sha256:6ab1c0a299c1a7df00a724fc7ed5c223df53819bb23b349bec512acc3cb83a24"
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
 * Metadata-only canonical carrier. It proves the future producer declared every Room v8 table and
 * a DataStore digest under one capture id, but does not recompute the opaque row/DataStore digest
 * tokens from content. It also cannot claim that capture was atomic. Content verification and the
 * transactional Room + DataStore snapshot proof remain the Auto-owned snapshot interface's work.
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
