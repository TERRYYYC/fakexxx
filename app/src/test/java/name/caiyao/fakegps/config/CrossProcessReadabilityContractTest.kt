package name.caiyao.fakegps.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the silent Hook publish failure observed on device ZY22JHW9M4 (Vector/LSPosed,
 * Android 15) with the merged-master build.
 *
 * Root cause: on Android N+ `MODE_WORLD_READABLE` no longer applies the other-read bit, and the
 * Vector prefs mirror is written **0660**. The old publication gate treated "MODE_WORLD_READABLE
 * did not throw" as proof of cross-process readability, so a 0660 mirror was stamped `published_at`
 * as a success while the hooked target (Google Maps, a different UID) got `Permission denied`
 * -> `jsonStr == null` -> `PASSTHROUGH` -> `location=false`, with NO `transport accepted`.
 *
 * The contract must gate on the ACTUAL other-read bit of the committed file, never on the
 * throw-proxy. `isOtherReadable` is the pure check ConfigPrefsSync feeds from `Os.stat().st_mode`.
 */
class CrossProcessReadabilityContractTest {

    @Test
    fun `0660 rw-rw---- is not other-readable`() {
        assertFalse(ConfigPublicationContract.isOtherReadable(0b110_110_000))
    }

    @Test
    fun `0664 rw-rw-r-- is other-readable`() {
        assertTrue(ConfigPublicationContract.isOtherReadable(0b110_110_100))
    }

    @Test
    fun `0644 rw-r--r-- is other-readable`() {
        assertTrue(ConfigPublicationContract.isOtherReadable(0b110_100_100))
    }

    @Test
    fun `0600 rw------- is not other-readable`() {
        assertFalse(ConfigPublicationContract.isOtherReadable(0b110_000_000))
    }

    @Test
    fun `a committed but 0660 mirror is NOT a successful cross-process publish`() {
        // The exact false-green: commit succeeds, but the hook's UID can never read the file.
        val readable = ConfigPublicationContract.isOtherReadable(0b110_110_000)
        assertFalse(
            ConfigPublicationContract.isCrossProcessPublishSuccessful(readable, committed = true),
        )
    }

    @Test
    fun `a committed 0664 mirror IS a successful cross-process publish`() {
        val readable = ConfigPublicationContract.isOtherReadable(0b110_110_100)
        assertTrue(
            ConfigPublicationContract.isCrossProcessPublishSuccessful(readable, committed = true),
        )
    }

    @Test
    fun `an other-readable file with a failed commit is still not a publication`() {
        val readable = ConfigPublicationContract.isOtherReadable(0b110_110_100)
        assertFalse(
            ConfigPublicationContract.isCrossProcessPublishSuccessful(readable, committed = false),
        )
    }
}
