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
expect_fail bash "$checker" "$fixtures/host-verification-wrapped-install.sh"
expect_fail bash "$checker" "$fixtures/host-verification-connected-check.sh"
expect_fail bash "$checker" "$fixtures/host-verification-yaml-folded-install.yml"
expect_pass env PATH="/usr/bin:/bin" bash "$checker" "$fixtures/host-verification-good.sh"
expect_fail env PATH="/usr/bin:/bin" bash "$checker" "$fixtures/host-verification-gradle-install.sh"
expect_fail env PATH="/usr/bin:/bin" bash "$checker" "$fixtures/host-verification-connected-test.sh"
expect_fail env PATH="/usr/bin:/bin" bash "$checker" "$fixtures/host-verification-wrapped-install.sh"
expect_fail env PATH="/usr/bin:/bin" bash "$checker" "$fixtures/host-verification-connected-check.sh"
expect_fail env PATH="/usr/bin:/bin" bash "$checker" "$fixtures/host-verification-yaml-folded-install.yml"

# The aggregate verifier must fail during preflight, before even a harmless
# Gradle wrapper is invoked.  Run an isolated copy with wrappers that merely
# append to a log; a non-empty log would prove the fail-fast contract false.
sandbox="$(mktemp -d)"
cleanup() { rm -rf "$sandbox"; }
trap cleanup EXIT
mkdir -p "$sandbox/apps/cellrebel-auto" "$sandbox/apps/qianwangyou" "$sandbox/.github/workflows"
cp -R "$repo_root/scripts" "$sandbox/scripts"
cp "$repo_root/.github/workflows/android-a-plus.yml" "$sandbox/.github/workflows/android-a-plus.yml"
for app in cellrebel-auto qianwangyou; do
  cp "$fixtures/host-verification-gradle-stub.sh" "$sandbox/apps/$app/gradlew"
  chmod +x "$sandbox/apps/$app/gradlew"
done
: > "$sandbox/gradle-invocations.log"
if HOST_VERIFICATION_ADDITIONAL_TARGETS="$sandbox/scripts/fixtures/host-verification-wrapped-install.sh" \
  VERIFY_A_PLUS_SKIP_HOST_ISOLATION_SELFTEST=1 \
  VERIFY_A_PLUS_STUB_LOG="$sandbox/gradle-invocations.log" \
  bash "$sandbox/scripts/verify-a-plus.sh" --stage import >/dev/null 2>&1; then
  failed=$((failed + 1))
elif [ -s "$sandbox/gradle-invocations.log" ]; then
  failed=$((failed + 1))
else
  passed=$((passed + 1))
fi

printf 'host-verification-device-isolation: %d passed, %d failed\n' "$passed" "$failed"
[ "$failed" -eq 0 ]
