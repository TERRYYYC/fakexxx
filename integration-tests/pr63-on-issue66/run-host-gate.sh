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
    --tests '*BinderAuthoritativeContinuitySourceTest' \
    --tests '*OracleBundleCodecTest' \
    --tests '*AuthoritativeAdvanceProviderTest'
  "$auto_wrapper" -p "$script_dir" :harness:testDebugUnitTest
  echo "HOST integration gate: PASS"
  echo "PHYSICAL DEVICE: NOT_RUN (this host gate emits no device evidence)"
  echo "DEVICE/FULL evidence: BLOCKED (both exact-build admission lists stay empty)"
  echo "OVERALL: BLOCKED pending additional authorization for activation/cleanup reboots and adversarial mutations"
  receipt_dir="$script_dir/harness/build/reports/pr63-on-issue66"
  mkdir -p "$receipt_dir"
  receipt='{"schemaVersion":2,"hostIntegration":"PASS","issue66Ac7":"NOT_PASSED","emulator":"NOT_RUN","physicalDevice":"NOT_RUN","deviceFull":"BLOCKED","overall":"BLOCKED","reason":"HOST_GATE_HAS_NO_DEVICE_EVIDENCE__BOTH_ADMISSION_LISTS_EMPTY__ACTIVATION_CLEANUP_REBOOTS_AND_ADVERSARIAL_MUTATIONS_REQUIRE_ADDITIONAL_AUTHORIZATION"}'
  printf '%s\n' "$receipt" | tee "$receipt_dir/host-gate-receipt.json"
  exit 0
fi

exec "$auto_wrapper" -p "$script_dir" "$@"
