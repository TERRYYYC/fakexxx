package name.caiyao.fakegps.integration.v1

/** Fail-closed projection of QWY's legacy `temp.id` owner into v1 profile refs. */
object ProfileRefProjection {
    fun fromLegacyIds(ids: List<Long>): List<String> {
        if (ids.any { it <= 0L } || ids.zipWithNext().any { (before, after) -> before >= after }) {
            return emptyList()
        }
        return ids.map { "profile-$it" }
    }
}
