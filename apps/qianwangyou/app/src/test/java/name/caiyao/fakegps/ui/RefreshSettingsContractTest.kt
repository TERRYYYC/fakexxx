package name.caiyao.fakegps.ui

import name.caiyao.fakegps.config.PublishPropagation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the refresh-interval setting.
 *
 * <p>Background: the Settings row rendered a hardcoded literal `"60 秒"` while the hook actually
 * re-read the payload every 30 s, and the row had no `clickable` modifier at all — so the screen
 * stated a number that was both wrong and unchangeable. A UI that reports a cadence the runtime
 * does not use is the same class of defect as a verify screen that reports a spoof that isn't
 * applied: the interface lies to the user.
 *
 * <p>These tests pin the two properties that prevent it recurring:
 * every displayed/selectable value derives from the single propagation policy, and the policy
 * itself refuses values the hook cannot honour.
 */
class RefreshSettingsContractTest {

    // --- 1. The displayed default must equal the cadence the hook actually uses ---

    @Test
    fun defaultIntervalMatchesHookCadence() {
        assertEquals(
            "the settings default must be the same number the propagation policy publishes",
            PublishPropagation.HOOK_REFRESH_INTERVAL_MS,
            PublishPropagation.DEFAULT_REFRESH_INTERVAL_SEC * 1000L,
        )
    }

    @Test
    fun defaultIsThirtySeconds_notTheSixtyTheScreenUsedToClaim() {
        assertEquals(30, PublishPropagation.DEFAULT_REFRESH_INTERVAL_SEC)
    }

    // --- 2. Every selectable choice comes from the policy, not from the screen ---

    @Test
    fun choicesAreNonEmptyAndSortedAscending() {
        val choices = PublishPropagation.REFRESH_INTERVAL_CHOICES_SEC
        assertTrue("policy must offer at least two choices", choices.size >= 2)
        assertEquals("choices must be ascending for a predictable picker", choices.sorted(), choices)
    }

    @Test
    fun defaultIsOneOfTheOfferedChoices() {
        assertTrue(
            "the default must be selectable, otherwise the picker cannot show the current state",
            PublishPropagation.DEFAULT_REFRESH_INTERVAL_SEC
                in PublishPropagation.REFRESH_INTERVAL_CHOICES_SEC,
        )
    }

    @Test
    fun everyChoiceIsAccepted_andOffPolicyValuesAreRejected() {
        for (sec in PublishPropagation.REFRESH_INTERVAL_CHOICES_SEC) {
            assertTrue("policy must accept its own choice $sec", PublishPropagation.isValidInterval(sec))
        }
        assertFalse("zero would busy-loop the hook", PublishPropagation.isValidInterval(0))
        assertFalse("negative is meaningless", PublishPropagation.isValidInterval(-5))
        assertFalse("a value off the policy list must not be honoured", PublishPropagation.isValidInterval(15))
    }

    // --- 3. A stored value is sanitised through the policy before anyone acts on it ---

    @Test
    fun storedGarbageFallsBackToDefault_ratherThanDisablingRefresh() {
        assertEquals(
            PublishPropagation.DEFAULT_REFRESH_INTERVAL_SEC,
            PublishPropagation.sanitizeInterval(0),
        )
        assertEquals(
            PublishPropagation.DEFAULT_REFRESH_INTERVAL_SEC,
            PublishPropagation.sanitizeInterval(-1),
        )
        assertEquals(
            PublishPropagation.DEFAULT_REFRESH_INTERVAL_SEC,
            PublishPropagation.sanitizeInterval(999),
        )
    }

    @Test
    fun aValidStoredValueSurvivesSanitisation() {
        val chosen = PublishPropagation.REFRESH_INTERVAL_CHOICES_SEC.last()
        assertEquals(chosen, PublishPropagation.sanitizeInterval(chosen))
    }

    // --- 4. Pending stays conservative across an old-cadence → new-cadence transition ---

    /** The plan fixes the offered set; an extra option must not creep in unreviewed. */
    @Test
    fun choicesMatchThePlan() {
        assertEquals(listOf(5, 10, 30, 60), PublishPropagation.REFRESH_INTERVAL_CHOICES_SEC)
    }

    @Test
    fun pendingWindowCoversTheLongestSupportedPreviousInterval() {
        val publishedAt = 1_000_000L
        val maxDelay = PublishPropagation.MAX_PROPAGATION_DELAY_MS
        assertTrue(PublishPropagation.isPending(publishedAt, publishedAt + maxDelay - 1))
        assertFalse(PublishPropagation.isPending(publishedAt, publishedAt + maxDelay))
    }
}
