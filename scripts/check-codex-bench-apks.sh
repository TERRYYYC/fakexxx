#!/usr/bin/env bash
# Inspect built bytes, not Gradle source: these APKs must coexist with the running apps.
# Usage: bash scripts/check-codex-bench-apks.sh QWY_CODEX_APK AUTO_CODEX_APK
# This checker never communicates with a device.
set -euo pipefail

fail() { echo "codex-bench isolation FAIL: $*" >&2; exit 2; }
[ "$#" -eq 2 ] || fail "expected QWY_CODEX_APK AUTO_CODEX_APK"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

find_aapt() {
    if command -v aapt >/dev/null 2>&1; then command -v aapt; return; fi
    local sdk candidate best=""
    for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk"; do
        [ -n "$sdk" ] || continue
        for candidate in "$sdk"/build-tools/*/aapt; do
            [ -x "$candidate" ] && best="$candidate"
        done
        if [ -n "$best" ]; then echo "$best"; return; fi
    done
    return 1
}
AAPT="$(find_aapt)" || fail "aapt unavailable; set ANDROID_HOME"

# All attributes precede children in aapt's xmltree. Flush on the next element,
# so checks remain tied to a declaration/component instead of a global grep hit.
manifest_attr() {
    local xml="$1" element="$2" component="$3" attribute="$4"
    awk -v wanted_element="$element" -v wanted_name="$component" -v wanted_attr="$attribute" '
        function flush() {
            if (kind == wanted_element && (wanted_name == "" || name == wanted_name) && value != "") print value
        }
        /E: / { flush(); kind=$0; sub(/^.*E: /, "", kind); sub(/ .*/, "", kind); name=""; value=""; next }
        /A: / {
            attr=$0; sub(/^.*A: /, "", attr); sub(/[(=].*/, "", attr)
            val=$0; sub(/^[^=]*=/, "", val)
            if (val ~ /^"/) { sub(/^"/, "", val); sub(/".*/, "", val) }
            if (attr == "android:name") name=val
            if (attr == wanted_attr) value=val
        }
        END { flush() }
    ' <<< "$xml"
}

assert_equal() { [ "$1" = "$2" ] || fail "$3: expected '$2', got '$1'"; }

check_apk() {
    local apk="$1" app_id="$2" label="$3" launcher="$4" keystore="$5"
    local badging xml actual labels component authority permission
    [ -f "$apk" ] || fail "APK missing: $apk"
    badging="$("$AAPT" dump badging "$apk")" || fail "cannot read APK: $apk"
    xml="$("$AAPT" dump xmltree "$apk" AndroidManifest.xml)" || fail "cannot read manifest: $apk"
    actual="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<< "$badging")"
    assert_equal "$actual" "$app_id" "application ID"
    labels="$(sed -n "s/^application-label[^:]*:'\(.*\)'$/\1/p" <<< "$badging")"
    [ -n "$labels" ] || fail "$app_id has no compiled application labels"
    while IFS= read -r actual; do assert_equal "$actual" "$label" "$app_id localized label"; done <<< "$labels"
    actual="$(sed -n "s/^launchable-activity: name='\([^']*\)'.*/\1/p" <<< "$badging")"
    assert_equal "$actual" "$launcher" "$app_id launcher class"
    actual="$(sed -n "s/^launchable-activity:.* label='\([^']*\)'.*/\1/p" <<< "$badging")"
    # aapt reports an empty label when the launcher inherits the application label.
    [ -z "$actual" ] || assert_equal "$actual" "$label" "$app_id launcher label"
    # A literal application label cannot revert to an old app_name under a locale
    # not selected by aapt badging. An explicit launcher label, if any, must match.
    assert_equal "$(manifest_attr "$xml" application "" android:label)" "$label" "$app_id literal application label"
    actual="$(manifest_attr "$xml" activity "$launcher" android:label)"
    [ -z "$actual" ] || assert_equal "$actual" "$label" "$app_id literal launcher label"
    [[ "$badging" == *$'\napplication-debuggable'* ]] || fail "$app_id is not debuggable"
    while IFS= read -r authority; do
        [ -n "$authority" ] || continue
        [[ "$authority" == "$app_id."* && "$authority" != *';'* ]] || fail "$app_id foreign provider authority: $authority"
    done <<< "$(manifest_attr "$xml" provider "" android:authorities)"
    while IFS= read -r permission; do
        [ -n "$permission" ] || continue
        [[ "$permission" == "$app_id."* ]] || fail "$app_id foreign signature permission: $permission"
        assert_equal "$(manifest_attr "$xml" permission "$permission" android:protectionLevel)" '(type 0x11)0x2' "$permission protection level"
    done <<< "$(manifest_attr "$xml" permission "" android:name)"
    bash "$SCRIPT_DIR/check-debug-signer.sh" "$keystore" "$apk"

    if [ "$app_id" = name.caiyao.fakegps.codexbench ]; then
        assert_equal "$(manifest_attr "$xml" provider name.caiyao.fakegps.data.AppInfoProvider android:authorities)" "$app_id.data.AppInfoProvider" "QWY data authority"
        assert_equal "$(manifest_attr "$xml" application "" android:name)" name.caiyao.fakegps.probe.HookAcceptanceApplication "QWY debug application"
        for component in name.caiyao.fakegps.probe.HookAcceptanceActivity name.caiyao.fakegps.mockprovider.MockProviderAcceptanceActivity name.caiyao.fakegps.integration.v1.PairingApprovalActivity name.caiyao.fakegps.integration.v1.FaultCollectorActivity; do
            assert_equal "$(manifest_attr "$xml" activity "$component" android:exported)" '(type 0x12)0xffffffff' "$component debug probe"
        done
        assert_equal "$(manifest_attr "$xml" activity name.caiyao.fakegps.probe.HookAcceptanceActivity android:permission)" "$app_id.permission.RUN_HOOK_ACCEPTANCE" "hook acceptance permission"
        assert_equal "$(manifest_attr "$xml" permission "$app_id.permission.RUN_HOOK_ACCEPTANCE" android:protectionLevel)" '(type 0x11)0x2' "hook acceptance signature declaration"
        assert_equal "$(manifest_attr "$xml" activity name.caiyao.fakegps.mockprovider.MockProviderAcceptanceActivity android:permission)" android.permission.DUMP "mock acceptance shell gate"
    else
        for component in com.example.cellrebelauto.integration.v1.HandshakeProbeActivity com.example.cellrebelauto.integration.v1.FullLoopProbeActivity com.example.cellrebelauto.integration.v1.ProviderRevokeCollectorActivity; do
            assert_equal "$(manifest_attr "$xml" activity "$component" android:exported)" '(type 0x12)0xffffffff' "$component debug probe"
        done
        actual="$(manifest_attr "$xml" package name.caiyao.fakegps.codexbench android:name)"
        assert_equal "$actual" name.caiyao.fakegps.codexbench "Auto provider visibility"
        [ -z "$(manifest_attr "$xml" package name.caiyao.fakegps.bench android:name)" ] || fail "Auto still queries the old bench provider"
    fi
    echo "codex-bench isolation PASS: $app_id | $label"
}

check_apk "$1" name.caiyao.fakegps.codexbench '千网游 · codex-bench' name.caiyao.fakegps.ui.ComposeActivity "$REPO_DIR/apps/qianwangyou/keystores/bench.keystore"
check_apk "$2" com.example.cellrebelauto.codexbench 'CellRebel Auto · codex-bench' com.example.cellrebelauto.ui.MainActivity "$REPO_DIR/apps/cellrebel-auto/keystores/bench.keystore"
echo "Both APKs have isolated codex-bench identities; no device verification is implied."
