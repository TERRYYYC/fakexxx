#!/usr/bin/env bash
# Selftest for scripts/install_apk_verified.sh (F-18).
#
# F-18 root causes this helper must make impossible:
#   1. `adb install -r` fails (INSTALL_FAILED_UPDATE_INCOMPATIBLE) and the caller
#      either swallowed stdout or never checked the exit code -> silent failure.
#   2. Build identity was asserted via versionCode/versionName, which are
#      IDENTICAL across signers and checkouts -> "install failed but version
#      unchanged" read as "already latest" -> tests ran against a stale APK.
#
# Both failure classes are exercised here against a fake adb; no device needed.
# Exit 0 = all cases pass; anything else = failure.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HELPER="$HERE/install_apk_verified.sh"

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

if [ ! -f "$HELPER" ]; then
    echo "selftest target missing: $HELPER (write the helper, not just the test)"
    exit 1
fi

# ---------------------------------------------------------------- fake adb ---
make_fake_adb() { # dir scenario
    local dir="$1" scenario="$2"
    mkdir -p "$dir"
    cat >"$dir/adb" <<EOF
#!/usr/bin/env bash
echo "\$*" >> "$dir/invocations.log"
full="\$*"
case "\$full" in
  *" install "*)
    cat "$dir/install.out"
    exit \$(cat "$dir/install.rc")
    ;;
  *"pm path"*)
    if [ -f "$dir/pm_path.out" ]; then cat "$dir/pm_path.out"; fi
    exit 0
    ;;
  *"sha256sum"*)
    if [ -f "$dir/sha256.out" ]; then cat "$dir/sha256.out"; fi
    exit 0
    ;;
esac
echo "fake adb: unhandled: \$*" >&2
exit 99
EOF
    chmod +x "$dir/adb"
    printf '%s' "$scenario" >"$dir/scenario.name"
}

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
SERIAL="FAKESERIAL1"
PKG="name.caiyao.fakegps.bench"
APK="$WORK/app-debug.apk"
printf 'fake-apk-bytes-for-f18' >"$APK"
LOCAL_SHA="$(shasum -a 256 "$APK" | awk '{print $1}')"
FOREIGN_SHA="1111111111111111111111111111111111111111111111111111111111111111"

run_helper() { # dir -> sets OUT / RC
    OUT="$(PATH="$1:$PATH" "$HELPER" -s "$SERIAL" -p "$PKG" -- "$APK" 2>&1)"
    RC=$?
}

# ------------------------------------------------------- case 1: happy path ---
D="$WORK/c1"; make_fake_adb "$D" ok
printf 'Success\n' >"$D/install.out"; printf '0' >"$D/install.rc"
printf 'package:/data/app/~~x/name.caiyao.fakegps.bench-y/base.apk\n' >"$D/pm_path.out"
printf '%s  /data/app/~~x/name.caiyao.fakegps.bench-y/base.apk\n' "$LOCAL_SHA" >"$D/sha256.out"
run_helper "$D"
[ "$RC" -eq 0 ] && report ok "c1 install+verify rc=0" || report fail "c1 install+verify rc=0" "rc=$RC out=$OUT"
grep -q "MATCH" <<<"$OUT" && report ok "c1 prints MATCH" || report fail "c1 prints MATCH" "$OUT"
grep -q -- "-s $SERIAL install" "$D/invocations.log" 2>/dev/null &&
    report ok "c1 actually installs" || report fail "c1 actually installs" "no install invocation"

# ------------------------------------- case 2: INSTALL_FAILED must be LOUD ----
D="$WORK/c2"; make_fake_adb "$D" incompatible
printf 'INSTALL_FAILED_UPDATE_INCOMPATIBLE: name.caiyao.fakegps.bench signatures do not match\n' >"$D/install.out"
printf '1' >"$D/install.rc"
run_helper "$D"
[ "$RC" -ne 0 ] && report ok "c2 incompatible -> nonzero rc" || report fail "c2 incompatible -> nonzero rc" "rc=0 (silent failure!)"
grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE" <<<"$OUT" &&
    report ok "c2 full adb failure text surfaced" || report fail "c2 full adb failure text surfaced" "$OUT"

# ----------------------- case 3: Success printed but device bytes are stale ---
D="$WORK/c3"; make_fake_adb "$D" stale-success
printf 'Success\n' >"$D/install.out"; printf '0' >"$D/install.rc"
printf 'package:/data/app/~~x/name.caiyao.fakegps.bench-y/base.apk\n' >"$D/pm_path.out"
printf '%s  /data/app/~~x/base.apk\n' "$FOREIGN_SHA" >"$D/sha256.out"
run_helper "$D"
[ "$RC" -ne 0 ] && report ok "c3 stale package -> nonzero rc" || report fail "c3 stale package -> nonzero rc" "rc=0 (false green!)"
grep -qi "IDENTITY MISMATCH" <<<"$OUT" &&
    report ok "c3 names IDENTITY MISMATCH" || report fail "c3 names IDENTITY MISMATCH" "$OUT"
grep -q "$FOREIGN_SHA" <<<"$OUT" &&
    report ok "c3 shows installed sha256" || report fail "c3 shows installed sha256" "$OUT"

# ------------------------------------ case 4: split APKs are not silently OK --
D="$WORK/c4"; make_fake_adb "$D" split
printf 'Success\n' >"$D/install.out"; printf '0' >"$D/install.rc"
printf 'package:/data/app/x/base.apk\npackage:/data/app/x/split_config.arm64_v8a.apk\n' >"$D/pm_path.out"
run_helper "$D"
[ "$RC" -ne 0 ] && report ok "c4 split apks -> nonzero rc" || report fail "c4 split apks -> nonzero rc" "rc=0"

# --------------------------- case 5: package vanished after install claim -----
D="$WORK/c5"; make_fake_adb "$D" vanished
printf 'Success\n' >"$D/install.out"; printf '0' >"$D/install.rc"
rm -f "$D/pm_path.out"
run_helper "$D"
[ "$RC" -ne 0 ] && report ok "c5 package absent -> nonzero rc" || report fail "c5 package absent -> nonzero rc" "rc=0"

# ------------------- case 6: the REAL adb exit status must be printed ---------
# R1 finding: `if ! OUT=$(...)` made $? inside the body 0, so every failed
# install was reported as "adb exit 0". The actual status is diagnostic gold
# (protocol failure vs signature mismatch vs stream error) and must survive.
D="$WORK/c6"; make_fake_adb "$D" real-status
printf 'INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match\n' >"$D/install.out"
printf '7' >"$D/install.rc"
run_helper "$D"
[ "$RC" -ne 0 ] && report ok "c6 nonzero rc -> nonzero rc" || report fail "c6 nonzero rc -> nonzero rc" "rc=0"
grep -q "adb exit 7" <<<"$OUT" &&
    report ok "c6 prints the REAL adb status (7)" || report fail "c6 prints the REAL adb status (7)" "$OUT"
grep -q "adb exit 0" <<<"$OUT" &&
    report fail "c6 must not claim exit 0" "false status in: $OUT" || report ok "c6 does not claim exit 0"

# --------------------- case 7: rc=0 but no Success marker in output -----------
# adb has been observed to exit 0 without installing; process status alone is
# not an install proof. The helper must refuse and stay loud.
D="$WORK/c7"; make_fake_adb "$D" no-success
printf 'Performing streamed install\n' >"$D/install.out"
printf '0' >"$D/install.rc"
run_helper "$D"
[ "$RC" -ne 0 ] && report ok "c7 rc0-no-Success -> nonzero rc" || report fail "c7 rc0-no-Success -> nonzero rc" "rc=0 (treated as installed!)"
grep -q "no Success" <<<"$OUT" &&
    report ok "c7 names the missing Success marker" || report fail "c7 names the missing Success marker" "$OUT"
grep -q "Performing streamed install" <<<"$OUT" &&
    report ok "c7 echoes the full adb output" || report fail "c7 echoes the full adb output" "$OUT"

printf 'install_apk_verified selftest: %d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
