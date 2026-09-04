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

# --- PR #62 P1-5: pm path cardinality — split installs must not false-pass ---
# A fake adb whose pm path answers with configurable lines, plus a root_shell
# that reports a matching sha for ANY path: if the guard ever byte-checks only
# the first line, the split case would pass — the exact false green under test.
make_fake_adb_paths() { # dir pathfile
    local dir="$1"
    mkdir -p "$dir"
    cat >"$dir/adb" <<EOF
#!/usr/bin/env bash
case "\$*" in
  *"pm path"*)
    cat "$dir/pm.out"
    exit 0
    ;;
  *"install"*)
    echo "fake adb: install must not be reached in this case" >&2
    exit 98
    ;;
esac
echo "fake adb: unhandled: \$*" >&2
exit 99
EOF
    chmod +x "$dir/adb"
}

run_fn_sha() { # dir sha -> sets OUT / RC  (root_shell answers sha256sum with $2)
    OUT="$(cd "$WORK" && PATH="$1:$PATH" BENCH_PACKAGE="name.caiyao.fakegps.bench" FAKE_SHA="$2" bash -c '
        root_shell() { printf "%s  device\n" "$FAKE_SHA"; }
        APK="$1"
        '"$FN"'
        install_debug_apk_if_changed
    ' _ "$APK" 2>&1)"
    RC=$?
}

LOCAL_SHA="$(shasum -a 256 "$APK" | awk '{print $1}')"

# case 4: base + split, both hypothetically byte-matching -> MUST be rc=2
D="$WORK/c4"; make_fake_adb_paths "$D"
printf 'package:/data/app/x/base.apk\npackage:/data/app/x/split_config.arm64_v8a.apk\n' >"$D/pm.out"
run_fn_sha "$D" "$LOCAL_SHA"
[ "$RC" -eq 2 ] && report ok "c4 split install -> rc=2 (no first-line false pass)" ||
    report fail "c4 split install -> rc=2 (no first-line false pass)" "rc=$RC out=$OUT"
grep -q "HARNESS_ERROR" <<<"$OUT" && grep -q "2 APK entries" <<<"$OUT" &&
    report ok "c4 names the cardinality violation" ||
    report fail "c4 names the cardinality violation" "$OUT"
grep -q "VERIFIED install.apk" <<<"$OUT" &&
    report fail "c4 must not emit the byte-identity line" "$OUT" ||
    report ok "c4 does not claim byte identity"

# case 5: sole base.apk with matching bytes -> rc=0 AND the SHA evidence line
D="$WORK/c5"; make_fake_adb_paths "$D"
printf 'package:/data/app/x/base.apk\n' >"$D/pm.out"
run_fn_sha "$D" "$LOCAL_SHA"
[ "$RC" -eq 0 ] && report ok "c5 identical sole base.apk -> rc=0" ||
    report fail "c5 identical sole base.apk -> rc=0" "rc=$RC out=$OUT"
grep -q "VERIFIED install.apk sha256=$LOCAL_SHA" <<<"$OUT" &&
    report ok "c5 emits the installed-sha evidence line" ||
    report fail "c5 emits the installed-sha evidence line" "$OUT"

# case 6: sole path that is not a base.apk -> rc=2
D="$WORK/c6"; make_fake_adb_paths "$D"
printf 'package:/data/app/x/split_config.arm64_v8a.apk\n' >"$D/pm.out"
run_fn_sha "$D" "$LOCAL_SHA"
[ "$RC" -eq 2 ] && report ok "c6 sole non-base path -> rc=2" ||
    report fail "c6 sole non-base path -> rc=2" "rc=$RC out=$OUT"

printf 'test-hook install-guard selftest: %d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
