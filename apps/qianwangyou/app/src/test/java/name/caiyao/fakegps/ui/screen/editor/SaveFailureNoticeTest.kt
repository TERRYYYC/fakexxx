package name.caiyao.fakegps.ui.screen.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #129：「保存并验证」失败提示给出路。
 * saveAndVerify 是先写库后发布（ProfileEditorViewModel.save → repo.save → postSaveAction）：
 * published=false 时档案【已保存】，文案必须如实说"已保存"并指明出路（稍后验证 / 下方「仅保存」）；
 * 字段校验失败与写库失败则必须如实说【未保存】，绝不把失败说成已保存。
 * 纯函数 JVM 测试，钉死三类失败各自的文案投影。
 */
class SaveFailureNoticeTest {

    @Test
    fun `publish-unreachable failure says the profile is saved and points to the save-only FAB`() {
        val notice = saveFailureNotice(SaveFailureCause.PUBLISH_UNREACHABLE)

        assertTrue("must name the publish failure", notice.contains("无法发布给 Hook"))
        assertTrue("must name the Vector module lane as the cause", notice.contains("Vector"))
        assertTrue(
            "the save DID happen (repo.save runs before publish) — copy must admit it",
            notice.contains("档案已保存"),
        )
        assertTrue("way out 1: verify later", notice.contains("稍后验证"))
        assertTrue("way out 2: the save-only FAB below", notice.contains("仅保存"))
    }

    @Test
    fun `field validation failure reports the count and honestly says nothing was saved`() {
        val notice = saveFailureNotice(SaveFailureCause.FIELD_VALIDATION, errorCount = 3)

        assertTrue("must report how many fields are invalid", notice.contains("3"))
        assertTrue("validation rejects the draft BEFORE any write — copy must say so", notice.contains("尚未保存"))
        assertFalse("nothing was written, never claim it was", notice.contains("已保存"))
    }

    @Test
    fun `write failure reports the reason and never claims the profile was saved`() {
        val notice = saveFailureNotice(SaveFailureCause.WRITE_FAILED, reason = "disk I/O")

        assertTrue(notice.contains("保存失败"))
        assertTrue("must surface the underlying reason", notice.contains("disk I/O"))
        assertFalse("a failed write must not claim anything was saved", notice.contains("已保存"))
    }

    @Test
    fun `the three failure notices are mutually distinct`() {
        val notices = listOf(
            saveFailureNotice(SaveFailureCause.PUBLISH_UNREACHABLE),
            saveFailureNotice(SaveFailureCause.FIELD_VALIDATION, errorCount = 1),
            saveFailureNotice(SaveFailureCause.WRITE_FAILED, reason = "x"),
        )
        assertEquals("each failure class gets its own copy", 3, notices.toSet().size)
    }
}
