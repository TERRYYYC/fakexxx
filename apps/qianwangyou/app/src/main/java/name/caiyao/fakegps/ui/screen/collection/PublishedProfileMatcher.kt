package name.caiyao.fakegps.ui.screen.collection

import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.config.PayloadRead
import name.caiyao.fakegps.config.PublishedConfig
import name.caiyao.fakegps.config.UnavailableFieldSet
import name.caiyao.fakegps.data.db.ProfileEntity
import name.caiyao.fakegps.data.db.ProfileEntityCodec
import name.caiyao.fakegps.data.model.ProfileFieldValueValidator

/** Resolves the badge from published bytes instead of guessing that Room's oldest row is active. */
internal object PublishedProfileMatcher {
    fun effectiveProfileId(profiles: List<ProfileEntity>, read: PayloadRead): Long? {
        val raw = (read as? PayloadRead.Raw)?.text ?: return null
        val published = PublishedConfig.parse(raw) ?: return null
        if (published.schemaVersion !in setOf(
                ConfigPrefsSync.LEGACY_SCHEMA_VERSION,
                ConfigPrefsSync.PREVIOUS_SCHEMA_VERSION,
                ConfigPrefsSync.SCHEMA_VERSION,
            ) || !published.fieldsPresent ||
            (published.schemaVersion != ConfigPrefsSync.LEGACY_SCHEMA_VERSION &&
                !published.unavailablePresent)
        ) {
            return null
        }
        return profiles.firstOrNull { matches(it, published) }?.id
    }

    private fun matches(profile: ProfileEntity, published: PublishedConfig): Boolean {
        val draft = ProfileEntityCodec.toDraft(profile)
        val configured = draft.filterValues { it != ProfileEntityCodec.UNAVAILABLE_TOKEN }
            .toMutableMap()
            .apply { profile.addname?.let { put("addname", it) } }
        if (configured.keys != published.fields.keys) return false
        if (configured.any { (column, value) ->
                canonical(column, value) != canonical(column, published.fields.getValue(column))
            }
        ) {
            return false
        }
        return UnavailableFieldSet.decode(profile.unavailableFields) == published.unavailable
    }

    private fun canonical(column: String, value: String): String =
        ProfileFieldValueValidator.normalize(column, value).let { result ->
            if (result.error == null) result.value.orEmpty() else value
        }
}
