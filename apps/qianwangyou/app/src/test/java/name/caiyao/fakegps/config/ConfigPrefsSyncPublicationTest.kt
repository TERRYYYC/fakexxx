package name.caiyao.fakegps.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigPrefsSyncPublicationTest {

    @Test
    fun transientMissingSelectedProfileKeepsTheLastGoodPayload() {
        assertTrue(
            ConfigPublicationContract.shouldKeepLastGoodPayload(
                requestedProfileId = 7L,
                resolvedProfileId = null,
                clearIfMissing = false,
            ),
        )
    }

    @Test
    fun explicitDeleteMayPublishAnEmptyPayload() {
        assertFalse(
            ConfigPublicationContract.shouldKeepLastGoodPayload(
                requestedProfileId = 7L,
                resolvedProfileId = null,
                clearIfMissing = true,
            ),
        )
    }

    @Test
    fun freshInstallWithoutASelectedProfileMayPublishEmpty() {
        assertFalse(
            ConfigPublicationContract.shouldKeepLastGoodPayload(
                requestedProfileId = null,
                resolvedProfileId = null,
                clearIfMissing = false,
            ),
        )
    }

    @Test
    fun privateFallbackCommitDoesNotCountAsCrossProcessPublication() {
        assertFalse(
            ConfigPublicationContract.isCrossProcessPublishSuccessful(
                crossProcessReadable = false,
                committed = true,
            ),
        )
    }

    @Test
    fun otherReadableCommitCountsAsCrossProcessPublication() {
        assertTrue(
            ConfigPublicationContract.isCrossProcessPublishSuccessful(
                crossProcessReadable = true,
                committed = true,
            ),
        )
    }

    @Test
    fun readableButUncommittedDoesNotCountAsPublication() {
        assertFalse(
            ConfigPublicationContract.isCrossProcessPublishSuccessful(
                crossProcessReadable = true,
                committed = false,
            ),
        )
    }
}
