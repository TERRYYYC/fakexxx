#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
checker="$repo_root/scripts/check-host-verification-device-isolation.sh"
fixtures="$repo_root/scripts/fixtures"

passed=0
failed=0

expect_pass() {
  if "$@"; then
    passed=$((passed + 1))
  else
    failed=$((failed + 1))
  fi
}

expect_fail() {
  if "$@" >/dev/null 2>&1; then
    failed=$((failed + 1))
  else
    passed=$((passed + 1))
  fi
}

expect_pass bash "$checker" "$fixtures/host-verification-good.sh"
expect_fail bash "$checker" "$fixtures/host-verification-gradle-install.sh"
expect_fail bash "$checker" "$fixtures/host-verification-connected-test.sh"
expect_pass env PATH="/usr/bin:/bin" bash "$checker" "$fixtures/host-verification-good.sh"
expect_fail env PATH="/usr/bin:/bin" bash "$checker" "$fixtures/host-verification-gradle-install.sh"
expect_fail env PATH="/usr/bin:/bin" bash "$checker" "$fixtures/host-verification-connected-test.sh"

printf 'host-verification-device-isolation: %d passed, %d failed\n' "$passed" "$failed"
[ "$failed" -eq 0 ]
