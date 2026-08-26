#!/usr/bin/env bash
# Selftest for the install guard inside apps/qianwangyou/scripts/test-hook.sh (F-18 R1).
#
# R1 finding: test-hook's install step branched only on process status; an
# `adb install` that exited 0 WITHOUT a Success marker fell through to
# HARNESS_ACTION — i.e. a non-install was treated as a successful module
# update. The exit status itself was also never shown.
#
# This tests the REAL shipped bytes of install_debug_apk_if_changed by
# extracting that function from test-hook.sh (sed) and exercising it against a
# fake adb — no device, no root, deterministic.
#
# The PKG/package-contract question (script constants vs the .bench debug
# install) was OUT of scope for R1 and is now guarded by its own selftest:
# scripts/selftest-test-hook-package-identity.sh. Here the identity variable
# is injected as the bench package purely so the extracted function runs
# against a realistic coordinate; this fake adb's `pm path` answers empty
# for any package (bench not installed), which is the install-branch shape.
# Exit 0 = all cases pass; anything else = failure.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_HOOK="$HERE/../apps/qianwangyou/scripts/test-hook.sh"

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

# Extract the actual function from the shipped script (single guard: exactly
# one match, non-empty, balanced by the closing brace at column 0).
FN="$(sed -n '/^install_debug_apk_if_changed()/,/^}/p' "$TEST_HOOK")"
if [ -z "$FN" ]; then
    echo "could not extract install_debug_apk_if_changed from $TEST_HOOK" >&2
    exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
APK="$WORK/app-debug.apk"
printf 'fake-apk-bytes' >"$APK"

make_fake_adb() { # dir
    local dir="$1"
    mkdir -p "$dir"
    cat >"$dir/adb" <<EOF
#!/usr/bin/env bash
case "\$*" in
  *"pm path"*)
    # bench package not installed on the fake device -> empty pm path,
    # function skips the idempotence branch and proceeds to install
    exit 0
    ;;
  *"install"*)
    cat "$dir/install.out"
    exit \$(cat "$dir/install.rc")
    ;;
esac
echo "fake adb: unhandled: \$*" >&2
exit 99
EOF
    chmod +x "$dir/adb"
}

run_fn() { # dir -> sets OUT / RC
    OUT="$(cd "$WORK" && PATH="$1:$PATH" BENCH_PACKAGE="name.caiyao.fakegps.bench" bash -c '
        root_shell() { :; }
        APK="$1"
        '"$FN"'
        install_debug_apk_if_changed
    ' _ "$APK" 2>&1)"
    RC=$?
}

# ------------------- case 1: rc=0 without Success must NOT be an update -------
D="$WORK/c1"; make_fake_adb "$D"
printf 'Performing streamed install\n' >"$D/install.out"
printf '0' >"$D/install.rc"
run_fn "$D"
[ "$RC" -eq 2 ] && report ok "c1 rc0-no-Success -> rc=2 HARNESS_ERROR" ||
    report fail "c1 rc0-no-Success -> rc=2 HARNESS_ERROR" "rc=$RC out=$OUT"
grep -q "HARNESS_ERROR" <<<"$OUT" &&
    report ok "c1 names HARNESS_ERROR" || report fail "c1 names HARNESS_ERROR" "$OUT"
grep -q "HARNESS_ACTION" <<<"$OUT" &&
    report fail "c1 must not emit HARNESS_ACTION" "non-install treated as update: $OUT" ||
    report ok "c1 does not emit HARNESS_ACTION"
grep -q "Performing streamed install" <<<"$OUT" &&
    report ok "c1 echoes full adb output" || report fail "c1 echoes full adb output" "$OUT"

# ------------------- case 2: real adb exit status must reach the caller -------
D="$WORK/c2"; make_fake_adb "$D"
printf 'INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match\n' >"$D/install.out"
printf '7' >"$D/install.rc"
run_fn "$D"
[ "$RC" -eq 2 ] && report ok "c2 failed install -> rc=2" || report fail "c2 failed install -> rc=2" "rc=$RC"
grep -q "exit 7" <<<"$OUT" &&
    report ok "c2 prints the real adb status (7)" || report fail "c2 prints the real adb status (7)" "$OUT"

# ------------- case 3: rc=0 WITH Success keeps the intentional lifecycle ------
# A real update must still stop for the operator to restore LSPosed scope
# (HARNESS_ACTION rc=3) — the guard must not break that contract.
D="$WORK/c3"; make_fake_adb "$D"
printf 'Success\n' >"$D/install.out"
printf '0' >"$D/install.rc"
run_fn "$D"
[ "$RC" -eq 3 ] && report ok "c3 genuine Success -> rc=3 HARNESS_ACTION" ||
    report fail "c3 genuine Success -> rc=3 HARNESS_ACTION" "rc=$RC out=$OUT"
grep -q "HARNESS_ACTION" <<<"$OUT" &&
    report ok "c3 preserves LSPosed lifecycle stop" || report fail "c3 preserves LSPosed lifecycle stop" "$OUT"

printf 'test-hook install-guard selftest: %d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
