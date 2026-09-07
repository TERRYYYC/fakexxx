#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
checker="$repo_root/scripts/check-host-verification-device-isolation.sh"
fixtures="$repo_root/scripts/fixtures"

passed=0
failed=0

expect_pass() {
  label="$1"
  shift
  if "$@"; then
    passed=$((passed + 1))
  else
    printf 'expected host-isolation pass failed: %s\n' "$label" >&2
    failed=$((failed + 1))
  fi
}

expect_fail() {
  label="$1"
  shift
  if "$@" >/dev/null 2>&1; then
    printf 'expected host-isolation rejection did not occur: %s\n' "$label" >&2
    failed=$((failed + 1))
  else
    passed=$((passed + 1))
  fi
}

expect_pass good bash "$checker" "$fixtures/host-verification-good.sh"
expect_fail install bash "$checker" "$fixtures/host-verification-gradle-install.sh"
expect_fail connected-test bash "$checker" "$fixtures/host-verification-connected-test.sh"
expect_fail wrapped-install bash "$checker" "$fixtures/host-verification-wrapped-install.sh"
expect_fail connected-check bash "$checker" "$fixtures/host-verification-connected-check.sh"
expect_fail yaml-folded-install bash "$checker" "$fixtures/host-verification-yaml-folded-install.yml"
expect_fail path-wrapper bash "$checker" "$fixtures/host-verification-path-wrapper-install.sh"
expect_fail root-qualified bash "$checker" "$fixtures/host-verification-root-qualified-install.sh"
expect_fail nested-qualified bash "$checker" "$fixtures/host-verification-nested-qualified-connected.sh"
expect_fail yaml-sequence-folded-connected bash "$checker" "$fixtures/host-verification-yaml-sequence-folded-connected.yml"
expect_fail single-quoted-install bash "$checker" "$fixtures/host-verification-single-quoted-install.sh"
expect_fail double-quoted-connected bash "$checker" "$fixtures/host-verification-double-quoted-connected.sh"
expect_pass yaml-sequence-folded-host-siblings bash "$checker" "$fixtures/host-verification-yaml-sequence-folded-host-siblings.yml"
expect_pass good-no-rg env PATH="/usr/bin:/bin" bash "$checker" "$fixtures/host-verification-good.sh"
expect_fail install-no-rg env PATH="/usr/bin:/bin" bash "$checker" "$fixtures/host-verification-gradle-install.sh"
expect_fail connected-test-no-rg env PATH="/usr/bin:/bin" bash "$checker" "$fixtures/host-verification-connected-test.sh"
expect_fail wrapped-install-no-rg env PATH="/usr/bin:/bin" bash "$checker" "$fixtures/host-verification-wrapped-install.sh"
expect_fail connected-check-no-rg env PATH="/usr/bin:/bin" bash "$checker" "$fixtures/host-verification-connected-check.sh"
expect_fail yaml-folded-install-no-rg env PATH="/usr/bin:/bin" bash "$checker" "$fixtures/host-verification-yaml-folded-install.yml"
expect_fail path-wrapper-no-rg env PATH="/usr/bin:/bin" bash "$checker" "$fixtures/host-verification-path-wrapper-install.sh"
expect_fail root-qualified-no-rg env PATH="/usr/bin:/bin" bash "$checker" "$fixtures/host-verification-root-qualified-install.sh"
expect_fail nested-qualified-no-rg env PATH="/usr/bin:/bin" bash "$checker" "$fixtures/host-verification-nested-qualified-connected.sh"
expect_fail yaml-sequence-folded-connected-no-rg env PATH="/usr/bin:/bin" bash "$checker" "$fixtures/host-verification-yaml-sequence-folded-connected.yml"
expect_fail single-quoted-install-no-rg env PATH="/usr/bin:/bin" bash "$checker" "$fixtures/host-verification-single-quoted-install.sh"
expect_fail double-quoted-connected-no-rg env PATH="/usr/bin:/bin" bash "$checker" "$fixtures/host-verification-double-quoted-connected.sh"
expect_pass yaml-sequence-folded-host-siblings-no-rg env PATH="/usr/bin:/bin" bash "$checker" "$fixtures/host-verification-yaml-sequence-folded-host-siblings.yml"

# The aggregate verifier must fail during preflight, before even a harmless
# Gradle wrapper is invoked.  This is a deliberately minimal sandbox: only the
# verifier, checker, workflow text and harmless wrappers are present.  The
# positive control proves the satisfied toolchain reaches a wrapper; the
# negative control proves a discovered forbidden task stops before it.
sandbox="$(mktemp -d)"
cleanup() { rm -rf "$sandbox"; }
trap cleanup EXIT
mkdir -p "$sandbox/apps/cellrebel-auto" "$sandbox/apps/qianwangyou" "$sandbox/.github/workflows" "$sandbox/scripts"
cp "$repo_root/scripts/verify-a-plus.sh" "$sandbox/scripts/verify-a-plus.sh"
cp "$repo_root/scripts/check-host-verification-device-isolation.sh" "$sandbox/scripts/check-host-verification-device-isolation.sh"
cp "$repo_root/.github/workflows/android-a-plus.yml" "$sandbox/.github/workflows/android-a-plus.yml"
for app in cellrebel-auto qianwangyou; do
  cp "$fixtures/host-verification-gradle-stub.sh" "$sandbox/apps/$app/gradlew"
  chmod +x "$sandbox/apps/$app/gradlew"
done
for hostile_target in \
  "$fixtures/host-verification-wrapped-install.sh" \
  "$fixtures/host-verification-yaml-sequence-folded-connected.yml" \
  "$fixtures/host-verification-single-quoted-install.sh" \
  "$fixtures/host-verification-double-quoted-connected.sh"; do
  : > "$sandbox/gradle-invocations.log"
  if ANDROID_HOME="$sandbox/android-sdk" \
    HOST_VERIFICATION_ADDITIONAL_TARGETS="$hostile_target" \
    VERIFY_A_PLUS_SKIP_HOST_ISOLATION_SELFTEST=1 \
    VERIFY_A_PLUS_STUB_LOG="$sandbox/gradle-invocations.log" \
    bash "$sandbox/scripts/verify-a-plus.sh" --stage import >/dev/null 2>&1; then
    printf 'aggregate verifier accepted a forbidden preflight target: %s\n' "$hostile_target" >&2
    failed=$((failed + 1))
  elif [ -s "$sandbox/gradle-invocations.log" ]; then
    printf 'aggregate verifier invoked a Gradle wrapper after failed preflight: %s\n' "$hostile_target" >&2
    failed=$((failed + 1))
  else
    passed=$((passed + 1))
  fi
done

: > "$sandbox/gradle-invocations.log"
ANDROID_HOME="$sandbox/android-sdk" \
  HOST_VERIFICATION_ADDITIONAL_TARGETS="$fixtures/host-verification-yaml-sequence-folded-host-siblings.yml" \
  VERIFY_A_PLUS_SKIP_HOST_ISOLATION_SELFTEST=1 \
  VERIFY_A_PLUS_STUB_LOG="$sandbox/gradle-invocations.log" \
  bash "$sandbox/scripts/verify-a-plus.sh" --stage import >/dev/null 2>&1 || true
if [ -s "$sandbox/gradle-invocations.log" ]; then
  passed=$((passed + 1))
else
  printf 'aggregate verifier did not reach a harmless wrapper after a safe folded workflow preflight\n' >&2
  failed=$((failed + 1))
fi

: > "$sandbox/gradle-invocations.log"
ANDROID_HOME="$sandbox/android-sdk" \
  VERIFY_A_PLUS_SKIP_HOST_ISOLATION_SELFTEST=1 \
  VERIFY_A_PLUS_STUB_LOG="$sandbox/gradle-invocations.log" \
  bash "$sandbox/scripts/verify-a-plus.sh" --stage import >/dev/null 2>&1 || true
if [ -s "$sandbox/gradle-invocations.log" ]; then
  passed=$((passed + 1))
else
  printf 'aggregate verifier did not reach a harmless wrapper after safe preflight\n' >&2
  failed=$((failed + 1))
fi

printf 'host-verification-device-isolation: %d passed, %d failed\n' "$passed" "$failed"
[ "$failed" -eq 0 ]
