#!/usr/bin/env bash
# Selftest for scripts/check-debug-signer.sh (F-18).
#
# F-18 root cause: CI runners generate a RANDOM ~/.android/debug.keystore per run,
# so every downloaded CI debug APK carries a different random signer and can never
# `adb install -r` over a local build. The guard compares the cert SHA-256 that
# signed an APK against the cert inside the committed repo keystore.
#
# This selftest exercises the comparison logic through fake keytool/apksigner
# PATH shims (no real crypto, no device, deterministic).
# Exit 0 = all cases pass; anything else = failure.

set -uo pipefail

# Tool discovery prefers $JAVA_HOME/bin/keytool; neutralize ambient env so the
# PATH shims below are the only keytool/apksigner the guard can find.
unset JAVA_HOME ANDROID_HOME ANDROID_SDK_ROOT

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GUARD="$HERE/check-debug-signer.sh"

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

if [ ! -f "$GUARD" ]; then
    echo "selftest target missing: $GUARD (write the guard, not just the test)"
    exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
KS="$WORK/bench.keystore"
APK="$WORK/app-debug.apk"
printf 'fake-keystore' >"$KS"
printf 'fake-apk' >"$APK"

make_shims() { # dir keystore_sha apk_sha with_apksigner
    local dir="$1" ks_sha="$2" apk_sha="$3" with_apksigner="$4"
    mkdir -p "$dir"
    cat >"$dir/keytool" <<EOF
#!/usr/bin/env bash
if [ "\$1" = "-list" ] || [ "\$1" = "-listv" ] || [ "\$1" = "-v" ]; then
    printf 'SHA256: %s\n' "$ks_sha"
    exit 0
fi
exit 99
EOF
    chmod +x "$dir/keytool"
    if [ "$with_apksigner" = "yes" ]; then
        cat >"$dir/apksigner" <<EOF
#!/usr/bin/env bash
printf 'Signer certificate SHA-256 digest: %s\n' "$apk_sha"
exit 0
EOF
        chmod +x "$dir/apksigner"
    fi
}

FINGERPRINT="7a598cbe6fb816ba74f01b58e3f43b8ff0f463989157e590ebd86c89b53f7e41"

# ------------------------------------------ case 1: signer matches keystore ---
D="$WORK/c1"; make_shims "$D" "$FINGERPRINT" "$FINGERPRINT" yes
OUT="$(PATH="$D:$PATH" "$GUARD" "$KS" "$APK" 2>&1)"; RC=$?
[ "$RC" -eq 0 ] && report ok "c1 matching signer rc=0" || report fail "c1 matching signer rc=0" "rc=$RC out=$OUT"
grep -q "$FINGERPRINT" <<<"$OUT" &&
    report ok "c1 prints the fingerprint" || report fail "c1 prints the fingerprint" "$OUT"

# ---------------------------------- case 2: foreign (random CI) signer caught --
D="$WORK/c2"; make_shims "$D" "$FINGERPRINT" "53fd8e583c7b4676429bfb6927b135de2caf7a1d6310e58e4f00ad4486571701" yes
OUT="$(PATH="$D:$PATH" "$GUARD" "$KS" "$APK" 2>&1)"; RC=$?
[ "$RC" -ne 0 ] && report ok "c2 foreign signer rc!=0" || report fail "c2 foreign signer rc!=0" "rc=0 (random CI cert passed the gate!)"
grep -qi "MISMATCH" <<<"$OUT" &&
    report ok "c2 names MISMATCH" || report fail "c2 names MISMATCH" "$OUT"

# ----------------------- case 3: normalization (colons + uppercase) tolerated --
D="$WORK/c3"
make_shims "$D" "7A:59:8C:BE:6F:B8:16:BA:74:F0:1B:58:E3:F4:3B:8F:F0:F4:63:98:91:57:E5:90:EB:D8:6C:89:B5:3F:7E:41" "$FINGERPRINT" yes
OUT="$(PATH="$D:$PATH" "$GUARD" "$KS" "$APK" 2>&1)"; RC=$?
[ "$RC" -eq 0 ] && report ok "c3 colon/upper form normalized" || report fail "c3 colon/upper form normalized" "rc=$RC out=$OUT"

# ---------------------------------- case 4: missing apksigner fails LOUDLY ----
# Discovery fallbacks (ANDROID_HOME, ANDROID_SDK_ROOT, ~/Library/Android/sdk)
# must be neutralized so the guard truly cannot find an apksigner.
D="$WORK/c4"; make_shims "$D" "$FINGERPRINT" "" no
mkdir -p "$WORK/fakehome"
OUT="$(HOME="$WORK/fakehome" ANDROID_HOME="$WORK/nosuchsdk" ANDROID_SDK_ROOT="$WORK/nosuchsdk" \
    PATH="$D:/usr/bin:/bin" "$GUARD" "$KS" "$APK" 2>&1)"; RC=$?
[ "$RC" -ne 0 ] && report ok "c4 no apksigner -> nonzero rc" || report fail "c4 no apksigner -> nonzero rc" "rc=0 (gate skipped itself!)"
grep -qi "apksigner" <<<"$OUT" &&
    report ok "c4 explains missing apksigner" || report fail "c4 explains missing apksigner" "$OUT"

printf 'check-debug-signer selftest: %d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
