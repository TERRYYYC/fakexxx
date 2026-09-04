#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
auto_wrapper="$repo_root/apps/cellrebel-auto/gradlew"
qwy_wrapper="$repo_root/apps/qianwangyou/gradlew"

write_receipt_atomically() {
  local payload="$1"
  receipt_tmp="$receipt_path.tmp.$$"
  printf '%s\n' "$payload" >"$receipt_tmp"
  mv -f "$receipt_tmp" "$receipt_path" || {
    local move_rc=$?
    rm -f "$receipt_tmp"
    receipt_tmp=""
    return "$move_rc"
  }
  receipt_tmp=""
}

cleanup_host_gate_lock() {
  if [[ -n "${receipt_tmp:-}" ]]; then
    /bin/rm -f -- "$receipt_tmp"
    receipt_tmp=""
  fi
  if [[ "${lock_owned:-0}" -eq 1 && "${lock_releasable:-0}" -eq 1 ]]; then
    local current_owner=""
    if [[ -f "$lock_owner_path" ]]; then
      current_owner="$(<"$lock_owner_path")"
    fi
    if [[ ! -e "$lock_owner_path" || "$current_owner" == "$run_owner" ]]; then
      /bin/rm -f -- "$lock_owner_path"
      rmdir "$lock_dir" 2>/dev/null || true
    fi
    lock_owned=0
  fi
}

if [[ "$#" -eq 0 ]]; then
  receipt_dir="$script_dir/harness/build/reports/pr63-on-issue66"
  mkdir -p "$receipt_dir"
  receipt_path="$receipt_dir/host-gate-receipt.json"
  lock_dir="$receipt_dir/host-gate.lock"
  lock_owner_path="$lock_dir/owner"
  run_owner="pid=$$;ppid=$PPID;nonce=$RANDOM$RANDOM"
  lock_owned=0
  lock_releasable=0
  receipt_tmp=""
  if ! mkdir "$lock_dir" 2>/dev/null; then
    echo "Host integration gate is already running; lock: $lock_dir" >&2
    exit 75
  fi
  lock_owned=1
  trap cleanup_host_gate_lock EXIT
  trap 'exit 129' HUP
  trap 'exit 130' INT
  trap 'exit 143' TERM

  # Until a RUNNING receipt is published, any early failure deliberately leaves
  # this owner lock behind as a fail-closed fence around an older receipt.
  printf '%s\n' "$run_owner" >"$lock_owner_path"
  /bin/rm -f -- "$receipt_path"
  [[ ! -e "$receipt_path" ]]
  running_receipt='{"schemaVersion":2,"hostIntegration":"RUNNING","issue66Ac7":"NOT_PASSED","emulator":"NOT_RUN","physicalDevice":"NOT_RUN","deviceFull":"BLOCKED","overall":"BLOCKED","reason":"HOST_GATE_RUNNING_NO_PASS_RECEIPT"}'
  write_receipt_atomically "$running_receipt"
  lock_releasable=1
fi

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
  bash "$repo_root/scripts/selftest-issue66-moto-readonly-collector.sh"
  bash "$repo_root/scripts/selftest-issue66-services-compatibility.sh"
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
  receipt='{"schemaVersion":2,"hostIntegration":"PASS","issue66Ac7":"NOT_PASSED","emulator":"NOT_RUN","physicalDevice":"NOT_RUN","deviceFull":"BLOCKED","overall":"BLOCKED","reason":"HOST_GATE_HAS_NO_DEVICE_EVIDENCE__BOTH_ADMISSION_LISTS_EMPTY__ACTIVATION_CLEANUP_REBOOTS_AND_ADVERSARIAL_MUTATIONS_REQUIRE_ADDITIONAL_AUTHORIZATION"}'
  write_receipt_atomically "$receipt"
  printf '%s\n' "$receipt"
  exit 0
fi

exec "$auto_wrapper" -p "$script_dir" "$@"
