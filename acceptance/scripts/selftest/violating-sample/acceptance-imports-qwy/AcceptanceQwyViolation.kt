package io.github.terryyyc.fakexxx.acceptance.scenarios.violation

// Acceptance boundary violating sample: importing qwy internals
import name.caiyao.fakegps.MockProviderService

/**
 * This file exists ONLY as a violating sample for the static guard self-test.
 * It demonstrates acceptance module importing qwy app internals,
 * which would break the ZERO shared code boundary.
 */
class AcceptanceQwyViolation {
    // If check-forbidden-boundaries.sh does NOT catch this, the guard is broken.
}
