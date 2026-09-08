package name.caiyao.fakegps.data

import android.content.Context

/**
 * P0.1-3 已导入/已关闭档案文件的指纹台账（SharedPreferences）。
 *
 * 「记录已导入」是提示条只出现一次的依据：扫描发现新 CSV → 一次性提示条 →
 * 用户导入成功或手动关闭 → 指纹入档 → 下次进入不再打扰。
 */
class ImportScanLog(context: Context) {

    private val prefs =
        context.getSharedPreferences("import_scan_log", Context.MODE_PRIVATE)

    fun seen(): Set<String> =
        prefs.getStringSet(KEY_SEEN_FINGERPRINTS, emptySet()) ?: emptySet()

    fun markSeen(fingerprints: Collection<String>) {
        if (fingerprints.isEmpty()) return
        val merged = seen() + fingerprints.filter { it.isNotBlank() }
        prefs.edit().putStringSet(KEY_SEEN_FINGERPRINTS, merged).apply()
    }

    private companion object {
        const val KEY_SEEN_FINGERPRINTS = "seen_fingerprints"
    }
}
