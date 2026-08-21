package com.example.cellrebelauto.violation

/**
 * M-BP-02 violating sample: Auto targets qwy's package for Accessibility.
 *
 * This file exists ONLY as a violating sample for the static guard self-test.
 * If check-forbidden-boundaries.sh does NOT catch this reference,
 * the guard has a false-green bug.
 */
class A11yViolation {
    companion object {
        // This would be a boundary violation (INV-20): using Accessibility to target qwy
        const val QWY_PACKAGE = "name.caiyao.fakegps"
    }

    fun launchQwyViaAccessibility() {
        // bridge.launchApp(QWY_PACKAGE)  // forbidden by M-BP-02
    }
}
