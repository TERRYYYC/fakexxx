#!/usr/bin/env bash
# check-forbidden-boundaries.sh — §10.1 static-guard for the acceptance module (#6).
#
# Evidence class: `static-guard` (no runtime component).
# Rows: M-BP-01, M-BP-02 (§10.1 ledger, bypass category).
# Invariants: INV-01 (qwy = sole authority), INV-20 (Auto ≠ write qwy storage / UI-automate qwy).
#
# The iron rule: the acceptance module (fake-qwy + scenarios) must share ZERO
# code with either app's production implementation. The only common ancestor
# is :environment-control-v1 (the contract module).
#
# This script is one of FIVE projections of the same fact (§0.1.28):
#   1. owner column in §10.1
#   2. evidence class table "who writes" column
#   3. §12.1 owner matrix
#   4. directory tree annotations
#   5. THIS script's input/output
# Any two inconsistent → fail-closed.
#
# Entry points (per §10.1 ledger):
#   check-forbidden-boundaries.sh::M-BP-01  (Auto ≠ write qwy prefs/DB)
#   check-forbidden-boundaries.sh::M-BP-02  (Auto ≠ Accessibility-target qwy)
#
# Evidence manifest output: acceptance/build/matrix-evidence-guard.json
#
# Run from the repo root:
#   bash acceptance/scripts/check-forbidden-boundaries.sh
#
# Exit 0 = clean, exit 1+ = forbidden boundary crossed.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ACCEPTANCE_DIR="$REPO_ROOT/acceptance"
AUTO_SRC="$REPO_ROOT/apps/cellrebel-auto/app/src"
QWY_PACKAGE="name.caiyao.fakegps"

# Source directories to scan
FAKE_QWY_SRC="$ACCEPTANCE_DIR/fake-qwy/src"
SCENARIOS_SRC="$ACCEPTANCE_DIR/scenarios/src"

violations=0
total_checks=0
manifest_entries=()

# ─── Helpers ─────────────────────────────────────────────────────────

check_pattern() {
    local row_id="$1"
    local label="$2"
    local pattern="$3"
    local description="$4"
    shift 4
    local -a scan_dirs=("$@")

    total_checks=$((total_checks + 1))

    local hits=""
    for dir in "${scan_dirs[@]}"; do
        if [ -d "$dir" ]; then
            local dir_hits
            dir_hits=$(grep -rn "$pattern" "$dir" \
                --include="*.kt" --include="*.java" 2>/dev/null || true)
            if [ -n "$dir_hits" ]; then
                hits="${hits}${dir_hits}"$'\n'
            fi
        fi
    done
    hits=$(echo "$hits" | sed '/^$/d')

    if [ -n "$hits" ]; then
        echo "❌ FAIL [$label]: $description"
        echo "   Row: $row_id"
        echo "   Forbidden pattern: $pattern"
        echo "   Violations:"
        echo "$hits" | while IFS= read -r line; do
            echo "     $line"
        done
        violations=$((violations + 1))
        return 1
    else
        echo "✅ PASS [$label]: $description"
        return 0
    fi
}

check_pattern_xml() {
    local row_id="$1"
    local label="$2"
    local pattern="$3"
    local description="$4"
    shift 4
    local -a scan_dirs=("$@")

    total_checks=$((total_checks + 1))

    local hits=""
    for dir in "${scan_dirs[@]}"; do
        if [ -d "$dir" ]; then
            local dir_hits
            dir_hits=$(grep -rn "$pattern" "$dir" \
                --include="*.kt" --include="*.java" --include="*.xml" 2>/dev/null || true)
            if [ -n "$dir_hits" ]; then
                hits="${hits}${dir_hits}"$'\n'
            fi
        fi
    done
    hits=$(echo "$hits" | sed '/^$/d')

    if [ -n "$hits" ]; then
        echo "❌ FAIL [$label]: $description"
        echo "   Row: $row_id"
        echo "   Forbidden pattern: $pattern"
        echo "   Violations:"
        echo "$hits" | while IFS= read -r line; do
            echo "     $line"
        done
        violations=$((violations + 1))
        return 1
    else
        echo "✅ PASS [$label]: $description"
        return 0
    fi
}

check_gradle_dep() {
    local label="$1"
    local description="$2"
    local pattern="$3"
    shift 3
    local -a files=("$@")

    total_checks=$((total_checks + 1))

    local hits=""
    for f in "${files[@]}"; do
        if [ -f "$f" ]; then
            local f_hits
            f_hits=$(grep -n "$pattern" "$f" 2>/dev/null || true)
            if [ -n "$f_hits" ]; then
                hits="${hits}${f}:${f_hits}"$'\n'
            fi
        fi
    done
    hits=$(echo "$hits" | sed '/^$/d')

    if [ -n "$hits" ]; then
        echo "❌ FAIL [$label]: $description"
        echo "   Violations:"
        echo "$hits" | while IFS= read -r line; do
            echo "     $line"
        done
        violations=$((violations + 1))
        return 1
    else
        echo "✅ PASS [$label]: $description"
        return 0
    fi
}

add_manifest_entry() {
    local row_id="$1"
    local status="$2"
    manifest_entries+=("{\"rowId\":\"$row_id\",\"status\":\"$status\",\"lane\":\"static-guard\",\"testId\":\"check-forbidden-boundaries.sh::$row_id\"}")
}

# ─── Header ──────────────────────────────────────────────────────────

echo "═══════════════════════════════════════════════════════════════════"
echo "  check-forbidden-boundaries.sh — §10.1 static-guard"
echo "  Rows: M-BP-01, M-BP-02"
echo "  Invariants: INV-01, INV-20"
echo ""
echo "  Scanning:"
echo "    Auto:       $AUTO_SRC"
echo "    fake-qwy:   $FAKE_QWY_SRC"
echo "    scenarios:   $SCENARIOS_SRC"
echo "═══════════════════════════════════════════════════════════════════"
echo ""

# ═══════════════════════════════════════════════════════════════════════
# M-BP-01: Auto 直接写 qwy prefs/DB → 静态 guard 失败
#
# INV-01: qianwangyou is the sole authority for Hook/System Mock/profile/schedule.
# INV-20: Auto must NOT write qianwangyou's storage.
#
# Forbidden: Auto source code importing qwy's internal packages,
#            or accessing qwy's SharedPreferences/ContentProvider/DB.
# ═══════════════════════════════════════════════════════════════════════

echo "── M-BP-01: Auto ≠ write qwy prefs/DB (INV-01, INV-20) ──────────"
echo ""

bp01_pass=true

# Check 1: Auto source must NOT import qwy's internal package
if ! check_pattern "M-BP-01" "BP-01.1" \
    "import ${QWY_PACKAGE//./\\.}" \
    "Auto source must NOT import qwy internals (${QWY_PACKAGE}.*)" \
    "$AUTO_SRC/main" "$AUTO_SRC/test"; then
    bp01_pass=false
fi

# Check 2: Auto source must NOT reference qwy's internal storage classes
#           (SharedPreferences targeting qwy, ContentResolver for qwy's provider)
if ! check_pattern "M-BP-01" "BP-01.2" \
    "${QWY_PACKAGE//./\\.}\.provider\.\|${QWY_PACKAGE//./\\.}\.storage\.\|${QWY_PACKAGE//./\\.}\.db\." \
    "Auto source must NOT reference qwy's provider/storage/db packages" \
    "$AUTO_SRC/main" "$AUTO_SRC/test"; then
    bp01_pass=false
fi

# Check 3: Auto source must NOT have a ContentProvider authority targeting qwy
if ! check_pattern "M-BP-01" "BP-01.3" \
    "\"${QWY_PACKAGE//./\\.}\..*provider\"\|\"content://${QWY_PACKAGE//./\\.}" \
    "Auto source must NOT reference qwy's ContentProvider authority" \
    "$AUTO_SRC/main" "$AUTO_SRC/test"; then
    bp01_pass=false
fi

echo ""

if $bp01_pass; then
    add_manifest_entry "M-BP-01" "PASS"
else
    add_manifest_entry "M-BP-01" "FAIL"
fi

# ═══════════════════════════════════════════════════════════════════════
# M-BP-02: Auto 用 Accessibility 操作千网游 → package target guard 失败
#
# INV-01: qianwangyou is the sole authority.
# INV-20: Auto must NOT use UI automation to call qianwangyou.
#
# Forbidden: Auto's Accessibility code targeting qwy's package name
#            for UI automation (launching, clicking, controlling qwy).
# ═══════════════════════════════════════════════════════════════════════

echo "── M-BP-02: Auto ≠ Accessibility-target qwy (INV-01, INV-20) ────"
echo ""

bp02_pass=true

# Check 1: Auto's accessibility service config must NOT have qwy as a target package
if ! check_pattern_xml "M-BP-02" "BP-02.1" \
    "android:packageNames=\".*${QWY_PACKAGE//./\\.}" \
    "Accessibility service config must NOT target qwy package" \
    "$AUTO_SRC/main/res"; then
    bp02_pass=false
fi

# Check 2: Auto's Kotlin/Java source must NOT launch or target qwy via Accessibility
#           (check for qwy package name as an Accessibility/launch target in code)
if ! check_pattern "M-BP-02" "BP-02.2" \
    "\"${QWY_PACKAGE//./\\.}\"" \
    "Auto Kotlin/Java source must NOT reference qwy package as a string literal (Accessibility/launch target)" \
    "$AUTO_SRC/main/java" "$AUTO_SRC/test/java"; then
    bp02_pass=false
fi

# Check 3: Auto must NOT have a Handler/Bridge class specifically for qwy
if ! check_pattern "M-BP-02" "BP-02.3" \
    "class.*QwyHandler\|class.*QianwanyouHandler\|class.*CaiyaoHandler" \
    "Auto must NOT have an Accessibility handler class for qwy" \
    "$AUTO_SRC/main/java" "$AUTO_SRC/test/java"; then
    bp02_pass=false
fi

echo ""

if $bp02_pass; then
    add_manifest_entry "M-BP-02" "PASS"
else
    add_manifest_entry "M-BP-02" "FAIL"
fi

# ═══════════════════════════════════════════════════════════════════════
# Acceptance module boundary: acceptance/ must only depend on the
# public v1 contract (:environment-control-v1), never on app internals.
# ═══════════════════════════════════════════════════════════════════════

echo "── Acceptance boundary: no app-internal imports ──────────────────"
echo ""

# Guard A: No qwy app-internal imports in acceptance
check_pattern "BOUNDARY" "ACC-01" \
    "import ${QWY_PACKAGE//./\\.}" \
    "Acceptance must NOT import qwy internals (${QWY_PACKAGE}.*)" \
    "$FAKE_QWY_SRC" "$SCENARIOS_SRC" || true

# Guard B: No Auto app-internal imports in acceptance
check_pattern "BOUNDARY" "ACC-02" \
    "import com\.example\.cellrebelauto" \
    "Acceptance must NOT import Auto internals (com.example.cellrebelauto.*)" \
    "$FAKE_QWY_SRC" "$SCENARIOS_SRC" || true

# Guard C: No references to qwy integration.v1 production package
check_pattern "BOUNDARY" "ACC-03" \
    "${QWY_PACKAGE//./\\.}\.integration\.v1\." \
    "Acceptance must NOT reference qwy integration.v1 production package" \
    "$FAKE_QWY_SRC" "$SCENARIOS_SRC" || true

# Guard D: No references to Auto package (even without 'import')
check_pattern "BOUNDARY" "ACC-04" \
    "com\.example\.cellrebelauto\." \
    "Acceptance must NOT reference Auto package" \
    "$FAKE_QWY_SRC" "$SCENARIOS_SRC" || true

echo ""
echo "── Acceptance boundary: no #4 test support references ────────────"
echo ""

# Guard E: No #4 production test doubles
check_pattern "BOUNDARY" "ACC-05" \
    "ProviderHarness\|InMemoryDurableKv\|FakeMonotonicClock\|FakeQwyEnvironment" \
    "Acceptance must NOT reference #4's production test doubles" \
    "$FAKE_QWY_SRC" "$SCENARIOS_SRC" || true

echo ""
echo "── Acceptance boundary: Gradle dependency whitelist ──────────────"
echo ""

# Guard F: No Gradle dependency on app modules
check_gradle_dep "ACC-06" \
    "No Gradle dependency on app modules" \
    'project(":app")\|project(":qianwangyou")\|project(":cellrebel-auto")' \
    "$ACCEPTANCE_DIR/fake-qwy/build.gradle.kts" \
    "$ACCEPTANCE_DIR/scenarios/build.gradle.kts" || true

# Guard G: Only allowed project dependencies
total_checks=$((total_checks + 1))
other_deps=$(grep -E 'project\(":[^"]+"\)' \
    "$ACCEPTANCE_DIR/fake-qwy/build.gradle.kts" \
    "$ACCEPTANCE_DIR/scenarios/build.gradle.kts" 2>/dev/null \
    | grep -v ':environment-control-v1' \
    | grep -v ':fake-qwy' \
    | grep -v ':scenarios' \
    || true)

if [ -n "$other_deps" ]; then
    echo "❌ FAIL [ACC-07]: acceptance/ has unexpected project dependencies"
    echo "   Violations:"
    echo "$other_deps" | while IFS= read -r line; do
        echo "     $line"
    done
    violations=$((violations + 1))
else
    echo "✅ PASS [ACC-07]: acceptance/ only depends on allowed modules"
fi

# ─── Evidence manifest ───────────────────────────────────────────────

echo ""
echo "── Writing evidence manifest ─────────────────────────────────────"
echo ""

MANIFEST_DIR="$ACCEPTANCE_DIR/build"
mkdir -p "$MANIFEST_DIR"

# Get current HEAD
EXACT_HEAD=$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo "unknown")

# Build JSON
{
    echo "["
    for i in "${!manifest_entries[@]}"; do
        entry="${manifest_entries[$i]}"
        # Inject exactHead into each entry
        entry="${entry%\}},\"exactHead\":\"$EXACT_HEAD\"}"
        if [ "$i" -lt $((${#manifest_entries[@]} - 1)) ]; then
            echo "  ${entry},"
        else
            echo "  ${entry}"
        fi
    done
    echo "]"
} > "$MANIFEST_DIR/matrix-evidence-guard.json"

echo "  Written: $MANIFEST_DIR/matrix-evidence-guard.json"

# ─── Summary ─────────────────────────────────────────────────────────

echo ""
echo "═══════════════════════════════════════════════════════════════════"
if [ "$violations" -eq 0 ]; then
    echo "  RESULT: ALL $total_checks CHECKS PASSED ✅"
    echo "  M-BP-01: PASS (Auto ≠ write qwy prefs/DB)"
    echo "  M-BP-02: PASS (Auto ≠ Accessibility-target qwy)"
    echo "  Acceptance boundary: CLEAN"
else
    echo "  RESULT: $violations / $total_checks CHECKS FAILED ❌"
    echo "  Fix the violations above before merging."
fi
echo "═══════════════════════════════════════════════════════════════════"

exit "$violations"
