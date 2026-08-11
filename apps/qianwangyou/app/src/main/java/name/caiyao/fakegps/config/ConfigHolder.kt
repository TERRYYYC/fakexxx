package name.caiyao.fakegps.config

import java.util.concurrent.atomic.AtomicReference

/**
 * Holds the currently-effective [SpoofConfig] and enforces LAST-KNOWN-GOOD semantics
 * on hot-reload.
 *
 * This is the code-level fix for the reviewer's correction: a hot-reload that fails
 * to parse must NOT revert to real device data mid-test (that would leak the true
 * environment while an app is watching). Instead:
 *
 *   - initial state          = null   => caller treats as PASSTHROUGH (first launch,
 *                                        no config yet: passing through real values is
 *                                        the safe default BEFORE any spoof is active)
 *   - update(valid json)     => replace current, return success(newConfig)
 *   - update(invalid json)   => KEEP the currently-effective config, return failure(err)
 *
 * The hook hot-path only ever reads [current] (in-memory AtomicReference); it never
 * parses JSON or touches IPC/disk.
 */
class ConfigHolder {

    private val ref = AtomicReference<SpoofConfig?>(null)

    /** Currently-effective config, or null if none has been loaded yet (=> passthrough). */
    fun current(): SpoofConfig? = ref.get()

    /**
     * Attempt to hot-reload from a JSON snapshot.
     *
     * Two rejection paths, BOTH preserving last-known-good (previously-effective config
     * stays untouched, we NEVER revert to real device data):
     *   - malformed JSON             -> parse throws              -> failure
     *   - valid JSON, unknown schema -> IncompatibleSchemaVersion -> failure
     *
     * Forward compatibility must go through an explicit migration step, never silent
     * acceptance of an unknown schemaVersion (reviewer P1).
     *
     * @return [Result.success] only when the snapshot parses AND its schemaVersion matches
     *         this build; otherwise [Result.failure] with the current config left intact.
     */
    fun update(json: String): Result<SpoofConfig> = try {
        val parsed = ConfigCodec.fromJson(json)
        if (parsed.schemaVersion != SpoofConfig.SCHEMA_VERSION) {
            Result.failure(
                IncompatibleSchemaVersionException(parsed.schemaVersion, SpoofConfig.SCHEMA_VERSION)
            )
        } else {
            ref.set(parsed)
            Result.success(parsed)
        }
    } catch (e: Exception) {
        // last-known-good: keep whatever was effective; never silently revert to real data.
        Result.failure(e)
    }
}

/** A snapshot parsed cleanly but declared a schemaVersion this build cannot interpret. */
class IncompatibleSchemaVersionException(val found: Int, val expected: Int) :
    IllegalArgumentException("incompatible schemaVersion=$found (this build expects $expected)")
