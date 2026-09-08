package com.example.cellrebelauto.configbundle

import com.example.cellrebelauto.cutover.CutoverAccessGate
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.plan.PlanConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Everything the Auto exporter needs — gathered by the ViewModel / [BundleExportSource]. */
data class AutoBundleExport(
    val planRows: List<WorklistRowData>,
    val planSourceFileName: String,
    val planBufferSeconds: Int,
    val planConfig: PlanConfig,
    val pairingFingerprints: List<ProviderFingerprint>,
    val lane: AutoBundleSections.LaneMetadata,
    val createdAtEpochMs: Long,
)

/** One exported bundle: raw zip bytes + what the manifest declares about it. */
data class ExportedAutoBundle(
    val zipBytes: ByteArray,
    val manifest: ConfigBundleManifest,
    val files: Map<String, ByteArray>,
)

/**
 * Assembles the Auto half of the configuration bundle. The manifest enumerates EXACTLY
 * the sections this exporter writes — the completeness contract is pinned by
 * AutoBundleExportTest.
 * # 导出清单枚举即包内容：完整性契约由测试钉死
 */
object AutoBundleExporter {

    const val SECTION_PLAN = "auto.plan"
    const val SECTION_PLAN_CONFIG = "auto.planConfig"
    const val SECTION_PAIRING = "auto.pairing"
    const val SECTION_LANE = "meta.lane"

    fun export(export: AutoBundleExport, createdAtEpochMs: Long = System.currentTimeMillis()): ExportedAutoBundle {
        val files = LinkedHashMap<String, ByteArray>()
        val sections = LinkedHashMap<String, SectionRef>()

        files["auto/plan.csv"] =
            AutoBundleSections.encodePlanCsv(export.planRows).toByteArray(Charsets.UTF_8)
        sections[SECTION_PLAN] = SectionRef("auto/plan.csv", count = export.planRows.size)

        files["auto/plan_config.json"] = AutoBundleSections.encodePlanConfig(
            export.planConfig,
            export.planSourceFileName,
        ).toByteArray(Charsets.UTF_8)
        sections[SECTION_PLAN_CONFIG] = SectionRef("auto/plan_config.json")

        files["auto/pairing.json"] =
            AutoBundleSections.encodePairing(export.pairingFingerprints).toByteArray(Charsets.UTF_8)
        sections[SECTION_PAIRING] = SectionRef(
            "auto/pairing.json",
            count = export.pairingFingerprints.size,
        )

        files["meta/lane.json"] =
            AutoBundleSections.encodeLane(export.lane).toByteArray(Charsets.UTF_8)
        sections[SECTION_LANE] = SectionRef("meta/lane.json")

        val manifest = ConfigBundleManifest(
            schemaVersion = ConfigBundleContract.BUNDLE_SCHEMA_VERSION,
            createdAtEpochMs = createdAtEpochMs,
            exporter = "cellrebel-auto",
            sections = sections,
        )
        files["manifest.json"] = ConfigBundleContract.manifestBytes(manifest)

        return ExportedAutoBundle(
            zipBytes = ConfigBundleContract.writeZip(files),
            manifest = manifest,
            files = files,
        )
    }

    /** SAF create-document suggestion: fakexxx-config-<UTC date>.zip */
    fun suggestedFileName(epochMs: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return "fakexxx-config-${format.format(Date(epochMs))}.zip"
    }
}

/**
 * Read-only gather of the export snapshot from the data owners (Room). Attempts, sessions,
 * receipts and audit rows are deliberately NOT exported — the bundle migrates the PLAN,
 * not the run history; history belongs to the device that earned it.
 * # 导出只带计划与参数；尝试/回执/审计历史留在挣得它们的设备上
 */
object BundleExportSource {

    /** This build's lane identity (applicationId/version + the selected provider principal). */
    fun defaultLane(): AutoBundleSections.LaneMetadata =
        AutoBundleSections.LaneMetadata(
            qwyApplicationId = null,
            qwyVersionName = null,
            transportSchemaVersion = null,
            autoApplicationId = com.example.cellrebelauto.BuildConfig.APPLICATION_ID,
            autoVersionName = com.example.cellrebelauto.BuildConfig.VERSION_NAME,
            providerPrincipal = com.example.cellrebelauto.automation.ProviderPrincipal.selected,
        )

    suspend fun read(
        db: AppDatabase,
        accessGate: CutoverAccessGate,
        planId: Long,
        planConfig: PlanConfig,
        lane: AutoBundleSections.LaneMetadata = defaultLane(),
    ): AutoBundleExport {
        // CUT-A26 census: every direct Room consumer must be gate-derived. `db` MUST be
        // the gated instance (CellRebelAutoApp.databaseFor(application, accessGate)) —
        // the gate is threaded so that provenance stays explicit at every call site
        // (same contract as remote/RemoteControlReceiver.kt).
        val plan = db.planDao().getPlanById(planId)
        val tasks = db.locationTaskDao().getTasksForPlan(planId).sortedBy { it.csvRow }
        val pairing = db.providerPairingDao().all().filter { it.revokedAt == null }
        return AutoBundleExport(
            planRows = tasks.map {
                WorklistRowData(it.longitude, it.latitude, it.priority, it.requiredSuccesses)
            },
            planSourceFileName = plan?.sourceFileName ?: "worklist.csv",
            planBufferSeconds = plan?.globalBufferSeconds
                ?: planConfig.globalBufferSeconds ?: PlanConfig.DEFAULT_GLOBAL_BUFFER_SECONDS,
            planConfig = planConfig,
            pairingFingerprints = pairing.map {
                ProviderFingerprint(
                    applicationId = it.applicationId,
                    signerDigest = it.currentSignerDigest,
                    approvedVersionCode = it.approvedVersionCode,
                )
            },
            lane = lane,
            createdAtEpochMs = System.currentTimeMillis(),
        )
    }
}
