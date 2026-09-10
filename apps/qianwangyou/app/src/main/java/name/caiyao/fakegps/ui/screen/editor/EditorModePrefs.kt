package name.caiyao.fakegps.ui.screen.editor

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * T11d 编辑器分层：简单/专家双模式的记忆存储。
 *
 * - `editor_simple_mode` 默认 **true**：新建档案/点开档案时默认进简单模式（只有名称、坐标、
 *   路线卡与「高级字段 ▸」入口——极简铁律：零蜂窝/WiFi 字段可见）。
 * - 切到专家模式后持久记住（下次打开编辑器仍是专家），直到在编辑器里一键回切简单模式。
 *   这里用 SharedPreferences 而非 Jetpack DataStore：工程既无 DataStore 依赖，单布尔
 *   不值得引入一个新持久化栈；键名即合同（[KEY_SIMPLE_MODE]），由 JVM 测试锁定。
 *
 * 简单/专家两个编辑器共用同一个 [ProfileEditorViewModel]（同一 NavBackStackEntry），
 * 切换只换渲染层——草稿（字段值/名称/路线）绝不因切模式而丢失。
 */
class EditorModePrefs private constructor(private val prefs: SharedPreferences) {

    private val _simpleMode = MutableStateFlow(
        prefs.getBoolean(KEY_SIMPLE_MODE, DEFAULT_SIMPLE_MODE),
    )

    /** true = 简单模式（默认）；false = 专家模式（14 组 90 字段，原样保留）。 */
    val simpleMode: StateFlow<Boolean> = _simpleMode

    val isSimpleMode: Boolean get() = _simpleMode.value

    fun setSimpleMode(simple: Boolean) {
        prefs.edit { putBoolean(KEY_SIMPLE_MODE, simple) }
        _simpleMode.value = simple
    }

    companion object {
        const val PREFS_NAME = "editor_mode"
        const val KEY_SIMPLE_MODE = "editor_simple_mode"
        const val DEFAULT_SIMPLE_MODE = true

        @Volatile
        private var INSTANCE: EditorModePrefs? = null

        fun getInstance(context: Context): EditorModePrefs {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: EditorModePrefs(
                    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                ).also { INSTANCE = it }
            }
        }

        /** 测试隔离：单例持有 SharedPreferences，重置后下次 getInstance 重读磁盘。 */
        fun resetForTests() {
            INSTANCE = null
        }
    }
}
