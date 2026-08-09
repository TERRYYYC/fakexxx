package name.caiyao.fakegps.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR #23 re-review (Sol): a MODE_PRIVATE read of the transport name poisons the ContextImpl
 * SharedPreferences cache so the transport silently downgrades to app-private storage whose 0664 bit
 * is a false positive (the 0700 data dir keeps the target UID out). These pin the contract guards:
 *  - reject an app-private path as cross-process readable (first-upgrade / cold-process where the
 *    private copy and the Vector mirror differ), and
 *  - count a publish only when every durability step committed.
 * The acquisition ORDER (world-readable before any other access to the transport name) and the
 * synchronized transaction are enforced in ConfigPrefsSync and proven on device with a changed
 * fingerprint.
 */
class TransportCachePoisonContractTest {

    private val dataDir = "/data/user/0/name.caiyao.fakegps"

    @Test
    fun `an app-private prefs file is not a cross-process transport`() {
        assertTrue(
            ConfigPublicationContract.isAppPrivatePath("$dataDir/shared_prefs/spoof_config.xml", dataDir),
        )
    }

    @Test
    fun `the legacy data-data path is also app-private`() {
        assertTrue(
            ConfigPublicationContract.isAppPrivatePath(
                "/data/data/name.caiyao.fakegps/shared_prefs/spoof_config.xml",
                "/data/data/name.caiyao.fakegps",
            ),
        )
    }

    @Test
    fun `the Vector mirror path outside dataDir is a cross-process transport`() {
        assertFalse(
            ConfigPublicationContract.isAppPrivatePath(
                "/data/misc/6997007a/prefs/name.caiyao.fakegps/spoof_config.xml", dataDir,
            ),
        )
    }

    @Test
    fun `a sibling dir sharing the dataDir prefix is not app-private`() {
        // Guard against a naive prefix match: .../name.caiyao.fakegps.bench is NOT under
        // .../name.caiyao.fakegps.
        assertFalse(
            ConfigPublicationContract.isAppPrivatePath("$dataDir.bench/shared_prefs/x.xml", dataDir),
        )
    }

    @Test
    fun `publish counts only when every durability step committed`() {
        assertTrue(ConfigPublicationContract.publicationResult(true, true, true, true))
        assertFalse(
            "pre-fail marker not durable",
            ConfigPublicationContract.publicationResult(preMarkDurable = false, committed = true, crossProcessReadable = true, outcomeDurable = true),
        )
        assertFalse(
            "payload commit failed",
            ConfigPublicationContract.publicationResult(preMarkDurable = true, committed = false, crossProcessReadable = true, outcomeDurable = true),
        )
        assertFalse(
            "file not cross-process readable",
            ConfigPublicationContract.publicationResult(preMarkDurable = true, committed = true, crossProcessReadable = false, outcomeDurable = true),
        )
        assertFalse(
            "success outcome not durable",
            ConfigPublicationContract.publicationResult(preMarkDurable = true, committed = true, crossProcessReadable = true, outcomeDurable = false),
        )
    }
}
