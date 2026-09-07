#!/usr/bin/env bash
# selftest-provision-device.sh — device-free selftest for scripts/provision-device.sh.
#
# Proves, WITHOUT any adb device:
#   1. the provisioner exists and parses args strictly (rc 2 on bad serial/lane/step);
#   2. --dry-run prints the complete cold-path plan (routing + per-step commands)
#      and executes ZERO adb commands (a trap adb on PATH logs every invocation
#      and exits 99 — the log must stay empty);
#   3. the lane->package routing table is correct for all four lanes
#      (glmbench/bench/codexbench suffixed, release bare, single source);
#   4. golden dry-run output for the glmbench and release lanes is byte-stable
#      (golden output covers both lanes as required);
#   5. accessibility is the LAST device-touching step of the plan;
#   6. --step NAME runs/plans exactly one step;
#   7. the selftest itself is re-runnable (mktemp workdir + trap cleanup, no state).
#
# Style reference: scripts/selftest-install-apk-verified.sh.
# Exit 0 = all cases pass; anything else = failure.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="$HERE/provision-device.sh"

pass=0
fail=0

report() { # status name detail
    if [ "$1" = "ok" ]; then
        printf 'ok   %s\n' "$2"
        pass=$((pass + 1))
    else
        printf 'FAIL %s :: %s\n' "$2" "$3"
        fail=$((fail + 1))
    fi
}

if [ ! -f "$TARGET" ]; then
    echo "selftest target missing: $TARGET (write the provisioner, not just the test)"
    exit 1
fi

# ------------------------------------------------------------------ harness ---
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/tmp"
mkdir -p "$WORK/fixtures"

printf 'longitude,latitude,priority,required_successes\n116.10,39.90,1,3\n121.47,31.23,2,2\n' >"$WORK/fixtures/location_test_plan.glmbench.csv"
printf 'addname,latitude,longitude\nloc-01,39.90,116.10\nloc-02,31.23,121.47\n' >"$WORK/fixtures/qwy_profiles_glmbench.csv"

# Trap adb: dry-run must NEVER reach adb. Log every invocation and fail loudly.
cat >"$WORK/adb" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$(dirname "$0")/adb-invocations.log"
echo "selftest trap adb: invoked with: $* (dry-run must not call adb)" >&2
exit 99
EOF
chmod +x "$WORK/adb"

run_provision() { # args... -> sets OUT / RC (cwd+PATH+TMPDIR pinned to WORK)
    OUT="$(cd "$WORK" && PATH="$WORK:$PATH" TMPDIR="$WORK/tmp" "$TARGET" "$@" 2>&1)"
    RC=$?
}

# strip the mktemp dir so goldens are location-independent
cleanse() { # text
    printf '%s' "$1" | sed "s|$WORK|<WORK>|g"
}

assert_no_adb() { # label
    if [ -f "$WORK/adb-invocations.log" ]; then
        report fail "$1 (no adb in dry-run)" "trap adb was invoked: $(head -3 "$WORK/adb-invocations.log")"
        rm -f "$WORK/adb-invocations.log"
    else
        report ok "$1 (no adb in dry-run)"
    fi
}

# accessibility must be the last device-touching step: every step header 1..8
# precedes the [9/10] accessibility header, [10/10] report follows it, and NO
# adb command line appears at or after the report header.
assert_a11y_last() { # label plan-text
    if printf '%s\n' "$2" | awk '
        /^[[:space:]]*\[[0-9]+\/10\][[:space:]]/ {
            hdr = $0
            sub(/^[[:space:]]*\[/, "", hdr)
            sub(/\/10\].*/, "", hdr)
            n = hdr + 0
            if (n >= 1 && n <= 8) before_a11y[n] = 1
            if (n == 9)  a11y_line = NR
            if (n == 10) rep_line  = NR
        }
        /adb -s/ { last_adb = NR }
        END {
            if (!a11y_line || !rep_line) exit 1
            if (rep_line <= a11y_line) exit 1
            for (k = 1; k <= 8; k++) if (!(k in before_a11y)) exit 1
            if (last_adb > rep_line) exit 1
            exit 0
        }'; then
        report ok "$1 (accessibility is the last device-touching step)"
    else
        report fail "$1 (accessibility is the last device-touching step)" "plan order violation — see output"
    fi
}

assert_golden() { # label expected actual
    if [ "$3" = "$2" ]; then
        report ok "$1 (golden dry-run output)"
    else
        report fail "$1 (golden dry-run output)" "diff (< expected, > actual):"
        diff <(printf '%s\n' "$2") <(printf '%s\n' "$3") | head -30 >&2
    fi
}

# ---------------------------------------------------------------- syntax -----
bash -n "$TARGET" 2>&1 &&
    report ok "bash -n provision-device.sh" ||
    report fail "bash -n provision-device.sh" "syntax error"

# ------------------------------------------------- c1: glmbench dry run ------
FIX="$WORK/fixtures"
run_provision --serial FAKEDEV001 --lane glmbench --dry-run \
    --plan-csv "$FIX/location_test_plan.glmbench.csv" \
    --profile-csv "$FIX/qwy_profiles_glmbench.csv"
[ "$RC" -eq 0 ] &&
    report ok "c1 glmbench dry-run rc=0" || report fail "c1 glmbench dry-run rc=0" "rc=$RC out=$OUT"
assert_no_adb "c1 glmbench dry-run"
GOT_GLM="$(cleanse "$OUT")"

grep -q 'name\.caiyao\.fakegps\.glmbench' <<<"$GOT_GLM" &&
    report ok "c1 routes QWY glmbench package" || report fail "c1 routes QWY glmbench package" "$GOT_GLM"
grep -q 'com\.example\.cellrebelauto\.glmbench' <<<"$GOT_GLM" &&
    report ok "c1 routes Auto glmbench package" || report fail "c1 routes Auto glmbench package" "$GOT_GLM"
grep -q 'com\.cellrebel\.mobile/0' <<<"$GOT_GLM" &&
    report ok "c1 scope targets com.cellrebel.mobile/0" || report fail "c1 scope targets com.cellrebel.mobile/0" "$GOT_GLM"
grep -q 'AutomationService' <<<"$GOT_GLM" &&
    report ok "c1 plans the automation service" || report fail "c1 plans the automation service" "$GOT_GLM"
grep -q 'org\.matrix\.vector\.manager' <<<"$GOT_GLM" &&
    report ok "c1 plans vector manager install" || report fail "c1 plans vector manager install" "$GOT_GLM"
grep -q 'location_test_plan\.csv' <<<"$GOT_GLM" &&
    report ok "c1 renames plan csv to location_test_plan.csv" || report fail "c1 renames plan csv" "$GOT_GLM"
grep -q 'qwy_profiles_glmbench\.csv' <<<"$GOT_GLM" &&
    report ok "c1 keeps profile csv name" || report fail "c1 keeps profile csv name" "$GOT_GLM"
assert_a11y_last "c1" "$GOT_GLM"

# ------------------------------------------------- c2: release dry run -------
run_provision --serial FAKEDEV001 --lane release --dry-run \
    --plan-csv "$FIX/location_test_plan.glmbench.csv" \
    --profile-csv "$FIX/qwy_profiles_glmbench.csv"
[ "$RC" -eq 0 ] &&
    report ok "c2 release dry-run rc=0" || report fail "c2 release dry-run rc=0" "rc=$RC out=$OUT"
assert_no_adb "c2 release dry-run"
GOT_REL="$(cleanse "$OUT")"

grep -Eq 'qwy_pkg[[:space:]]+name\.caiyao\.fakegps$' <<<"$GOT_REL" &&
    report ok "c2 release QWY package has NO lane suffix" || report fail "c2 release QWY package has NO lane suffix" "$(grep qwy_pkg <<<"$GOT_REL")"
grep -Eq 'auto_pkg[[:space:]]+com\.example\.cellrebelauto$' <<<"$GOT_REL" &&
    report ok "c2 release Auto package has NO lane suffix" || report fail "c2 release Auto package has NO lane suffix" "$(grep auto_pkg <<<"$GOT_REL")"
# NOTE: only LANE suffixes count — com.example.cellrebelauto.automation.AutomationService
# (a11y class, always dot-suffixed) and the fixture CSV names are not lane packages.
if grep -qE 'name\.caiyao\.fakegps\.(glmbench|bench|codexbench)|com\.example\.cellrebelauto\.(glmbench|bench|codexbench)' <<<"$GOT_REL"; then
    report fail "c2 release plan is suffix-free" "found lane-suffixed package in release plan"
else
    report ok "c2 release plan is suffix-free"
fi
assert_a11y_last "c2" "$GOT_REL"

# ------------------------------------------------- c3: bench + codexbench ----
run_provision --serial FAKEDEV001 --lane bench --dry-run
grep -q 'name\.caiyao\.fakegps\.bench' <<<"$OUT" && grep -q 'com\.example\.cellrebelauto\.bench' <<<"$OUT" &&
    report ok "c3 bench lane routes both suffixed packages" || report fail "c3 bench lane routes both suffixed packages" "$OUT"

run_provision --serial FAKEDEV001 --lane codexbench --dry-run
grep -q 'name\.caiyao\.fakegps\.codexbench' <<<"$OUT" && grep -q 'com\.example\.cellrebelauto\.codexbench' <<<"$OUT" &&
    report ok "c3 codexbench lane routes both suffixed packages" || report fail "c3 codexbench lane routes both suffixed packages" "$OUT"

# ------------------------------------------------- c4: golden output ---------
GOLDEN_GLM="$(cat <<'__GOLDEN_GLM__'
provision-device DRY RUN — plan only, ZERO adb commands will be executed
routing (single source: <pkg-base>[.<lane>]; release has no suffix):
  qwy_pkg              name.caiyao.fakegps.glmbench
  auto_pkg             com.example.cellrebelauto.glmbench
  a11y_service         com.example.cellrebelauto.glmbench/com.example.cellrebelauto.automation.AutomationService
  cellrebel_pkg        com.cellrebel.mobile
  vector_zip           /tmp/Vector-v2.2-3110-Release.zip
  vector_module_dir    /data/adb/modules/org.matrix.vector
  vector_manager_pkg   org.matrix.vector.manager
  lspd_cli             /data/adb/lspd/cli
  plan_csv             <WORK>/fixtures/location_test_plan.glmbench.csv -> /sdcard/Download/location_test_plan.csv
  profile_csv          <WORK>/fixtures/qwy_profiles_glmbench.csv -> /sdcard/Download/qwy_profiles_glmbench.csv
  report_file          provision-report-FAKEDEV001.txt (cwd)
steps (cold path; at run time every step first self-checks and SKIPs finished work):
  [1/10] mock_location
      $ adb -s FAKEDEV001 shell appops set name.caiyao.fakegps.glmbench android:mock_location allow
  [2/10] permissions
      $ adb -s FAKEDEV001 shell pm grant name.caiyao.fakegps.glmbench android.permission.ACCESS_FINE_LOCATION
      $ adb -s FAKEDEV001 shell pm grant name.caiyao.fakegps.glmbench android.permission.ACCESS_COARSE_LOCATION
      $ adb -s FAKEDEV001 shell pm grant name.caiyao.fakegps.glmbench android.permission.POST_NOTIFICATIONS
      $ adb -s FAKEDEV001 shell pm grant com.example.cellrebelauto.glmbench android.permission.ACCESS_FINE_LOCATION
      $ adb -s FAKEDEV001 shell pm grant com.example.cellrebelauto.glmbench android.permission.ACCESS_COARSE_LOCATION
      $ adb -s FAKEDEV001 shell pm grant com.example.cellrebelauto.glmbench android.permission.POST_NOTIFICATIONS
      note: runtime: already-granted perms are skipped; grant+verify retried <=3x with 2s gaps (HyperOS probabilistic revoke)
  [3/10] push-csv
      $ adb -s FAKEDEV001 push <WORK>/fixtures/location_test_plan.glmbench.csv /sdcard/Download/location_test_plan.csv
      $ adb -s FAKEDEV001 push <WORK>/fixtures/qwy_profiles_glmbench.csv /sdcard/Download/qwy_profiles_glmbench.csv
      note: plan csv is renamed to location_test_plan.csv; profile csv keeps its name; runtime skips push when device bytes match
  [4/10] power-whitelist
      $ adb -s FAKEDEV001 shell svc power stayon usb
      $ adb -s FAKEDEV001 shell dumpsys deviceidle whitelist +name.caiyao.fakegps.glmbench
      $ adb -s FAKEDEV001 shell dumpsys deviceidle whitelist +com.example.cellrebelauto.glmbench
  [5/10] vector-module
      $ shasum -a 256 /tmp/Vector-v2.2-3110-Release.zip
      $ adb -s FAKEDEV001 push /tmp/Vector-v2.2-3110-Release.zip /data/local/tmp/Vector-v2.2-3110-Release.zip
      $ adb -s FAKEDEV001 shell su -c "magisk --install-module /data/local/tmp/Vector-v2.2-3110-Release.zip"
      note: REBOOT required to activate the module; re-run this script after reboot (steps 1-4 will SKIP)
  [6/10] manager-apk
      $ adb -s FAKEDEV001 shell su -c "chown 2000:2000 /data/adb/modules/org.matrix.vector/manager.apk && chmod 644 /data/adb/modules/org.matrix.vector/manager.apk"
      $ adb -s FAKEDEV001 pull /data/adb/modules/org.matrix.vector/manager.apk <WORK>/tmp/provision-FAKEDEV001/vector-manager.apk
      $ adb -s FAKEDEV001 install -r <WORK>/tmp/provision-FAKEDEV001/vector-manager.apk
  [7/10] vector-scope
      $ adb -s FAKEDEV001 shell su -c "/data/adb/lspd/cli modules enable name.caiyao.fakegps.glmbench"
      $ adb -s FAKEDEV001 shell su -c "/data/adb/lspd/cli scope set name.caiyao.fakegps.glmbench com.cellrebel.mobile/0 com.example.cellrebelauto.glmbench/0 name.caiyao.fakegps.glmbench/0"
  [8/10] publish-probe
      $ adb -s FAKEDEV001 shell logcat -c
      MANUAL ACTION: open name.caiyao.fakegps.glmbench settings on the device and toggle "System Mock" OFF->ON once
                    (deliberately NOT automated — su input tap on that switch is unreliable; a human or the upper layer does this)
      $ adb -s FAKEDEV001 shell logcat -d -s ConfigPrefsSync   # polled every 3s, <=60s, must contain published=true
  [9/10] accessibility
      $ adb -s FAKEDEV001 shell settings put secure enabled_accessibility_services com.example.cellrebelauto.glmbench/com.example.cellrebelauto.automation.AutomationService
      $ adb -s FAKEDEV001 shell settings put secure accessibility_enabled 1
      note: runtime asserts Bound via dumpsys accessibility (retry x3 @2s); OEM rollback models (moto) -> manual enable + --step accessibility
  [10/10] report
      note: local-only step (no adb) — writes provision-report-FAKEDEV001.txt in the invocation cwd (step x status x evidence + leftovers)
__GOLDEN_GLM__
)"
GOLDEN_REL="$(cat <<'__GOLDEN_REL__'
provision-device DRY RUN — plan only, ZERO adb commands will be executed
routing (single source: <pkg-base>[.<lane>]; release has no suffix):
  qwy_pkg              name.caiyao.fakegps
  auto_pkg             com.example.cellrebelauto
  a11y_service         com.example.cellrebelauto/com.example.cellrebelauto.automation.AutomationService
  cellrebel_pkg        com.cellrebel.mobile
  vector_zip           /tmp/Vector-v2.2-3110-Release.zip
  vector_module_dir    /data/adb/modules/org.matrix.vector
  vector_manager_pkg   org.matrix.vector.manager
  lspd_cli             /data/adb/lspd/cli
  plan_csv             <WORK>/fixtures/location_test_plan.glmbench.csv -> /sdcard/Download/location_test_plan.csv
  profile_csv          <WORK>/fixtures/qwy_profiles_glmbench.csv -> /sdcard/Download/qwy_profiles_glmbench.csv
  report_file          provision-report-FAKEDEV001.txt (cwd)
steps (cold path; at run time every step first self-checks and SKIPs finished work):
  [1/10] mock_location
      $ adb -s FAKEDEV001 shell appops set name.caiyao.fakegps android:mock_location allow
  [2/10] permissions
      $ adb -s FAKEDEV001 shell pm grant name.caiyao.fakegps android.permission.ACCESS_FINE_LOCATION
      $ adb -s FAKEDEV001 shell pm grant name.caiyao.fakegps android.permission.ACCESS_COARSE_LOCATION
      $ adb -s FAKEDEV001 shell pm grant name.caiyao.fakegps android.permission.POST_NOTIFICATIONS
      $ adb -s FAKEDEV001 shell pm grant com.example.cellrebelauto android.permission.ACCESS_FINE_LOCATION
      $ adb -s FAKEDEV001 shell pm grant com.example.cellrebelauto android.permission.ACCESS_COARSE_LOCATION
      $ adb -s FAKEDEV001 shell pm grant com.example.cellrebelauto android.permission.POST_NOTIFICATIONS
      note: runtime: already-granted perms are skipped; grant+verify retried <=3x with 2s gaps (HyperOS probabilistic revoke)
  [3/10] push-csv
      $ adb -s FAKEDEV001 push <WORK>/fixtures/location_test_plan.glmbench.csv /sdcard/Download/location_test_plan.csv
      $ adb -s FAKEDEV001 push <WORK>/fixtures/qwy_profiles_glmbench.csv /sdcard/Download/qwy_profiles_glmbench.csv
      note: plan csv is renamed to location_test_plan.csv; profile csv keeps its name; runtime skips push when device bytes match
  [4/10] power-whitelist
      $ adb -s FAKEDEV001 shell svc power stayon usb
      $ adb -s FAKEDEV001 shell dumpsys deviceidle whitelist +name.caiyao.fakegps
      $ adb -s FAKEDEV001 shell dumpsys deviceidle whitelist +com.example.cellrebelauto
  [5/10] vector-module
      $ shasum -a 256 /tmp/Vector-v2.2-3110-Release.zip
      $ adb -s FAKEDEV001 push /tmp/Vector-v2.2-3110-Release.zip /data/local/tmp/Vector-v2.2-3110-Release.zip
      $ adb -s FAKEDEV001 shell su -c "magisk --install-module /data/local/tmp/Vector-v2.2-3110-Release.zip"
      note: REBOOT required to activate the module; re-run this script after reboot (steps 1-4 will SKIP)
  [6/10] manager-apk
      $ adb -s FAKEDEV001 shell su -c "chown 2000:2000 /data/adb/modules/org.matrix.vector/manager.apk && chmod 644 /data/adb/modules/org.matrix.vector/manager.apk"
      $ adb -s FAKEDEV001 pull /data/adb/modules/org.matrix.vector/manager.apk <WORK>/tmp/provision-FAKEDEV001/vector-manager.apk
      $ adb -s FAKEDEV001 install -r <WORK>/tmp/provision-FAKEDEV001/vector-manager.apk
  [7/10] vector-scope
      $ adb -s FAKEDEV001 shell su -c "/data/adb/lspd/cli modules enable name.caiyao.fakegps"
      $ adb -s FAKEDEV001 shell su -c "/data/adb/lspd/cli scope set name.caiyao.fakegps com.cellrebel.mobile/0 com.example.cellrebelauto/0 name.caiyao.fakegps/0"
  [8/10] publish-probe
      $ adb -s FAKEDEV001 shell logcat -c
      MANUAL ACTION: open name.caiyao.fakegps settings on the device and toggle "System Mock" OFF->ON once
                    (deliberately NOT automated — su input tap on that switch is unreliable; a human or the upper layer does this)
      $ adb -s FAKEDEV001 shell logcat -d -s ConfigPrefsSync   # polled every 3s, <=60s, must contain published=true
  [9/10] accessibility
      $ adb -s FAKEDEV001 shell settings put secure enabled_accessibility_services com.example.cellrebelauto/com.example.cellrebelauto.automation.AutomationService
      $ adb -s FAKEDEV001 shell settings put secure accessibility_enabled 1
      note: runtime asserts Bound via dumpsys accessibility (retry x3 @2s); OEM rollback models (moto) -> manual enable + --step accessibility
  [10/10] report
      note: local-only step (no adb) — writes provision-report-FAKEDEV001.txt in the invocation cwd (step x status x evidence + leftovers)
__GOLDEN_REL__
)"
assert_golden "c4 glmbench golden" "$GOLDEN_GLM" "$GOT_GLM"
assert_golden "c4 release golden" "$GOLDEN_REL" "$GOT_REL"

# ------------------------------------------------- c5: arg validation --------
run_provision --lane glmbench --dry-run
[ "$RC" -eq 2 ] && grep -q -- "--serial" <<<"$OUT" &&
    report ok "c5 missing --serial -> rc 2 + names the flag" || report fail "c5 missing --serial -> rc 2 + names the flag" "rc=$RC out=$OUT"

run_provision --serial FAKEDEV001 --dry-run
[ "$RC" -eq 2 ] && grep -q -- "--lane" <<<"$OUT" &&
    report ok "c5 missing --lane -> rc 2 + names the flag" || report fail "c5 missing --lane -> rc 2 + names the flag" "rc=$RC out=$OUT"

run_provision --serial FAKEDEV001 --lane wronglane --dry-run
[ "$RC" -eq 2 ] && grep -q 'glmbench' <<<"$OUT" &&
    report ok "c5 invalid lane -> rc 2 + lists valid lanes" || report fail "c5 invalid lane -> rc 2 + lists valid lanes" "rc=$RC out=$OUT"

run_provision --serial FAKEDEV001 --lane glmbench --step nope --dry-run
[ "$RC" -eq 2 ] && grep -q 'publish-probe' <<<"$OUT" &&
    report ok "c5 invalid step -> rc 2 + lists valid steps" || report fail "c5 invalid step -> rc 2 + lists valid steps" "rc=$RC out=$OUT"

run_provision --serial FAKEDEV001 --lane glmbench --bogus-flag
[ "$RC" -eq 2 ] &&
    report ok "c5 unknown flag -> rc 2" || report fail "c5 unknown flag -> rc 2" "rc=$RC out=$OUT"

# ------------------------------------------------- c6: --step single step ----
run_provision --serial FAKEDEV001 --lane glmbench --step publish-probe --dry-run \
    --plan-csv "$FIX/location_test_plan.glmbench.csv" \
    --profile-csv "$FIX/qwy_profiles_glmbench.csv"
[ "$RC" -eq 0 ] &&
    report ok "c6 --step publish-probe rc=0" || report fail "c6 --step publish-probe rc=0" "rc=$RC out=$OUT"
grep -q '\[8/10\] publish-probe' <<<"$OUT" &&
    report ok "c6 plans exactly the requested step" || report fail "c6 plans exactly the requested step" "$OUT"
grep -q '\[1/10\]' <<<"$OUT" &&
    report fail "c6 --step must not plan other steps" "step 1 leaked into output" ||
    report ok "c6 --step must not plan other steps"
assert_no_adb "c6 --step dry-run"

# ------------------------------------------------- c7: no-csv plan -----------
run_provision --serial FAKEDEV001 --lane glmbench --dry-run
grep -q 'no --plan-csv' <<<"$OUT" &&
    report ok "c7 no CSVs -> step announces SKIP assumption" || report fail "c7 no CSVs -> step announces SKIP assumption" "$OUT"

# ------------------------------------------------- c8: custom vector zip -----
run_provision --serial FAKEDEV001 --lane glmbench --dry-run --vector-zip /tmp/custom-vector.zip
grep -q 'magisk --install-module /data/local/tmp/custom-vector.zip' <<<"$OUT" &&
    report ok "c8 --vector-zip flows into install-module plan" || report fail "c8 --vector-zip flows into install-module plan" "$OUT"

printf 'selftest-provision-device: %d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
