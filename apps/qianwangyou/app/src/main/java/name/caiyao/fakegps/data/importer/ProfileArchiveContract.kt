package name.caiyao.fakegps.data.importer

import name.caiyao.fakegps.data.model.FieldSpec

/** Ordered column contract shared by template generation and archive validation. */
internal object ProfileArchiveContract {
    const val NAME_COLUMN = "addname"

    val specsByColumn = FieldSpec.allCategories()
        .values
        .flatten()
        .associateBy { it.dbColumn }
    val canonicalHeaders = listOf(NAME_COLUMN) + specsByColumn.keys
    val allowedColumns = canonicalHeaders.toSet()
}
