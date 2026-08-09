package name.caiyao.fakegps.config

internal object ConfigPublicationContract {
    fun shouldKeepLastGoodPayload(
        requestedProfileId: Long?,
        resolvedProfileId: Long?,
        clearIfMissing: Boolean,
    ): Boolean = requestedProfileId != null && resolvedProfileId == null && !clearIfMissing

    /**
     * A publish only reaches the hook when the committed file is ACTUALLY readable by the target
     * app's UID. Historically the first argument was `worldReadable` — merely "MODE_WORLD_READABLE
     * did not throw". On Android N+ that call no longer applies the other-read bit, and the Vector
     * prefs mirror is written 0660, so the proxy over-reported success while the hook got
     * Permission denied. The caller must pass the VERIFIED other-read state of the committed file
     * ([isOtherReadable] over its `stat` mode), not the throw-proxy.
     */
    fun isCrossProcessPublishSuccessful(
        crossProcessReadable: Boolean,
        committed: Boolean,
    ): Boolean = crossProcessReadable && committed

    /** POSIX other-read (S_IROTH, 0o004) test over a `stat` st_mode. */
    fun isOtherReadable(stMode: Int): Boolean = (stMode and S_IROTH) != 0

    private const val S_IROTH = 0b100 // POSIX 0o004

    /**
     * Durable publication outcome, persisted OUTSIDE the transported prefs. Two reasons it must not
     * live in the payload file: (1) any second write to that file resets its cross-process-readable
     * mode, and (2) a success timestamp written INTO the same commit as the payload survives a
     * process death that happens before readability is verified, resurrecting the false-green. So the
     * outcome lives in its own private store and is written fail-closed — [preCommitFailClosed]
     * before the payload commit, flipped to [onVerifiedPublish] only after the committed file is
     * verified other-readable, else [onVerifiedFailure].
     */
    data class PublishState(
        val publishedAtMs: Long?,
        val publishFailed: Boolean,
        val activeProfileId: Long?,
    )

    /**
     * Written BEFORE the payload commit. From here until verification, any interruption must read as
     * a failure and must NOT advance the active pointer past the last verified-good profile.
     */
    fun preCommitFailClosed(prior: PublishState): PublishState =
        prior.copy(publishedAtMs = null, publishFailed = true)

    /** Written ONLY after the committed payload is verified cross-process readable. */
    fun onVerifiedPublish(nowMs: Long, publishedProfileId: Long?): PublishState =
        PublishState(publishedAtMs = nowMs, publishFailed = false, activeProfileId = publishedProfileId)

    /**
     * Written when the payload committed but is NOT verified readable (or verification threw): keep
     * the last verified-good active pointer, never a success timestamp.
     */
    fun onVerifiedFailure(prior: PublishState): PublishState =
        prior.copy(publishedAtMs = null, publishFailed = true)

    /**
     * A file under the app's private data dir cannot be read by another UID regardless of its own
     * mode — the dir is 0700 / SELinux-labelled — so its 0664 bit is a false positive. Only a
     * transport resolved OUTSIDE dataDir (the Vector mirror) is cross-process reachable. Guards
     * against a poisoned SharedPreferences cache silently redirecting the transport to app-private
     * storage.
     */
    fun isAppPrivatePath(filePath: String, appDataDir: String): Boolean =
        filePath == appDataDir || filePath.startsWith("$appDataDir/")

    /**
     * The COMPLETE durability gate for a publish. It counts only when every step is durably committed:
     * the fail-closed pre-marker (so no stale success survives), the payload, the verified
     * cross-process readability, AND the success outcome. A dropped `commit()` at any step therefore
     * cannot be reported as a successful publish.
     */
    fun publicationResult(
        preMarkDurable: Boolean,
        committed: Boolean,
        crossProcessReadable: Boolean,
        outcomeDurable: Boolean,
    ): Boolean = preMarkDurable && committed && crossProcessReadable && outcomeDurable

    /**
     * The effective active pointer: the private outcome store once it is initialized (which may hold
     * a deliberately-null pointer), else the one-time legacy migration value. Callers must resolve
     * this ONCE per operation and reuse it — a failure path that re-derives it from a still-
     * uninitialized store would read null and destroy the migrated last-good pointer.
     */
    fun resolveActiveProfileId(
        storeInitialized: Boolean,
        storeActive: Long?,
        legacyActive: Long?,
    ): Long? = if (storeInitialized) storeActive else legacyActive
}
