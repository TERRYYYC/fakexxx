package name.caiyao.fakegps.data.bundle

import androidx.room.withTransaction
import name.caiyao.fakegps.data.db.AppDatabase
import name.caiyao.fakegps.data.db.ProfileEntity
import name.caiyao.fakegps.data.db.ProfileEntityCodec
import name.caiyao.fakegps.data.repository.ProfileImportPlanner
import name.caiyao.fakegps.data.repository.ProfileRepository

/**
 * Conflict decision for sections that collide with existing local data.
 * # 冲突策略二选一：覆盖（整组替换）或跳过（保留现有）
 */
enum class ConfigBundleImportDecision { Replace, KeepExisting }

sealed interface ConfigBundleImportResult {
    data class Done(
        /** Rows actually inserted; 0 when the section was skipped or the set was already current. */
        val profilesImported: Int,
        /** Exact duplicates against the current set — the idempotent re-import evidence. */
        val profilesDuplicate: Int,
        /** false = the profiles section was SKIPPED (KeepExisting). */
        val profilesSectionApplied: Boolean,
        /** Room id of the re-anchored profile; null when no pointer matched or anchoring was skipped. */
        val anchoredProfileId: Long?,
        val anchoredProfileName: String?,
        val settingsApplied: Boolean,
        /** Fingerprints for HUMAN verification — the import never writes trust state from these. */
        val callerFingerprints: List<QwyBundleSections.CallerFingerprint>,
        /** Reconciliation warnings (count mismatches), rendered through the T2 warning channel. */
        val warnings: List<String>,
        val lane: QwyBundleSections.LaneMetadata?,
    ) : ConfigBundleImportResult

    data class Rejected(val reason: String) : ConfigBundleImportResult
}

/**
 * QWY-side config bundle import. Layered ON TOP of the T2 import/anchor chain:
 * duplicate identity is the T2 canonical one ([ProfileImportPlanner] semantics) and
 * anchoring goes through [ProfileRepository.setActiveProfile] (the verified publish
 * path). REPLACE swaps the whole profile set — except when the existing set is already
 * content-identical to the bundle, in which case nothing is written (idempotent
 * re-import, Room ids survive). Caller fingerprints are surfaced for manual
 * re-approval only — no trust state is ever written from a bundle.
 * # 导入叠加在 T2 导入/锚定链之上：事务性替换 + canonical 幂等 + 走既有锚定发布；
 * # 指纹仅人工核对，绝不自动授权
 */
class QwyBundleImporter(
    private val db: AppDatabase,
    private val repository: ProfileRepository,
    /**
     * Applies qwy/settings.json through SpoofSettings and (production) republishes the
     * hook payload; returning false reports a failed apply without aborting the profiles.
     */
    private val settingsApplier: suspend (QwyBundleSections.SettingsSnapshot) -> Boolean,
) {

    suspend fun import(
        zip: ByteArray,
        decision: ConfigBundleImportDecision,
    ): ConfigBundleImportResult {
        // 1. Fail-closed container + manifest validation (version, declared-vs-actual files).
        val parsed = ConfigBundleContract.parseBundle(zip)
        if (parsed is ConfigBundleParseResult.Rejected) {
            return ConfigBundleImportResult.Rejected(parsed.reason)
        }
        val bundle = (parsed as ConfigBundleParseResult.Ok).bundle
        val warnings = mutableListOf<String>()

        // 2. Decode every QWY section BEFORE touching the database — a corrupt section
        //    rejects the whole import atomically (no half state).
        val profilesResult = bundle.manifest.sections["qwy.profiles"]?.let {
            QwyBundleSections.decodeProfiles(bundle.files.getValue(it.file))
        }
        if (profilesResult is QwyBundleSections.ProfilesResult.Error) {
            return ConfigBundleImportResult.Rejected(profilesResult.reason)
        }
        val profiles = (profilesResult as? QwyBundleSections.ProfilesResult.Ok)?.profiles
        if (profiles != null) {
            val declared = bundle.manifest.sections.getValue("qwy.profiles").count
            if (declared != null && declared != profiles.size) {
                warnings += "档案行数对账不一致：清单声明 $declared，包内实际 ${profiles.size}"
            }
        }

        val pointer = bundle.manifest.sections["qwy.activeProfile"]?.let {
            QwyBundleSections.decodeActiveProfile(bundle.files.getValue(it.file))
        }
        if (pointer is QwyBundleSections.PointerResult.Error) {
            return ConfigBundleImportResult.Rejected(pointer.reason)
        }

        val settings = bundle.manifest.sections["qwy.settings"]?.let {
            QwyBundleSections.decodeSettings(bundle.files.getValue(it.file))
        }
        if (settings is QwyBundleSections.SettingsResult.Error) {
            return ConfigBundleImportResult.Rejected(settings.reason)
        }

        val callers = bundle.manifest.sections["qwy.callers"]?.let {
            QwyBundleSections.decodeCallers(bundle.files.getValue(it.file))
        }
        if (callers is QwyBundleSections.CallersResult.Error) {
            return ConfigBundleImportResult.Rejected(callers.reason)
        }

        val lane = bundle.manifest.sections["meta.lane"]?.let {
            QwyBundleSections.decodeLane(bundle.files.getValue(it.file))
        }
        if (lane is QwyBundleSections.LaneResult.Error) {
            return ConfigBundleImportResult.Rejected(lane.reason)
        }

        // 3. "Not our bundle": a QWY import needs at least one qwy.* section.
        if (profiles == null && pointer == null && settings == null && callers == null) {
            return ConfigBundleImportResult.Rejected(
                "包内没有 QWY 区段（qwy.*）——这不是 QWY 可导入的配置包",
            )
        }

        // 4. Lane config first, so the final publish already carries it.
        var settingsApplied = false
        val settingsSnapshot = (settings as? QwyBundleSections.SettingsResult.Ok)?.settings
        if (settingsSnapshot != null) {
            settingsApplied = settingsApplier(settingsSnapshot)
            if (!settingsApplied) {
                warnings += "车道配置（qwy/settings）应用失败，已保留本机现有设置"
            }
        }

        // 5. Profiles: transactional replace (KeepExisting = skip the section entirely),
        //    then resolve the active pointer by content fingerprint.
        var imported = 0
        var duplicates = 0
        var sectionApplied = false
        var anchoredId: Long? = null
        var anchoredName: String? = null
        if (profiles != null && decision == ConfigBundleImportDecision.Replace) {
            db.withTransaction {
                val existing = db.profileDao().getAll()
                // Idempotency guard: content-identical set → zero writes, ids survive.
                if (canonicalSet(existing) == canonicalSet(profiles)) {
                    imported = 0
                    duplicates = profiles.size
                } else {
                    if (existing.isNotEmpty()) db.profileDao().deleteAll()
                    val plan = ProfileImportPlanner.plan(existing = emptyList(), candidates = profiles)
                    val insertedIds = if (plan.toInsert.isNotEmpty()) {
                        db.profileDao().insertAll(plan.toInsert)
                    } else {
                        emptyList()
                    }
                    imported = insertedIds.size
                    duplicates = plan.duplicates
                }
                sectionApplied = true

                // Re-anchor by fingerprint — Room ids are regenerated on this device.
                val activePointer = pointer as? QwyBundleSections.PointerResult.Ok
                if (activePointer != null) {
                    val current = db.profileDao().getAll()
                    val match = current.firstOrNull {
                        QwyProfileFingerprint.of(it) == activePointer.fingerprint
                    }
                    if (match != null) {
                        anchoredId = match.id
                        anchoredName = match.addname
                    } else {
                        warnings += "生效档案指针在包内档案中找不到匹配" +
                            "（fingerprint=${activePointer.fingerprint.take(12)}…），未重新锚定"
                    }
                }
            }
        } else if (profiles != null) {
            warnings += "已跳过档案区段：保留本机现有档案与生效档案"
        }

        // 6. Anchor through the T2 verified publish path (once, at the very end). When only
        //    settings changed, the settingsApplier's publish is the single write to the hook.
        if (anchoredId != null) {
            repository.setActiveProfile(anchoredId!!)
        }

        return ConfigBundleImportResult.Done(
            profilesImported = imported,
            profilesDuplicate = duplicates,
            profilesSectionApplied = sectionApplied,
            anchoredProfileId = anchoredId,
            anchoredProfileName = anchoredName,
            settingsApplied = settingsApplied,
            callerFingerprints = (callers as? QwyBundleSections.CallersResult.Ok)?.callers.orEmpty(),
            warnings = warnings,
            lane = (lane as? QwyBundleSections.LaneResult.Ok)?.lane,
        )
    }

    private fun canonicalSet(profiles: List<ProfileEntity>): Set<ProfileEntity> =
        profiles.mapTo(linkedSetOf(), ProfileEntityCodec::canonical)
}
