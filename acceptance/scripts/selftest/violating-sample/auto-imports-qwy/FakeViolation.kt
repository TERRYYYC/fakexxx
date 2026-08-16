package com.example.cellrebelauto.violation

// M-BP-01 violating sample: Auto imports qwy internal package
import name.caiyao.fakegps.internal.PrefsManager

/**
 * This file exists ONLY as a violating sample for the static guard self-test.
 * It must NEVER be included in any real build.
 *
 * If check-forbidden-boundaries.sh does NOT catch this import,
 * the guard has a false-green bug.
 */
class FakeViolation {
    fun writeQwyPrefs() {
        // This would be a direct boundary violation (INV-20)
        // PrefsManager.write("spoofEnabled", true)
    }
}
