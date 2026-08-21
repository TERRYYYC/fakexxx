package io.github.terryyyc.fakexxx.acceptance.fakeqwy

/**
 * Controllable clock for acceptance testing.
 *
 * Both epoch (wall clock) and elapsed-realtime (monotonic clock) advance
 * together by default. Elapsed-realtime is the ONLY clock used for lease
 * deadlines and continuity comparison (§6.4.2, §8.4). Epoch stays for
 * receipt timestamps and human-readable audit fields only — no machine
 * decision may depend on it.
 */
class FakeProviderClock(
    var epochMs: Long = 1_700_000_000_000L,
    var elapsedRealtimeMs: Long = 100_000L,
) {
    /** Advance both clocks by [ms]. */
    fun advance(ms: Long) {
        epochMs += ms
        elapsedRealtimeMs += ms
    }

    /**
     * Advance only the wall clock. Simulates NTP correction or user changing
     * the system clock — elapsed-realtime is immune to this (§6.4.2).
     */
    fun advanceWallClockOnly(ms: Long) {
        epochMs += ms
    }
}
