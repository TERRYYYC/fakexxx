package name.caiyao.fakegps.ui.screen.editor

/**
 * #129: why a "保存并验证" attempt did not end in a published config. The three classes have
 * physically different consequences for the user's data, so the notice copy must differ too:
 *
 * - [FIELD_VALIDATION] / [WRITE_FAILED]: nothing reached the database — the copy must honestly
 *   say 未保存.
 * - [PUBLISH_UNREACHABLE]: `repo.save` runs BEFORE the publish, so the row IS in the database
 *   even though the hook never got it — the copy must honestly say 已保存 and hand the user a
 *   way out (verify later, or the labeled 仅保存 FAB to retry the publish) instead of the old
 *   dead-end "已写入数据库，但未发布" that read like a total failure.
 */
enum class SaveFailureCause {
    /** Field validation rejected the draft before any write — nothing was saved. */
    FIELD_VALIDATION,

    /** The database write itself threw — nothing was saved. */
    WRITE_FAILED,

    /** The row was written, but the payload never reached the hook (module off / mirror unreachable). */
    PUBLISH_UNREACHABLE,
}

/**
 * Project a save failure onto its user-facing notice. Pure and free of Android types so the copy
 * contract is locked by a JVM test (same lane as [postSaveAction]).
 */
fun saveFailureNotice(
    cause: SaveFailureCause,
    errorCount: Int = 0,
    reason: String? = null,
): String = when (cause) {
    SaveFailureCause.FIELD_VALIDATION ->
        "有 $errorCount 个字段格式无效，尚未保存"
    SaveFailureCause.WRITE_FAILED ->
        "保存失败：${reason ?: "未知错误"}"
    SaveFailureCause.PUBLISH_UNREACHABLE ->
        "无法发布给 Hook（常见原因：Vector 模块未启用或发布通道不可达）——档案已保存。" +
            "可稍后验证，或点下方「仅保存」。"
}
