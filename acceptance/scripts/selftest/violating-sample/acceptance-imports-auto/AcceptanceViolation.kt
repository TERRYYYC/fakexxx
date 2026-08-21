package io.github.terryyyc.fakexxx.acceptance.scenarios.violation

// Acceptance boundary violating sample: importing Auto internals
import com.example.cellrebelauto.automation.AutomationEngine

/**
 * This file exists ONLY as a violating sample for the static guard self-test.
 * It demonstrates acceptance module importing Auto app internals,
 * which would break the ZERO shared code boundary.
 */
class AcceptanceViolation {
    // If check-forbidden-boundaries.sh does NOT catch this, the guard is broken.
}
