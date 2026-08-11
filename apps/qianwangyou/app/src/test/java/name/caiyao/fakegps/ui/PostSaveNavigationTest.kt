package name.caiyao.fakegps.ui

import name.caiyao.fakegps.ui.screen.editor.PostSaveAction
import name.caiyao.fakegps.ui.screen.editor.postSaveAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What may happen after a save, given whether the payload actually reached the hook.
 *
 * <p>Extracted as a pure function because the ViewModel itself cannot be unit-tested (it is an
 * `AndroidViewModel` over a Room repository), and this is precisely the decision that must not be
 * gotten wrong: a "保存并验证" button that navigates on a FAILED publish would send the user to a
 * verify screen that compares their brand-new config against the payload the hook is still
 * running — every field reads back "wrong", and the screen blames the spoof rather than the
 * publish. Silence about a failed publish is the defect; a jump that hides it is worse.
 */
class PostSaveNavigationTest {

    @Test
    fun publishedWithoutVerifyRequest_returnsToTheList() {
        assertEquals(
            PostSaveAction.BACK,
            postSaveAction(published = true, verifyRequested = false),
        )
    }

    @Test
    fun publishedWithVerifyRequest_goesToVerify() {
        assertEquals(
            PostSaveAction.VERIFY,
            postSaveAction(published = true, verifyRequested = true),
        )
    }

    /** The whole point: a failed publish must never navigate, in either mode. */
    @Test
    fun failedPublish_staysPut_soTheFailureIsVisible() {
        assertEquals(
            PostSaveAction.STAY,
            postSaveAction(published = false, verifyRequested = false),
        )
        assertEquals(
            "verifying an unpublished draft would blame the spoof for a publish failure",
            PostSaveAction.STAY,
            postSaveAction(published = false, verifyRequested = true),
        )
    }
}
