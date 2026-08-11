package name.caiyao.fakegps.data.repository

import name.caiyao.fakegps.data.db.ProfileEntity
import name.caiyao.fakegps.data.db.ProfileEntityCodec

/** Pure transaction plan; exact duplicate identity includes every profile field except Room id. */
object ProfileImportPlanner {
    data class Plan(
        val toInsert: List<ProfileEntity>,
        val duplicates: Int,
    )

    fun plan(existing: List<ProfileEntity>, candidates: List<ProfileEntity>): Plan {
        val seen = existing.mapTo(linkedSetOf(), ProfileEntityCodec::canonical)
        val insert = mutableListOf<ProfileEntity>()
        var duplicates = 0
        for (candidate in candidates) {
            val canonical = ProfileEntityCodec.canonical(candidate)
            if (seen.add(canonical)) insert += canonical else duplicates++
        }
        return Plan(insert, duplicates)
    }
}
