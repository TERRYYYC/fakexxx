package name.caiyao.fakegps.ui.screen.editor

/** Where the editor goes once a save attempt finishes. */
enum class PostSaveAction {
    /** Publication failed — remain on the editor so the notice is seen. */
    STAY,

    /** Saved and published; return to the profile list. */
    BACK,

    /** Saved and published, and the user asked to verify it. */
    VERIFY,
}

/**
 * Decide what follows a save.
 *
 * <p>The load-bearing rule is the [PostSaveAction.STAY] branch: navigation is gated on the payload
 * having actually reached the hook. Jumping to the verify screen after a FAILED publish would
 * compare the user's new config against the payload the hook is still serving, so every changed
 * field reads back "wrong" — the screen would blame the spoof for what is really a publish
 * failure, and the real error notice would be scrolled away by a screen transition.
 *
 * <p>Pure and free of Android types so the rule is locked by a JVM test; the ViewModel is an
 * `AndroidViewModel` and cannot be.
 */
fun postSaveAction(published: Boolean, verifyRequested: Boolean): PostSaveAction = when {
    !published -> PostSaveAction.STAY
    verifyRequested -> PostSaveAction.VERIFY
    else -> PostSaveAction.BACK
}
