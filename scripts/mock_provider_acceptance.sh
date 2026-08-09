#!/usr/bin/env bash
set -euo pipefail

SERIAL="${1:-ZY22JHW9M4}"
REFERENCE_PACKAGE="com.hopefactory2021.fakegpslocation"
PRODUCT_PACKAGE="name.caiyao.fakegps"
BENCH_PACKAGE="name.caiyao.fakegps.bench"
BENCH_LABEL="千网游·测试"
BENCH_ACTIVITY="$BENCH_PACKAGE/name.caiyao.fakegps.ui.ComposeActivity"
ACCEPTANCE_ACTIVITY="$BENCH_PACKAGE/name.caiyao.fakegps.mockprovider.MockProviderAcceptanceActivity"
BENCH_APK="${BENCH_APK:-app/build/outputs/apk/debug/app-debug.apk}"
KYIV_LATITUDE="50.4501"
KYIV_LONGITUDE="30.5234"
OBSERVE_SECONDS="${OBSERVE_SECONDS:-8}"
MOCK_STABILITY_SAMPLES="${MOCK_STABILITY_SAMPLES:-120}"
MOCK_STABILITY_INTERVAL_SECONDS="${MOCK_STABILITY_INTERVAL_SECONDS:-0.5}"
SCREENSHOT_PATH="${SCREENSHOT_PATH:-}"
RECOVERY_SCREENSHOT_PATH="${RECOVERY_SCREENSHOT_PATH:-}"
FIRST_START_SCREENSHOT_PATH="${FIRST_START_SCREENSHOT_PATH:-}"
ADB=(adb -s "$SERIAL")

ui_dump() {
    "${ADB[@]}" exec-out uiautomator dump /dev/tty 2>/dev/null \
        | tr -d '\r' \
        | sed 's/></>\n</g'
}

tap_node() {
    local selector="$1"
    local attempt dump line coordinates x1 y1 x2 y2
    for attempt in $(seq 1 10); do
        dump=$(ui_dump || true)
        line=$(printf '%s\n' "$dump" | awk -v selector="$selector" '
            index($0, selector) && first == "" { first = $0 }
            END { print first }
        ')
        coordinates=$(printf '%s\n' "$line" \
            | sed -nE 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/p')
        if [[ -n "$coordinates" ]]; then
            read -r x1 y1 x2 y2 <<<"$coordinates"
            "${ADB[@]}" shell input tap "$(((x1 + x2) / 2))" "$(((y1 + y2) / 2))"
            echo "UI_TAP selector=$selector"
            return 0
        fi
        sleep 1
    done
    echo "Unable to find visible UI node: $selector" >&2
    return 1
}

tap_node_optional() {
    local selector dump line coordinates x1 y1 x2 y2
    selector="$1"
    dump=$(ui_dump || true)
    line=$(printf '%s\n' "$dump" | awk -v selector="$selector" '
        index($0, selector) && first == "" { first = $0 }
        END { print first }
    ')
    coordinates=$(printf '%s\n' "$line" \
        | sed -nE 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/p')
    if [[ -z "$coordinates" ]]; then
        return 1
    fi
    read -r x1 y1 x2 y2 <<<"$coordinates"
    "${ADB[@]}" shell input tap "$(((x1 + x2) / 2))" "$(((y1 + y2) / 2))"
    echo "UI_TAP_OPTIONAL selector=$selector"
}

wake_and_unlock_device() {
    "${ADB[@]}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    "${ADB[@]}" shell wm dismiss-keyguard >/dev/null 2>&1 || true
}

open_settings() {
    local clean_start="${1:-false}" dump
    wake_and_unlock_device
    if [[ "$clean_start" == "true" ]]; then
        # An unfinished profile import can leave DocumentsUI above ComposeActivity in the same
        # task. A clean first launch prevents that unrelated picker from hiding Settings.
        "${ADB[@]}" shell am force-stop "$BENCH_PACKAGE" >/dev/null 2>&1 || true
        # FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK. Moto's `am start` does not accept
        # the long-form --activity-* switches, while the numeric Intent flag is portable.
        "${ADB[@]}" shell am start --user 0 -W -f 0x10008000 \
            -n "$BENCH_ACTIVITY" >/dev/null
    else
        "${ADB[@]}" shell am start --user 0 -W -n "$BENCH_ACTIVITY" >/dev/null
    fi
    dump=$(ui_dump || true)
    if [[ "$dump" != *'text="系统 Mock 位置"'* ]]; then
        tap_node 'content-desc="菜单"'
        tap_node 'text="设置"'
    fi
}

provider_section() {
    local provider="$1"
    "${ADB[@]}" shell dumpsys location | awk -v target="$provider provider" '
        /^[[:space:]]+[[:alnum:]_-]+ provider( \[mock\])?:/ {
            if (capture) exit
            capture = index($0, target) > 0
        }
        capture { print }
    '
}

gps_section() {
    provider_section gps
}

network_section() {
    provider_section network
}

assert_provider_is_mock() {
    local full section network
    full=$("${ADB[@]}" shell dumpsys location)
    section=$(printf '%s\n' "$full" | awk '
        /^[[:space:]]+[[:alnum:]_-]+ provider( \[mock\])?:/ {
            if (capture) exit
            capture = index($0, "gps provider") > 0
        }
        capture { print }
    ')
    network=$(printf '%s\n' "$full" | awk '
        /^[[:space:]]+[[:alnum:]_-]+ provider( \[mock\])?:/ {
            if (capture) exit
            capture = index($0, "network provider") > 0
        }
        capture { print }
    ')
    printf '%s\n' "$section" | rg -q 'gps provider \[mock\]'
    printf '%s\n' "$section" | rg -q "$BENCH_PACKAGE"
    printf '%s\n' "$network" | rg -q 'network provider \[mock\]'
    printf '%s\n' "$network" | rg -q "$BENCH_PACKAGE"
    printf '%s\n' "$full" | rg -q 'Location\[gps 50\.450100,30\.523400.*mock\]'
    printf '%s\n' "$full" | rg -q 'Location\[network 50\.450100,30\.523400.*mock\]'
    printf '%s\n' "$full" | rg -q 'Location\[fused 50\.450100,30\.523400.*mock\]'
    echo "PROVIDER_MOCK owner=$BENCH_PACKAGE coordinate=$KYIV_LATITUDE,$KYIV_LONGITUDE"
}

assert_mock_stability_over_time() {
    SERIAL="$SERIAL" \
        EXPECTED_LATITUDE="50.450100" \
        EXPECTED_LONGITUDE="30.523400" \
        MOCK_STABILITY_SAMPLES="$MOCK_STABILITY_SAMPLES" \
        MOCK_STABILITY_INTERVAL_SECONDS="$MOCK_STABILITY_INTERVAL_SECONDS" \
        "$(dirname "$0")/assert_fused_mock_stability.sh"
    echo "MOCK_STABILITY_COMPLETE samples=$MOCK_STABILITY_SAMPLES interval=${MOCK_STABILITY_INTERVAL_SECONDS}s"
}

assert_gps_provider_residue_is_mock() {
    local section
    section=$(gps_section)
    printf '%s\n' "$section" | rg -q 'gps provider \[mock\]'
    printf '%s\n' "$section" | rg -q "$BENCH_PACKAGE"
    echo "PROVIDER_MOCK_RESIDUE owner=$BENCH_PACKAGE"
}

assert_fused_mock_cache_cleared() {
    local attempt full fused
    for attempt in $(seq 1 20); do
        full=$("${ADB[@]}" shell dumpsys location)
        fused=$(printf '%s\n' "$full" \
            | grep -m1 -oE 'Location\[fused [^]]+\]' \
            || true)
        if [[ "$fused" != "Location[fused 50.450100,30.523400 "*" mock]" ]]; then
            echo "FUSED_MOCK_CACHE_CLEARED observed=${fused:-missing}"
            return 0
        fi
        sleep 1
    done
    echo "Google FLP still exposes the Kyiv mock cache after Stop" >&2
    echo "$fused" >&2
    return 1
}

assert_provider_is_real() {
    local section network
    section=$(gps_section)
    network=$(network_section)
    printf '%s\n' "$section" | rg -q 'gps provider:'
    printf '%s\n' "$section" | rg -q 'GnssService'
    printf '%s\n' "$network" | rg -q 'network provider:'
    if printf '%s\n' "$section" | rg -q 'gps provider \[mock\]|name\.caiyao\.fakegps'; then
        echo "gps provider is still owned by FakeGPS" >&2
        printf '%s\n' "$section" >&2
        return 1
    fi
    if printf '%s\n' "$network" | rg -q 'network provider \[mock\]|name\.caiyao\.fakegps'; then
        echo "network provider is still owned by FakeGPS" >&2
        printf '%s\n' "$network" >&2
        return 1
    fi
    assert_fused_mock_cache_cleared
    echo "PROVIDER_REAL gps=GnssService network=system"
}

assert_service_is_foreground() {
    local services
    services=$("${ADB[@]}" shell dumpsys activity services "$BENCH_PACKAGE")
    printf '%s\n' "$services" | rg -q 'MockProviderService'
    printf '%s\n' "$services" | rg -q 'isForeground=true'
}

notification_permission_is_granted() {
    "${ADB[@]}" shell dumpsys package "$BENCH_PACKAGE" \
        | rg -q 'android\.permission\.POST_NOTIFICATIONS: granted=true'
}

assert_mock_location_permission_declared() {
    local requested_permissions
    requested_permissions=$("${ADB[@]}" shell dumpsys package "$BENCH_PACKAGE" \
        | sed -n '/requested permissions:/,/install permissions:/p')
    if ! printf '%s\n' "$requested_permissions" \
        | rg -q 'android\.permission\.ACCESS_MOCK_LOCATION'; then
        echo "$BENCH_PACKAGE does not declare ACCESS_MOCK_LOCATION in the installed manifest" >&2
        return 1
    fi
    echo "MOCK_LOCATION_PERMISSION_DECLARED package=$BENCH_PACKAGE"
}

assert_mock_app_listed_in_picker() {
    local attempt dump selector line coordinates x1 y1 x2 y2
    local selectors=(
        'text="选择模拟位置信息应用"'
        'text="选择模拟位置应用"'
        'text="Select mock location app"'
    )

    wake_and_unlock_device
    "${ADB[@]}" shell am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS >/dev/null
    sleep 2

    # Settings may reuse an existing Developer Options activity at an arbitrary scroll position.
    # Normalize to the top, then scan downward for the real system picker row.
    for attempt in $(seq 1 10); do
        "${ADB[@]}" shell input swipe 540 500 540 1900 120 >/dev/null
    done
    for attempt in $(seq 0 15); do
        dump=$(ui_dump || true)
        line=""
        for selector in "${selectors[@]}"; do
            line=$(printf '%s\n' "$dump" | awk -v selector="$selector" '
                index($0, selector) && first == "" { first = $0 }
                END { print first }
            ')
            [[ -n "$line" ]] && break
        done
        if [[ -n "$line" ]]; then
            coordinates=$(printf '%s\n' "$line" \
                | sed -nE 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/p')
            read -r x1 y1 x2 y2 <<<"$coordinates"
            "${ADB[@]}" shell input tap "$(((x1 + x2) / 2))" "$(((y1 + y2) / 2))"
            sleep 2
            break
        fi
        "${ADB[@]}" shell input swipe 540 1900 540 450 250 >/dev/null
        sleep 1
    done
    if [[ -z "$line" ]]; then
        echo "Unable to open the system mock-location app picker" >&2
        return 1
    fi

    for attempt in $(seq 0 8); do
        dump=$(ui_dump || true)
        if [[ "$dump" == *"text=\"$BENCH_LABEL\""* ]] || [[ "$dump" == *"text=\"$BENCH_PACKAGE\""* ]]; then
            echo "MOCK_APP_PICKER_ENTRY_VISIBLE package=$BENCH_PACKAGE label=$BENCH_LABEL"
            "${ADB[@]}" shell input keyevent KEYCODE_BACK >/dev/null
            return 0
        fi
        "${ADB[@]}" shell input swipe 540 1900 540 450 250 >/dev/null
        sleep 1
    done

    echo "$BENCH_PACKAGE is absent from the system mock-location app picker" >&2
    printf '%s\n' "$dump" | rg 'android:id/(title|summary)' >&2 || true
    "${ADB[@]}" shell input keyevent KEYCODE_BACK >/dev/null
    return 1
}

remove_bench_task() {
    local attempt dump line coordinates x1 y1 x2 y2 center_x center_y
    "${ADB[@]}" shell input keyevent KEYCODE_HOME
    "${ADB[@]}" shell input keyevent KEYCODE_APP_SWITCH
    for attempt in $(seq 1 10); do
        dump=$(ui_dump || true)
        line=$(printf '%s\n' "$dump" | awk -v selector="content-desc=\"$BENCH_LABEL\"" '
            index($0, selector) && first == "" { first = $0 }
            END { print first }
        ')
        coordinates=$(printf '%s\n' "$line" \
            | sed -nE 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/p')
        if [[ -n "$coordinates" ]]; then
            read -r x1 y1 x2 y2 <<<"$coordinates"
            center_x=$(((x1 + x2) / 2))
            center_y=$(((y1 + y2) / 2))
            "${ADB[@]}" shell input swipe "$center_x" "$center_y" "$center_x" 100 500
            sleep 2
            dump=$(ui_dump || true)
            if [[ "$dump" == *"content-desc=\"$BENCH_LABEL\""* ]]; then
                echo "Bench task still appears in Recents after swipe" >&2
                return 1
            fi
            echo "TASK_REMOVED label=$BENCH_LABEL"
            return 0
        fi
        sleep 1
    done
    echo "Unable to find Bench task in Recents" >&2
    return 1
}

acceptance_command() {
    local command="$1"
    "${ADB[@]}" shell am start --user 0 -W -n "$ACCEPTANCE_ACTIVITY" \
        --es command "$command" >/dev/null
}

restore() {
    local restore_status=0
    set +e
    if "${ADB[@]}" shell pm path "$BENCH_PACKAGE" >/dev/null 2>&1; then
        # Cleanup needs the same mock app-op that created the provider. Give it back temporarily,
        # invoke the in-process product Stop path, then prove GNSS truth before restoring reference.
        "${ADB[@]}" shell cmd appops set "$REFERENCE_PACKAGE" android:mock_location deny \
            >/dev/null 2>&1 || restore_status=1
        "${ADB[@]}" shell cmd appops set "$BENCH_PACKAGE" android:mock_location allow \
            >/dev/null 2>&1 || restore_status=1
        acceptance_command stop >/dev/null 2>&1 || restore_status=1
        sleep 2
        assert_provider_is_real >/dev/null 2>&1 || restore_status=1
        "${ADB[@]}" shell am force-stop "$BENCH_PACKAGE" >/dev/null 2>&1
        "${ADB[@]}" shell cmd appops set "$BENCH_PACKAGE" android:mock_location deny \
            >/dev/null 2>&1 || restore_status=1
        if [[ "$INITIAL_NOTIFICATION_GRANTED" == "true" ]]; then
            "${ADB[@]}" shell pm grant "$BENCH_PACKAGE" \
                android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || restore_status=1
        else
            "${ADB[@]}" shell pm revoke "$BENCH_PACKAGE" \
                android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || restore_status=1
        fi
    fi
    "${ADB[@]}" shell cmd appops set "$REFERENCE_PACKAGE" android:mock_location allow \
        >/dev/null 2>&1 || restore_status=1
    echo "RESTORE bench=deny reference=allow provider=real status=$restore_status"
    return "$restore_status"
}

"${ADB[@]}" get-state >/dev/null
test -f "$BENCH_APK"

current_mock_app=$("${ADB[@]}" shell cmd appops query-op android:mock_location allow | tr -d '\r')
if [[ "$current_mock_app" != "$REFERENCE_PACKAGE" ]]; then
    echo "Refusing to mutate device: expected $REFERENCE_PACKAGE as the sole mock app; got:"
    echo "$current_mock_app"
    exit 2
fi

assert_provider_is_real
if notification_permission_is_granted; then
    INITIAL_NOTIFICATION_GRANTED=true
else
    INITIAL_NOTIFICATION_GRANTED=false
fi
trap restore EXIT

"${ADB[@]}" install -r "$BENCH_APK"
for package_name in "$REFERENCE_PACKAGE" "$PRODUCT_PACKAGE" "$BENCH_PACKAGE"; do
    "${ADB[@]}" shell pm path "$package_name" | sed "s/^/INSTALLED $package_name /"
done
assert_mock_location_permission_declared
assert_mock_app_listed_in_picker

"${ADB[@]}" shell pm grant "$BENCH_PACKAGE" android.permission.ACCESS_FINE_LOCATION
"${ADB[@]}" shell pm revoke "$BENCH_PACKAGE" android.permission.POST_NOTIFICATIONS \
    >/dev/null 2>&1 || true
"${ADB[@]}" shell pm clear-permission-flags "$BENCH_PACKAGE" \
    android.permission.POST_NOTIFICATIONS user-set user-fixed >/dev/null 2>&1 || true

# The debug-only DUMP-protected seam resets only .bench test data, then saves Kyiv through the
# real ProfileRepository so ConfigPrefsSync and the service consume the normal effective profile.
acceptance_command prepare_kyiv
sleep 2

# First-use boundary: the reference app still owns mock_location, so Bench has never had authority
# and cannot have created a provider. The product must describe selection + switch retry, never a
# fictitious residue or Stop recovery.
open_settings true
tap_node 'checkable="true" checked="false"'
# The product must request notification permission itself. The harness deliberately starts from
# revoked state instead of granting it out of band.
tap_node 'resource-id="com.android.permissioncontroller:id/permission_allow_button"'
sleep 3
notification_permission_is_granted
echo "NOTIFICATION_PERMISSION_GRANTED via=product-runtime-request"
assert_provider_is_real
first_start_dump=$(ui_dump || true)
printf '%s\n' "$first_start_dump" | rg -q 'text="选择当前千网游"'
printf '%s\n' "$first_start_dump" | rg -q '重新打开.*开关'
if printf '%s\n' "$first_start_dump" | rg -q '重试停止|残留位置'; then
    echo "First start permission guidance falsely claims provider cleanup work" >&2
    exit 1
fi
echo "FIRST_START_PERMISSION_GUIDANCE_VISIBLE"
if [[ -n "$FIRST_START_SCREENSHOT_PATH" ]]; then
    "${ADB[@]}" exec-out screencap -p >"$FIRST_START_SCREENSHOT_PATH"
    echo "FIRST_START_SCREENSHOT path=$FIRST_START_SCREENSHOT_PATH"
fi

# A process restart must reconcile to clean Hook intent. This is the durable marker assertion: a
# stale marker would launch StopAndUseHook and recreate the red recovery state here.
"${ADB[@]}" shell am force-stop "$BENCH_PACKAGE"
open_settings true
first_restart_dump=$(ui_dump || true)
printf '%s\n' "$first_restart_dump" | rg -q 'Hook 位置注入'
if printf '%s\n' "$first_restart_dump" | rg -q '重试停止|重新选择当前千网游'; then
    echo "First start permission failure survived process restart as a cleanup error" >&2
    exit 1
fi
assert_provider_is_real
echo "FIRST_START_RESTART_CLEAN"

# Simulate the user following the selection guidance, then retry through the same product switch.
"${ADB[@]}" shell cmd appops set "$REFERENCE_PACKAGE" android:mock_location deny
"${ADB[@]}" shell cmd appops set "$BENCH_PACKAGE" android:mock_location allow
tap_node 'checkable="true" checked="false"'
sleep 3
assert_provider_is_mock

bench_pid=$("${ADB[@]}" shell pidof -s "$BENCH_PACKAGE" | tr -d '\r')
test -n "$bench_pid"
echo "ACTIVE package=$BENCH_PACKAGE pid=$bench_pid"
"${ADB[@]}" shell dumpsys activity services "$BENCH_PACKAGE" \
    | rg -i 'ServiceRecord|MockProviderService|isForeground' \
    | sed -n '1,40p'
gps_section | sed -n '1,80p'
"${ADB[@]}" logcat --pid="$bench_pid" -d -v threadtime \
    | rg 'MockProviderMain' \
    | tail -30

# Removing the launcher task must not silently stop the user-selected System Mock session.
# This is the exact lifecycle regression caused by stopWithTask=true.
remove_bench_task
sleep 2
assert_service_is_foreground
assert_provider_is_mock
echo "ACCEPTANCE_TASK_REMOVAL_PHASE_COMPLETE"

"${ADB[@]}" shell monkey -p com.google.android.apps.maps 1 >/dev/null
sleep "$OBSERVE_SECONDS"
if tap_node_optional 'content-desc="重新将您所在位置设为地图中心"' || \
    tap_node_optional 'content-desc="Re-center map to your location"' || \
    tap_node_optional 'content-desc="Recenter map to your location"'; then
    echo "MAPS_RECENTER tapped"
else
    # When Maps already follows the blue dot it omits the recenter control. Provider truth and the
    # screenshot remain the acceptance evidence; absence of a transient locale-specific button is
    # not a product failure.
    echo "MAPS_RECENTER already-centered-or-control-absent"
fi
sleep 3
echo "MAPS_FOREGROUND coordinate=$KYIV_LATITUDE,$KYIV_LONGITUDE"
"${ADB[@]}" shell dumpsys activity activities \
    | rg 'mResumedActivity|topResumedActivity' \
    | sed -n '1,2p'
if [[ -n "$SCREENSHOT_PATH" ]]; then
    "${ADB[@]}" exec-out screencap -p >"$SCREENSHOT_PATH"
    echo "MAPS_SCREENSHOT path=$SCREENSHOT_PATH"
fi
assert_mock_stability_over_time
echo "ACCEPTANCE_ACTIVE_PHASE_COMPLETE"

# Reproduce the real recovery boundary: Android lets the user select another mock-location app
# while our provider remains installed. Without the original app-op, Stop cannot remove it.
"${ADB[@]}" shell cmd appops set "$BENCH_PACKAGE" android:mock_location deny
"${ADB[@]}" shell cmd appops set "$REFERENCE_PACKAGE" android:mock_location allow
sleep 3
assert_gps_provider_residue_is_mock
open_settings
recovery_dump=$(ui_dump || true)
printf '%s\n' "$recovery_dump" | rg -q 'text="重新选择当前千网游"'
printf '%s\n' "$recovery_dump" | rg -q 'text="重试停止"'
echo "APP_OP_RECOVERY_GUIDANCE_VISIBLE"
if [[ -n "$RECOVERY_SCREENSHOT_PATH" ]]; then
    "${ADB[@]}" exec-out screencap -p >"$RECOVERY_SCREENSHOT_PATH"
    echo "APP_OP_RECOVERY_SCREENSHOT path=$RECOVERY_SCREENSHOT_PATH"
fi

# This simulates the user following the inline Developer Options instruction, then exercising the
# shipped retry button. The app never attempts to grant this app-op itself.
"${ADB[@]}" shell cmd appops set "$REFERENCE_PACKAGE" android:mock_location deny
"${ADB[@]}" shell cmd appops set "$BENCH_PACKAGE" android:mock_location allow
tap_node 'text="重试停止"'
sleep 2
assert_provider_is_real
echo "ACCEPTANCE_APP_OP_RECOVERY_PHASE_COMPLETE"

restore
restored_mock_app=$("${ADB[@]}" shell cmd appops query-op android:mock_location allow | tr -d '\r')
test "$restored_mock_app" = "$REFERENCE_PACKAGE"
assert_provider_is_real

"${ADB[@]}" shell monkey -p "$REFERENCE_PACKAGE" 1 >/dev/null
sleep 2
echo "REFERENCE_APP_FOREGROUND"
"${ADB[@]}" shell dumpsys activity activities \
    | rg 'mResumedActivity|topResumedActivity' \
    | rg "$REFERENCE_PACKAGE" \
    | sed -n '1,2p'
echo "ACCEPTANCE_RESTORE_PHASE_COMPLETE"
trap - EXIT
