package name.caiyao.fakegps.ui.screen.editor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * T11d 编辑器分层：模式记忆。
 *
 * - `editor_simple_mode` 默认 true（简单模式是新档案/点档案时的默认编辑器）
 * - 切专家后持久记住（下次打开编辑器仍是专家——同会话重进与进程重启同一语义）
 * - 一键回切简单模式
 */
@RunWith(RobolectricTestRunner::class)
class EditorModePrefsTest {

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(EditorModePrefs.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        EditorModePrefs.resetForTests()
    }

    private fun freshPrefs(): EditorModePrefs {
        EditorModePrefs.resetForTests()
        return EditorModePrefs.getInstance(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `default is simple mode`() {
        assertTrue(freshPrefs().isSimpleMode)
    }

    @Test
    fun `switching to expert is remembered by the next read`() {
        val prefs = freshPrefs()
        prefs.setSimpleMode(false)
        assertFalse("flow must emit the expert state immediately", prefs.isSimpleMode)
        assertFalse(
            "expert mode must survive a re-init (session memory)",
            freshPrefs().isSimpleMode,
        )
    }

    @Test
    fun `one tap back to simple mode`() {
        val prefs = freshPrefs()
        prefs.setSimpleMode(false)
        prefs.setSimpleMode(true)
        assertTrue(freshPrefs().isSimpleMode)
    }

    @Test
    fun `preference key is the contract name`() {
        assertEquals("editor_simple_mode", EditorModePrefs.KEY_SIMPLE_MODE)
    }
}
