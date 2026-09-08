package name.caiyao.fakegps.data.bundle

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import name.caiyao.fakegps.data.db.ProfileEntity

/** Everything the QWY exporter needs — gathered by the ViewModel from the data owners. */
data class QwyBundleExport(
    val profiles: List<ProfileEntity>,
    /** The profile the hook is actually running (resolved via PublishedProfileMatcher), null = none. */
    val activeProfile: ActiveProfileRef?,
    val settings: QwyBundleSections.SettingsSnapshot?,
    val callers: List<QwyBundleSections.CallerFingerprint>,
    val lane: QwyBundleSections.LaneMetadata,
    val createdAtEpochMs: Long,
) {
    data class ActiveProfileRef(val fingerprint: String, val addname: String?)
}

/** One exported bundle: raw zip bytes + what the manifest declares about it. */
data class ExportedQwyBundle(
    val zipBytes: ByteArray,
    val manifest: ConfigBundleManifest,
    val files: Map<String, ByteArray>,
)

/**
 * Assembles the QWY half of the configuration bundle. The manifest enumerates EXACTLY the
 * sections this exporter writes — the completeness contract (every declared file in the
 * package, nothing else) is pinned by QwyBundleExportTest.
 * # 导出清单枚举即包内容：完整性契约由测试钉死
 */
object QwyBundleExporter {

    const val SECTION_PROFILES = "qwy.profiles"
    const val SECTION_ACTIVE_PROFILE = "qwy.activeProfile"
    const val SECTION_SETTINGS = "qwy.settings"
    const val SECTION_CALLERS = "qwy.callers"
    const val SECTION_LANE = "meta.lane"

    fun export(export: QwyBundleExport): ExportedQwyBundle {
        val files = LinkedHashMap<String, ByteArray>()
        val sections = LinkedHashMap<String, SectionRef>()

        files["qwy/profiles.json"] =
            QwyBundleSections.encodeProfiles(export.profiles).toByteArray(Charsets.UTF_8)
        sections[SECTION_PROFILES] = SectionRef(
            "qwy/profiles.json",
            count = export.profiles.size,
        )

        if (export.activeProfile != null) {
            files["qwy/active_profile.json"] = QwyBundleSections.encodeActiveProfile(
                export.activeProfile.fingerprint,
                export.activeProfile.addname,
            ).toByteArray(Charsets.UTF_8)
            sections[SECTION_ACTIVE_PROFILE] = SectionRef("qwy/active_profile.json")
        }

        if (export.settings != null) {
            files["qwy/settings.json"] =
                QwyBundleSections.encodeSettings(export.settings).toByteArray(Charsets.UTF_8)
            sections[SECTION_SETTINGS] = SectionRef("qwy/settings.json")
        }

        files["qwy/callers.json"] =
            QwyBundleSections.encodeCallers(export.callers).toByteArray(Charsets.UTF_8)
        sections[SECTION_CALLERS] = SectionRef(
            "qwy/callers.json",
            count = export.callers.size,
        )

        files["meta/lane.json"] =
            QwyBundleSections.encodeLane(export.lane).toByteArray(Charsets.UTF_8)
        sections[SECTION_LANE] = SectionRef("meta/lane.json")

        files["manifest.json"] = ConfigBundleContract.manifestBytes(
            ConfigBundleManifest(
                schemaVersion = ConfigBundleContract.BUNDLE_SCHEMA_VERSION,
                createdAtEpochMs = export.createdAtEpochMs,
                exporter = "qianwangyou",
                sections = sections,
            ),
        )

        return ExportedQwyBundle(
            zipBytes = ConfigBundleContract.writeZip(files),
            manifest = ConfigBundleManifest(
                schemaVersion = ConfigBundleContract.BUNDLE_SCHEMA_VERSION,
                createdAtEpochMs = export.createdAtEpochMs,
                exporter = "qianwangyou",
                sections = sections,
            ),
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
