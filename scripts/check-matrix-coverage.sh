#!/usr/bin/env bash
# check-matrix-coverage.sh — Machine-verifies that every canonical M-AD-* test ID
# referenced by the feature spec exists at the EXACT spec-mandated coordinate.
#
# Sol R2 P1-3/P1-4: the guard locks the exact file path prescribed by §10.1,
# not just any file containing the method name. A test at the wrong coordinate
# is not the test the spec references.
#
# Exit 0 = all IDs found at exact coordinate. Exit 1 = missing or wrong location.
# Used by: selftest-release-debt.sh (N-9) and CI workflow.
#
# # 矩阵覆盖检测：§10.1 prescribed coordinate + exact ID set
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Exact spec-mandated coordinate (§10.1 lines 2719-2724):
# apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/AdvanceMatrixTest.kt
EXACT_FILE="$REPO_ROOT/apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/AdvanceMatrixTest.kt"

if [ ! -f "$EXACT_FILE" ]; then
  echo "❌ MATRIX COVERAGE FAIL — spec-mandated file not found:"
  echo "   Expected: $EXACT_FILE"
  echo "   Reference: feature-specs/2026-08-09-cellrebel-qianwangyou-a-plus.md §10.1"
  exit 1
fi

# Canonical M-AD IDs from feature-specs/2026-08-09-cellrebel-qianwangyou-a-plus.md §10.1
REQUIRED_IDS=(
  M_AD_14
  M_AD_15
  M_AD_16
  M_AD_17
  M_AD_18
  M_AD_19
)

MISSING=()
for id in "${REQUIRED_IDS[@]}"; do
  if ! grep -q "fun ${id}()" "$EXACT_FILE"; then
    MISSING+=("$id")
  fi
done

if [ ${#MISSING[@]} -gt 0 ]; then
  echo "❌ MATRIX COVERAGE FAIL — missing test IDs at exact coordinate:"
  for id in "${MISSING[@]}"; do
    echo "   • $id"
  done
  echo ""
  echo "File: $EXACT_FILE"
  echo "Reference: feature-specs/2026-08-09-cellrebel-qianwangyou-a-plus.md §10.1"
  exit 1
fi

# Verify package declaration matches spec coordinate
if ! grep -q "^package com.example.cellrebelauto.matrix" "$EXACT_FILE"; then
  echo "❌ MATRIX COVERAGE FAIL — wrong package declaration"
  echo "   Expected: package com.example.cellrebelauto.matrix"
  echo "   File: $EXACT_FILE"
  exit 1
fi

echo "✅ MATRIX COVERAGE OK — all ${#REQUIRED_IDS[@]} M-AD IDs verified at exact coordinate:"
for id in "${REQUIRED_IDS[@]}"; do
  echo "   ✓ $id → matrix/AdvanceMatrixTest.kt"
done
exit 0
