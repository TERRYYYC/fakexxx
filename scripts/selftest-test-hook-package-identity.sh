#!/usr/bin/env bash
# Selftest for the PACKAGE IDENTITY of apps/qianwangyou/scripts/test-hook.sh.
#
# Gap (G2 package §3.3-3, docs/acceptance/issue7-g2-acceptance-package.md):
# the script hardcoded PKG=name.caiyao.fakegps (production applicationId)
# while the debug APK it builds and installs carries debug
# applicationIdSuffix ".bench" — and the acceptance Activity, its
# signature permission, and the AppInfoProvider authority live only inside
# that bench debug install. Every identity probe therefore addressed the
# WRONG package:
#   - install idempotence compared the LOCAL bench APK's bytes against the
#     PRODUCTION package's base.apk: a byte-identical production install
#     read as "identical debug APK already installed" and skipped install
#     (false green, F-13/F-17 family), and no bench install could ever be
#     recognized (permanent reinstall loop);
#   - the dumpsys "debug acceptance build" gate grepped the PRODUCTION
#     package with a permission string that ${applicationId} never expands
#     to in either install — a condition no real package can satisfy.
#
# Red-first: R1/R2 below FAIL against the pre-fix script (the recorded red:
# they demonstrate the false green exists), and PASS once identity points at
# the bench package. G1 proves the green path launches the bench component
# by its EXPLICIT namespace FQCN — `pkg/.ShortName` shorthand resolves
# relative to the applicationId (name.caiyao.fakegps.bench.probe.*) and
# points at classes that do not exist (convention already set by
# mock_provider_acceptance.sh BENCH_ACTIVITY/ACCEPTANCE_ACTIVITY).
#
# M1/M2 mutation-check the guards: re-pointing identity at production must
# bring the false green back, otherwise the cases above prove nothing.
#
# Device-free: the runner script sources the REAL shipped constant block,
# then the REAL extracted functions (sed), driven against a fake adb (F-18
# selftest lineage — scripts/selftest-test-hook-install-guard.sh).
# Identity under test is therefore always the script's own, never a
# selftest-injected value. Exit 0 = all cases pass.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_HOOK="$HERE/../apps/qianwangyou/scripts/test-hook.sh"

PRODUCT_ID="name.caiyao.fakegps"
BENCH_ID="name.caiyao.fakegps.bench"

pass=0
fail=0

report() {
    if [ "$1" = "ok" ]; then
        printf 'ok   %s\n' "$2"
        pass=$((pass + 1))
    else
        printf 'FAIL %s :: %s\n' "$2" "$3"
        fail=$((fail + 1))
    fi
}

if [ ! -f "$TEST_HOOK" ]; then
    echo "selftest target missing: $TEST_HOOK" >&2
    exit 1
fi

# Real shipped pieces: the constant block (set -u .. before MODE=) and the
# functions under test. Balanced by the closing brace at column 0.
CONSTS="$(sed -n '/^set -u$/,/^MODE=/p' "$TEST_HOOK" | sed '$d')"
FN_INSTALL="$(sed -n '/^install_debug_apk_if_changed()/,/^}/p' "$TEST_HOOK")"
FN_PREFLIGHT="$(sed -n '/^preflight_matrix()/,/^}/p' "$TEST_HOOK")"
FN_SNAPDB="$(sed -n '/^snapshot_db()/,/^}/p' "$TEST_HOOK")"
FN_WAITSCHEMA="$(sed -n '/^wait_for_profile_schema()/,/^}/p' "$TEST_HOOK")"
for fn in "$CONSTS" "$FN_INSTALL" "$FN_PREFLIGHT" "$FN_SNAPDB" "$FN_WAITSCHEMA"; do
    [ -n "$fn" ] || { echo "could not extract constants/functions from $TEST_HOOK" >&2; exit 1; }
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
FAKE_APK="$WORK/app-debug.apk"
printf 'fake-bench-apk-bytes' >"$FAKE_APK"
LOCAL_SHA=$(shasum -a 256 "$FAKE_APK" | awk '{print $1}')

# ---------------------------------------------------------------------------
# fake adb: routes every device-side identity query by its package argument.
# The production `pm path` answer describes a package whose bytes happen to
# match the local APK (the false-green shape); bench answers are per-case.
# ---------------------------------------------------------------------------
make_fake_adb() { # dir
    local dir="$1"
    mkdir -p "$dir"
    cat >"$dir/adb" <<EOF
#!/usr/bin/env bash
record() { printf '%s\n' "\$1" >>"\$CALLS"; }
case "\$*" in
  *"pm path $BENCH_ID")
    record "\$*"
    [ -s "$dir/bench.pmpath" ] && cat "$dir/bench.pmpath"
    exit 0
    ;;
  *"pm path $PRODUCT_ID")
    # production install byte-identical to the local (bench) artifact bytes:
    # the exact shape a production-pointing identity check false-greens on
    record "\$*"
    echo "package:/data/app/~~prod==/base.apk"
    exit 0
    ;;
  *"dumpsys package $BENCH_ID")
    record "\$*"
    cat "$dir/bench.dump" 2>/dev/null
    exit 0
    ;;
  *"dumpsys package $PRODUCT_ID")
    record "\$*"
    cat "$dir/prod.dump" 2>/dev/null
    exit 0
    ;;
  *"install"*)
    record "\$*"
    cat "$dir/install.out"
    exit "\$(cat "$dir/install.rc")"
    ;;
  *"am start"*)
    record "\$*"
    exit 0
    ;;
  *"pm grant "*|*"am force-stop "*|*"logcat -c"*)
    exit 0
    ;;
  *"logcat -d"*)
    printf 'FakeGPS: [DIAG] prefs loaded fields=9\n'
    exit 0
    ;;
esac
echo "fake adb: unhandled: \$*" >&2
exit 99
EOF
    chmod +x "$dir/adb"
}

# Assemble a runner: real constants -> optional identity mutation -> fake
# artifacts -> stubs -> real functions -> the call. Written to a file (not
# inlined) because the extracted function bodies legitimately contain
# single quotes.
#   $1 dir  $2 target_fn  $3 preflight?(preflight|install)  $4 override(may be empty)
build_and_run() {
    local dir="$1" target="$2" mode="$3" override="$4"
    CALLS="$dir/calls.log"; export CALLS
    : >"$CALLS"
    {
        printf '%s\n' "$CONSTS"
        if [ -n "$override" ]; then
            printf 'PKG=%q\nBENCH_PACKAGE=%q\n' "$override" "$override"
        fi
        cat <<MID
APK="\$FAKE_APK"
MATRIX_TOOL="\$FAKE_APK"
VERDICT_TOOL="\$FAKE_APK"
DEVICE_API=34
root_shell() {
    case "\$1" in
        *"sha256sum "*)
            # production base.apk bytes == local artifact bytes
            echo "\$FAKE_INSTALLED_SHA  /data/app/~~prod==/base.apk"
            ;;
        *"content query "*)
            echo "Row: unavailable_fields=none"
            ;;
    esac
    return 0
}
MID
        if [ "$mode" = "preflight" ]; then
            printf 'install_debug_apk_if_changed() { return 0; }\n'
            printf '%s\n' "$FN_SNAPDB"
            printf '%s\n' "$FN_WAITSCHEMA"
        fi
        printf '%s\n' "$FN_INSTALL"
        printf '%s\n' "$FN_PREFLIGHT"
        printf '%s\n' "$target"
    } >"$dir/runner.sh"
    OUT="$(
        cd "$WORK" &&
        env -i PATH="$dir:$PATH" \
            FAKE_APK="$FAKE_APK" \
            FAKE_INSTALLED_SHA="$LOCAL_SHA" \
            CALLS="$CALLS" \
            HOME="$WORK" \
            bash "$dir/runner.sh" 2>&1
    )"
    RC=$?
}

# ---------------------------------------------------------------------------
# R1 (red): production bytes matching must NOT prove the bench artifact is
# installed. Pre-fix: return 0 "identical" (false green). Post-fix: the
# check queries the bench package; bench absent -> loud install path.
# ---------------------------------------------------------------------------
D="$WORK/r1"; make_fake_adb "$D"
: >"$D/bench.pmpath"           # bench not installed
printf 'Success\n' >"$D/install.out"; printf '0' >"$D/install.rc"
: >"$D/bench.dump"; : >"$D/prod.dump"
build_and_run "$D" "install_debug_apk_if_changed" "install" ""
if [ "$RC" -eq 0 ]; then
    report fail "R1 production-match must not read as installed" "rc=0 out=$OUT"
else
    report ok "R1 production-match must not read as installed (rc=$RC)"
fi
grep -q "identical" <<<"$OUT" &&
    report fail "R1 must not claim identical" "out=$OUT" ||
    report ok "R1 does not claim identical"
grep -q -- "pm path $BENCH_ID" "$D/calls.log" &&
    report ok "R1 identity query addresses bench package" ||
    report fail "R1 identity query addresses bench package" "calls: $(cat "$D/calls.log")"

# ---------------------------------------------------------------------------
# R2 (red): the dumpsys debug-acceptance gate must be satisfied by the BENCH
# package, never by production output — even production output that carries
# both the DEBUGGABLE flag and an acceptance-permission line (unreachable in
# reality: production ships no such permission, and ${applicationId} expands
# the bench one to .bench.permission.*). Bench is installed byte-identical so
# the install line is bypassed on BOTH red (production bytes match) and green
# (bench bytes match) runs, and the gate alone decides.
# ---------------------------------------------------------------------------
D="$WORK/r2"; make_fake_adb "$D"
printf 'package:/data/app/~~bench==/base.apk\n' >"$D/bench.pmpath"
printf 'Success\n' >"$D/install.out"; printf '0' >"$D/install.rc"
: >"$D/bench.dump"
cat >"$D/prod.dump" <<'EOF'
flags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA]
requested permissions:
name.caiyao.fakegps.permission.RUN_HOOK_ACCEPTANCE: prot=signature
EOF
build_and_run "$D" "preflight_matrix" "preflight" ""
if [ "$RC" -eq 0 ]; then
    report fail "R2 production dumpsys must not pass the gate" "rc=0 out=$OUT"
else
    report ok "R2 production dumpsys must not pass the gate (rc=$RC)"
fi
grep -q "HARNESS_ERROR" <<<"$OUT" &&
    report ok "R2 names HARNESS_ERROR" ||
    report fail "R2 names HARNESS_ERROR" "$OUT"
grep -q -- "dumpsys package $BENCH_ID" "$D/calls.log" &&
    report ok "R2 gate queries the bench package" ||
    report fail "R2 gate queries the bench package" "calls: $(cat "$D/calls.log")"

# ---------------------------------------------------------------------------
# G1 (green): a bench install that IS the debug acceptance build passes the
# gate, and the launched component is the bench package + explicit namespace
# FQCN (never applicationId-relative shorthand).
# ---------------------------------------------------------------------------
D="$WORK/g1"; make_fake_adb "$D"
printf 'package:/data/app/~~bench==/base.apk\n' >"$D/bench.pmpath"
printf 'Success\n' >"$D/install.out"; printf '0' >"$D/install.rc"
cat >"$D/bench.dump" <<EOF
flags=[ DEBUGGABLE HAS_CODE]
$BENCH_ID.permission.RUN_HOOK_ACCEPTANCE: prot=signature
EOF
: >"$D/prod.dump"
build_and_run "$D" "preflight_matrix" "preflight" ""
[ "$RC" -eq 0 ] &&
    report ok "G1 bench acceptance build passes preflight" ||
    report fail "G1 bench acceptance build passes preflight" "rc=$RC out=$OUT"
grep -q -- "am start -W -n $BENCH_ID/name.caiyao.fakegps.ui.ComposeActivity" "$D/calls.log" &&
    report ok "G1 launches bench component by explicit FQCN" ||
    report fail "G1 launches bench component by explicit FQCN" "calls: $(cat "$D/calls.log")"

# ---------------------------------------------------------------------------
# S (static): shipped constants must carry the bench identity in explicit
# FQCN form; shorthand (`$BENCH_PACKAGE/.ShortName`) resolves relative to
# the applicationId and points at classes that do not exist.
# ---------------------------------------------------------------------------
grep -q "BENCH_PACKAGE=\"$BENCH_ID\"" "$TEST_HOOK" &&
    report ok "S defines BENCH_PACKAGE" ||
    report fail "S defines BENCH_PACKAGE" "not found in $TEST_HOOK"
for c in 'ACT="$BENCH_PACKAGE/name.caiyao.fakegps.ui.ComposeActivity"' \
    'ACCEPTANCE_ACT="$BENCH_PACKAGE/name.caiyao.fakegps.probe.HookAcceptanceActivity"' \
    'PROVIDER="content://$BENCH_PACKAGE.data.AppInfoProvider/app"'; do
    grep -qF "$c" "$TEST_HOOK" &&
        report ok "S constant $c" ||
        report fail "S constant $c" "not found"
done
grep -qE '^(ACT|ACCEPTANCE_ACT|PROVIDER)=.*\$BENCH_PACKAGE/\.' "$TEST_HOOK" &&
    report fail "S no shorthand component names" "shorthand found in $TEST_HOOK" ||
    report ok "S no shorthand component names"
grep -qE '^PKG=' "$TEST_HOOK" &&
    report fail "S no bare PKG= production constant" "PKG= still defined" ||
    report ok "S no bare PKG= production constant"

# ---------------------------------------------------------------------------
# M1/M2 (mutation): re-point identity at production; the false green MUST
# come back. If it does not, the fake shapes above were not reachable and
# R1/R2 proved nothing.
# ---------------------------------------------------------------------------
D="$WORK/m1"; make_fake_adb "$D"
: >"$D/bench.pmpath"
printf 'Success\n' >"$D/install.out"; printf '0' >"$D/install.rc"
: >"$D/bench.dump"; : >"$D/prod.dump"
build_and_run "$D" "install_debug_apk_if_changed" "install" "$PRODUCT_ID"
[ "$RC" -eq 0 ] && grep -q "identical" <<<"$OUT" &&
    report ok "M1 revert->production reproduces the false green" ||
    report fail "M1 revert->production reproduces the false green" "rc=$RC out=$OUT"

D="$WORK/m2"; make_fake_adb "$D"
: >"$D/bench.pmpath"
printf 'Success\n' >"$D/install.out"; printf '0' >"$D/install.rc"
: >"$D/bench.dump"
cat >"$D/prod.dump" <<'EOF'
flags=[ DEBUGGABLE]
name.caiyao.fakegps.permission.RUN_HOOK_ACCEPTANCE: prot=signature
EOF
build_and_run "$D" "preflight_matrix" "preflight" "$PRODUCT_ID"
[ "$RC" -eq 0 ] &&
    report ok "M2 revert->production passes the dumpsys gate" ||
    report fail "M2 revert->production passes the dumpsys gate" "rc=$RC out=$OUT"

printf 'test-hook package-identity selftest: %d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
