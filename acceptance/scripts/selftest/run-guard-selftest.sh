#!/usr/bin/env bash
# run-guard-selftest.sh — Self-test for check-forbidden-boundaries.sh
#
# Verifies the static guard itself:
#   1. On CLEAN directories → must exit 0 (no false positives)
#   2. On VIOLATING samples → must exit ≠ 0 (no false negatives)
#
# Per §10.1: this self-test is wired into :scenarios:guardSelfTest,
# which :scenarios:selfTest dependsOn.
#
# Run: bash acceptance/scripts/selftest/run-guard-selftest.sh

set -euo pipefail

SELFTEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPTS_DIR="$(cd "$SELFTEST_DIR/.." && pwd)"
GUARD_SCRIPT="$SCRIPTS_DIR/check-forbidden-boundaries.sh"
REPO_ROOT="$(cd "$SCRIPTS_DIR/../.." && pwd)"
VIOLATING_DIR="$SELFTEST_DIR/violating-sample"

pass=0
fail=0
total=0

run_case() {
    local label="$1"
    local expected_exit="$2"  # "zero" or "nonzero"
    local setup_fn="$3"
    local teardown_fn="$4"

    total=$((total + 1))

    # Setup
    $setup_fn

    # Run the guard script, capturing exit code
    local actual_exit=0
    bash "$GUARD_SCRIPT" > /dev/null 2>&1 || actual_exit=$?

    # Teardown
    $teardown_fn

    # Check
    if [ "$expected_exit" = "zero" ] && [ "$actual_exit" -eq 0 ]; then
        echo "  ✅ $label → exit 0 (expected clean)"
        pass=$((pass + 1))
    elif [ "$expected_exit" = "nonzero" ] && [ "$actual_exit" -ne 0 ]; then
        echo "  ✅ $label → exit $actual_exit (expected violation detected)"
        pass=$((pass + 1))
    elif [ "$expected_exit" = "zero" ] && [ "$actual_exit" -ne 0 ]; then
        echo "  ❌ $label → exit $actual_exit (expected 0 = false positive!)"
        fail=$((fail + 1))
    else
        echo "  ❌ $label → exit $actual_exit (expected nonzero = false negative!)"
        fail=$((fail + 1))
    fi
}

# ─── Setup/teardown functions ────────────────────────────────────────

# Case 1: Clean directories (no modifications) → must pass
setup_clean() { :; }
teardown_clean() { :; }

# Case 2: M-BP-01 violation — inject qwy import into Auto source
setup_bp01_violation() {
    cp "$VIOLATING_DIR/auto-imports-qwy/FakeViolation.kt" \
       "$REPO_ROOT/apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/FakeViolation.kt"
}
teardown_bp01_violation() {
    rm -f "$REPO_ROOT/apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/FakeViolation.kt"
}

# Case 3: M-BP-02 violation — inject qwy Accessibility target into Auto source
setup_bp02_violation() {
    cp "$VIOLATING_DIR/auto-a11y-targets-qwy/A11yViolation.kt" \
       "$REPO_ROOT/apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/A11yViolation.kt"
}
teardown_bp02_violation() {
    rm -f "$REPO_ROOT/apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/A11yViolation.kt"
}

# Case 4: Acceptance imports Auto — inject Auto import into acceptance
setup_acc_auto_violation() {
    cp "$VIOLATING_DIR/acceptance-imports-auto/AcceptanceViolation.kt" \
       "$REPO_ROOT/acceptance/scenarios/src/matrixTest/kotlin/matrix/AcceptanceViolation.kt"
}
teardown_acc_auto_violation() {
    rm -f "$REPO_ROOT/acceptance/scenarios/src/matrixTest/kotlin/matrix/AcceptanceViolation.kt"
}

# Case 5: Acceptance imports qwy — inject qwy import into acceptance
setup_acc_qwy_violation() {
    cp "$VIOLATING_DIR/acceptance-imports-qwy/AcceptanceQwyViolation.kt" \
       "$REPO_ROOT/acceptance/scenarios/src/matrixTest/kotlin/matrix/AcceptanceQwyViolation.kt"
}
teardown_acc_qwy_violation() {
    rm -f "$REPO_ROOT/acceptance/scenarios/src/matrixTest/kotlin/matrix/AcceptanceQwyViolation.kt"
}

# ─── Run cases ───────────────────────────────────────────────────────

echo "═══════════════════════════════════════════════════════════════════"
echo "  run-guard-selftest.sh — self-test for check-forbidden-boundaries"
echo "═══════════════════════════════════════════════════════════════════"
echo ""

echo "── Clean baseline (no violations) ────────────────────────────────"
run_case "Clean repo: guard must pass" "zero" setup_clean teardown_clean

echo ""
echo "── M-BP-01 violation (Auto imports qwy) ──────────────────────────"
run_case "Auto imports qwy internals: guard must fail" "nonzero" setup_bp01_violation teardown_bp01_violation

echo ""
echo "── M-BP-02 violation (Auto a11y targets qwy) ────────────────────"
run_case "Auto a11y targets qwy package: guard must fail" "nonzero" setup_bp02_violation teardown_bp02_violation

echo ""
echo "── Acceptance imports Auto (boundary violation) ──────────────────"
run_case "Acceptance imports Auto internals: guard must fail" "nonzero" setup_acc_auto_violation teardown_acc_auto_violation

echo ""
echo "── Acceptance imports qwy (boundary violation) ──────────────────"
run_case "Acceptance imports qwy internals: guard must fail" "nonzero" setup_acc_qwy_violation teardown_acc_qwy_violation

# ─── Summary ─────────────────────────────────────────────────────────

echo ""
echo "═══════════════════════════════════════════════════════════════════"
if [ "$fail" -eq 0 ]; then
    echo "  SELFTEST: ALL $total CASES PASSED ✅ ($pass/$total)"
    echo "  The guard detects violations and passes clean code."
else
    echo "  SELFTEST: $fail / $total CASES FAILED ❌"
    echo "  The guard has false positives or false negatives."
fi
echo "═══════════════════════════════════════════════════════════════════"

exit "$fail"
