#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
auto_wrapper="$repo_root/apps/cellrebel-auto/gradlew"
qwy_wrapper="$repo_root/apps/qianwangyou/gradlew"

for pinned_wrapper in "$auto_wrapper" "$qwy_wrapper"; do
  if [[ ! -x "$pinned_wrapper" ]]; then
    echo "Pinned repository Gradle wrapper is unavailable: $pinned_wrapper" >&2
    exit 1
  fi
done
if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "JAVA_HOME must point to a JDK 17 runtime." >&2
  exit 1
fi
if [[ -z "${ANDROID_HOME:-}" ]]; then
  echo "ANDROID_HOME must point to the Android SDK." >&2
  exit 1
fi

if [[ "$#" -eq 0 ]]; then
  "$auto_wrapper" -p "$repo_root/apps/cellrebel-auto" \
    :app:testDebugUnitTest \
    --tests '*ProviderPrincipalRoutingRedTest'
  "$qwy_wrapper" -p "$repo_root/apps/qianwangyou" \
    :app:testDebugUnitTest \
    --tests '*Android15OracleHookPlanTest' \
    --tests '*SystemServerOracleWiringGuardTest' \
    --tests '*AuthoritativeOracleProductionGuardTest' \
    --tests '*AuthoritativeAdvanceProviderTest'
  "$auto_wrapper" -p "$script_dir" :harness:testDebugUnitTest
  echo "HOST integration gate: PASS"
  echo "DEVICE/FULL evidence: BLOCKED (no device run; production fingerprint allowlist stays empty)"
  echo "OVERALL: BLOCKED pending the separately authorized device gate"
  receipt_dir="$script_dir/harness/build/reports/pr63-on-issue66"
  mkdir -p "$receipt_dir"
  receipt='{"schemaVersion":1,"hostIntegration":"PASS","issue66Ac7":"NOT_PASSED","emulator":"NOT_RUN","physicalDevice":"BLOCKED_NO_AUTHORIZATION","deviceFull":"BLOCKED","overall":"BLOCKED","reason":"NO_DEVICE_RUN_AND_PRODUCTION_FINGERPRINT_ALLOWLIST_EMPTY"}'
  printf '%s\n' "$receipt" | tee "$receipt_dir/host-gate-receipt.json"
  exit 0
fi

exec "$auto_wrapper" -p "$script_dir" "$@"
