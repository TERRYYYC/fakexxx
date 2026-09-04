#!/usr/bin/env bash
# Deterministic fake adb for selftest-issue66-moto-readonly-collector.sh.
# It has no transport and cannot contact a device. Any mutating command is
# rejected here as a second safety boundary in addition to collector policy.

set -uo pipefail

scenario="${FAKE_ADB_SCENARIO:-target}"
log="${FAKE_ADB_LOG:?FAKE_ADB_LOG must name a selftest log}"
work_root="${SELFTEST_WORK_ROOT:?SELFTEST_WORK_ROOT must name the private selftest root}"
real_python="${SELFTEST_REAL_PYTHON:?SELFTEST_REAL_PYTHON is required}"
authorized="ZY22JHW9M4"
known_packages=(
  "name.caiyao.fakegps"
  "name.caiyao.fakegps.bench"
  "name.caiyao.fakegps.codexbench"
  "com.example.cellrebelauto"
  "com.example.cellrebelauto.codexbench"
  "com.cellrebel.mobile"
)
missing_fixture_package="com.cellrebel.mobile"

# Fault injection deliberately replaces files/directories. Refuse every
# caller-controlled fixture path unless its resolved location remains beneath
# the selftest's freshly-created private root.
controlled_paths=("$log")
for variable_name in \
  FAKE_ADB_REPLACEMENT \
  FAKE_ADB_REPLACE_MARKER \
  FAKE_ADB_REPLACE_SOURCE \
  FAKE_ADB_SNAPSHOT_REPLACE_MARKER \
  FAKE_ADB_SWAP_MARKER \
  FAKE_ADB_SWAP_OUTPUT \
  FAKE_ADB_SWAP_TARGET; do
  value=${!variable_name-}
  [[ -n $value ]] && controlled_paths+=("$value")
done
"$real_python" -I - "$work_root" "${controlled_paths[@]}" <<'PY' \
  || { printf 'fake adb safety stop: fixture path escaped WORK\n' >&2; exit 99; }
import pathlib
import sys

root = pathlib.Path(sys.argv[1]).resolve(strict=True)
for raw in sys.argv[2:]:
    candidate = pathlib.Path(raw).resolve(strict=False)
    try:
        relative = candidate.relative_to(root)
    except ValueError:
        raise SystemExit(1)
    if relative == pathlib.Path("."):
        raise SystemExit(1)
PY

is_known_package() {
  local candidate="$1" package
  for package in "${known_packages[@]}"; do
    [ "$candidate" = "$package" ] && return 0
  done
  return 1
}

emit_fixture_archive() { # apk|services [package]
  "$real_python" -I - "$1" "${2-}" "$scenario" <<'PY'
import io
import struct
import sys
import warnings
import zipfile

kind, package, scenario = sys.argv[1:]
targeted_apk = kind == "apk" and package == "name.caiyao.fakegps"
buffer = io.BytesIO()
warnings.simplefilter("ignore", UserWarning)
with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_STORED) as archive:
    def add(name, data):
        member = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
        member.external_attr = 0o100600 << 16
        archive.writestr(member, data)
    if kind == "apk":
        if not (targeted_apk and scenario == "apk-empty-archive"):
            manifest_name = (
                "AndroidManifest.xmlX"
                if targeted_apk and scenario == "apk-nul-member"
                else "AndroidManifest.xml"
            )
            if scenario != "apk-missing-manifest" or not targeted_apk:
                add(manifest_name, f"ISSUE66_MANIFEST:{package}".encode("ascii"))
            add("classes.dex", b"dex\n035\0ISSUE66_APK_FIXTURE")
            if targeted_apk and scenario == "apk-duplicate-member":
                add("classes.dex", b"dex\n035\0ISSUE66_DUPLICATE")
            elif targeted_apk and scenario == "apk-parent-member":
                add("../escape", b"ISSUE66_UNSAFE_PARENT")
            elif targeted_apk and scenario == "apk-absolute-member":
                add("/escape", b"ISSUE66_UNSAFE_ABSOLUTE")
    elif kind == "services":
        add("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\r\n\r\n")
        if scenario != "services-missing-dex":
            dex_name = "classes.dexX" if scenario == "services-nul-member" else "classes.dex"
            add(dex_name, b"dex\n035\0ISSUE66_SERVICES_FIXTURE")
    else:
        raise SystemExit(2)
data = bytearray(buffer.getvalue())
if targeted_apk and scenario == "apk-nul-member":
    data = bytearray(bytes(data).replace(b"AndroidManifest.xmlX", b"AndroidManifest.xml\0"))
if kind == "services" and scenario == "services-nul-member":
    data = bytearray(bytes(data).replace(b"classes.dexX", b"classes.dex\0"))
if (targeted_apk and scenario == "apk-crc-corrupt") or (
    kind == "services" and scenario == "services-crc-corrupt"
):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        member = next(item for item in archive.infolist() if item.file_size)
    name_length, extra_length = struct.unpack_from("<HH", data, member.header_offset + 26)
    data_offset = member.header_offset + 30 + name_length + extra_length
    data[data_offset] ^= 0x01
sys.stdout.buffer.write(data)
PY
}

printf '%s\n' "$*" >>"$log"

# Deterministic TOCTOU fixture: replace the caller-selected source pathname
# after its first execution. A collector that repeatedly resolves that source
# path will hit the poison on its second command; a private byte snapshot will
# continue executing the already-copied fixture.
if [ -n "${FAKE_ADB_REPLACE_SOURCE:-}" ] \
    && [ ! -e "${FAKE_ADB_REPLACE_MARKER:-}" ]; then
  : >"${FAKE_ADB_REPLACE_MARKER:?FAKE_ADB_REPLACE_MARKER is required}"
  cp "${FAKE_ADB_REPLACEMENT:?FAKE_ADB_REPLACEMENT is required}" \
    "$FAKE_ADB_REPLACE_SOURCE.next" || exit 94
  chmod +x "$FAKE_ADB_REPLACE_SOURCE.next" || exit 94
  mv -f "$FAKE_ADB_REPLACE_SOURCE.next" "$FAKE_ADB_REPLACE_SOURCE" || exit 94
fi

if [ -n "${FAKE_ADB_SWAP_OUTPUT:-}" ] \
    && [ ! -e "${FAKE_ADB_SWAP_MARKER:-}" ]; then
  : >"${FAKE_ADB_SWAP_MARKER:?FAKE_ADB_SWAP_MARKER is required}"
  mv "$FAKE_ADB_SWAP_OUTPUT" "$FAKE_ADB_SWAP_OUTPUT.detached" || exit 93
  ln -s "${FAKE_ADB_SWAP_TARGET:?FAKE_ADB_SWAP_TARGET is required}" \
    "$FAKE_ADB_SWAP_OUTPUT" || exit 93
fi

if [ "$scenario" = snapshot-self-replace ] \
    && [ ! -e "${FAKE_ADB_SNAPSHOT_REPLACE_MARKER:-}" ]; then
  : >"${FAKE_ADB_SNAPSHOT_REPLACE_MARKER:?FAKE_ADB_SNAPSHOT_REPLACE_MARKER is required}"
  chmod 700 tooling || exit 92
  cp "${FAKE_ADB_REPLACEMENT:?FAKE_ADB_REPLACEMENT is required}" tooling/adb.next || exit 92
  chmod 500 tooling/adb.next || exit 92
  mv -f tooling/adb.next tooling/adb || exit 92
  chmod 500 tooling || exit 92
fi

joined=" $* "
mutating=0
case "$joined" in
  *" install "*|*" install-multiple "*|*" uninstall "*|*" push "*) mutating=1 ;;
  *" root "*|*" reboot "*|*" remount "*|*" disable-verity "*|*" enable-verity "*) mutating=1 ;;
  *" shell settings put "*|*" shell settings delete "*) mutating=1 ;;
  *" shell appops set "*|*" shell appops reset "*) mutating=1 ;;
  *" shell am force-stop "*|*" shell am crash "*) mutating=1 ;;
  *" shell pm clear "*|*" shell kill "*|*" shell pkill "*) mutating=1 ;;
  *" shell stop "*|*" shell start "*|*" shell sqlite3 "*) mutating=1 ;;
  *" set-location-enabled "*|*" add-test-provider "*|*" set-test-provider "*) mutating=1 ;;
  *" shell su "*|*" logcat "*|*" dumpsys location "*|*" am start "*) mutating=1 ;;
esac
if [ "$mutating" -ne 0 ]; then
  printf 'fake adb safety stop: mutating command denied: %s\n' "$*" >&2
  exit 97
fi

if [ "$#" -eq 2 ] && [ "$1" = devices ] && [ "$2" = -l ]; then
  if [ "$scenario" = devices-exit7 ]; then
    printf 'fixture devices inventory transport failure\n' >&2
    exit 7
  fi
  printf 'List of devices attached\n'
  case "$scenario" in
    missing-target) ;;
    extra-device)
      printf '%-22s %s product:cancunf model:moto_g54_5G device:cancunf transport_id:1\n' "$authorized" device
      printf '%-22s %s product:other model:other device:other transport_id:2\n' EXTRA_DEVICE device
      ;;
    extra-offline)
      printf '%-22s %s product:cancunf model:moto_g54_5G device:cancunf transport_id:1\n' "$authorized" device
      printf '%-22s %s transport_id:2\n' OFFLINE_DEVICE offline
      ;;
    extra-unauthorized)
      printf '%-22s %s product:cancunf model:moto_g54_5G device:cancunf transport_id:1\n' "$authorized" device
      printf '%-22s %s usb:1-1 transport_id:2\n' UNAUTHORIZED_DEVICE unauthorized
      ;;
    extra-emulator)
      printf '%-22s %s product:cancunf model:moto_g54_5G device:cancunf transport_id:1\n' "$authorized" device
      printf '%-22s %s product:sdk_gphone64_arm64 model:sdk_gphone64_arm64 device:emu64a transport_id:2\n' emulator-5554 device
      ;;
    devices-hidden-cr)
      printf '%-22s %s transport_id:1\r%-22s %s transport_id:2\n' \
        "$authorized" device EXTRA_DEVICE device
      ;;
    *)
      printf '%-22s %s product:cancunf model:moto_g54_5G device:cancunf transport_id:1\n' "$authorized" device
      ;;
  esac
  printf '\n'
  exit 0
fi

if [ "$#" -lt 3 ] || [ "$1" != -s ] || [ "$2" != "$authorized" ]; then
  printf 'fake adb scope stop: expected -s %s, got: %s\n' "$authorized" "$*" >&2
  exit 96
fi
shift 2

if [ "$#" -eq 4 ] && [ "$1" = shell ] && [ "$2" = pm ] && [ "$3" = path ]; then
  package="$4"
  is_known_package "$package" || { printf 'unknown fixture package: %s\n' "$package" >&2; exit 98; }
  if [ "$scenario" = missing-package-stderr ] && [ "$package" = "$missing_fixture_package" ]; then
    printf 'Unknown package: %s\n' "$package" >&2
    exit 1
  fi
  if [ "$scenario" = missing-package ] && [ "$package" = "$missing_fixture_package" ]; then
    exit 1
  fi
  if [ "$package" = name.caiyao.fakegps.codexbench ]; then
    case "$scenario" in
      split-package)
        printf 'package:/data/app/~~issue66/%s-fixture/base.apk\n' "$package"
        printf 'package:/data/app/~~issue66/%s-fixture/split_config.arm64_v8a.apk\n' "$package"
        exit 0
        ;;
      pm-path-stderr)
        printf 'fixture package-manager warning\n' >&2
        printf 'package:/data/app/~~issue66/%s-fixture/base.apk\n' "$package"
        exit 0
        ;;
      unsafe-pm-path-injection)
        printf 'package:/data/app/~~issue66/%s-fixture/base.apk;id\n' "$package"
        exit 0
        ;;
      unsafe-pm-path-multiple)
        printf 'package:/data/app/~~issue66/%s-fixture/base.apk\n' "$package"
        printf 'package:/data/app/~~other/%s-other/base.apk\n' "$package"
        exit 0
        ;;
      unsafe-pm-path-wrong-package)
        printf 'package:/data/app/~~issue66/other.package-fixture/base.apk\n'
        exit 0
        ;;
      unsafe-pm-path-dot)
        printf 'package:/data/app/./%s-fixture/base.apk\n' "$package"
        exit 0
        ;;
      unsafe-pm-path-dotdot)
        printf 'package:/data/app/../%s-fixture/base.apk\n' "$package"
        exit 0
        ;;
    esac
  fi
  printf 'package:/data/app/~~issue66/%s-fixture/base.apk\n' "$package"
  exit 0
fi

if [ "$#" -eq 4 ] && [ "$1" = shell ] && [ "$2" = dumpsys ] && [ "$3" = package ]; then
  package="$4"
  is_known_package "$package" || { printf 'unknown fixture package: %s\n' "$package" >&2; exit 98; }
  if [ "$package" = name.caiyao.fakegps ]; then
    case "$scenario" in
      dumpsys-malformed)
        printf 'Package [other.package] userId=10208\n'
        exit 0
        ;;
      dumpsys-failure)
        printf 'fixture dumpsys transport failure\n' >&2
        exit 7
        ;;
    esac
  fi
  printf 'Package [%s] userId=10208\n  versionCode=1\n' "$package"
  exit 0
fi

if [ "$#" -eq 3 ] && [ "$1" = shell ] && [ "$2" = pidof ]; then
  package="$3"
  is_known_package "$package" || { printf 'unknown fixture package: %s\n' "$package" >&2; exit 98; }
  if [ "$package" = name.caiyao.fakegps ]; then
    case "$scenario" in
      pidof-malformed)
        printf 'pid=not-a-decimal\n'
        exit 0
        ;;
      pidof-failure)
        printf 'fixture pidof transport failure\n' >&2
        exit 7
        ;;
      pidof-not-running) exit 1 ;;
    esac
  fi
  if [ "$scenario" = missing-package ] && [ "$package" = "$missing_fixture_package" ]; then
    exit 1
  fi
  printf '12008\n'
  exit 0
fi

if [ "$#" -ge 4 ] && [ "$1" = shell ] && [ "$2" = appops ] && [ "$3" = get ]; then
  package=""
  if [ "$#" -eq 7 ] && [ "$4" = --user ] && [ "$5" = 0 ] \
      && [ "$7" = android:mock_location ]; then
    package="$6"
  elif [ "$#" -eq 4 ]; then
    # Kept only so the production classifier/audit, not fixture availability,
    # supplies the focused RED for the obsolete broad argv.
    package="$4"
  fi
  [ -n "$package" ] || { printf 'fake adb: malformed appops argv: %s\n' "$*" >&2; exit 98; }
  is_known_package "$package" || { printf 'unknown fixture package: %s\n' "$package" >&2; exit 98; }
  if [ "$package" = name.caiyao.fakegps ]; then
    case "$scenario" in
      appops-malformed)
        printf 'FINE_LOCATION: allow\n'
        exit 0
        ;;
      appops-conflict)
        printf 'MOCK_LOCATION: allow\n'
        printf 'MOCK_LOCATION: deny\n'
        exit 0
        ;;
      appops-conflict-inline)
        printf 'MOCK_LOCATION: allow; MOCK_LOCATION: deny\n'
        exit 0
        ;;
      appops-public-op-name)
        printf 'android:mock_location: allow\n'
        exit 0
        ;;
      appops-op-name-wrong-case)
        printf 'mock_location: allow\n'
        exit 0
        ;;
      appops-mode-wrong-case)
        printf 'MOCK_LOCATION: ALLOW\n'
        exit 0
        ;;
      appops-mode-default)
        printf 'MOCK_LOCATION: default\n'
        exit 0
        ;;
      appops-mode-foreground)
        printf 'MOCK_LOCATION: foreground\n'
        exit 0
        ;;
      appops-unknown-mode)
        printf 'MOCK_LOCATION: mode=5\n'
        exit 0
        ;;
      appops-leading-space)
        printf ' MOCK_LOCATION: allow\n'
        exit 0
        ;;
      appops-trailing-space)
        printf 'MOCK_LOCATION: allow \n'
        exit 0
        ;;
      appops-multiple-space)
        printf 'MOCK_LOCATION:  allow\n'
        exit 0
        ;;
      appops-error-tail)
        printf 'MOCK_LOCATION: allow; Error: transport failed\n'
        exit 0
        ;;
      appops-default-tail)
        printf 'MOCK_LOCATION: allow; Default mode: deny\n'
        exit 0
        ;;
      appops-metadata)
        printf 'MOCK_LOCATION: allow; time=+1m2s0ms ago; rejectTime=+3m4s0ms ago; duration=+5s0ms\n'
        exit 0
        ;;
      appops-running)
        printf 'MOCK_LOCATION: allow; time=+1m2s0ms ago (running)\n'
        exit 0
        ;;
      appops-bogus-time)
        printf 'MOCK_LOCATION: allow; time=error transport failed\n'
        exit 0
        ;;
      appops-tab-spacing)
        printf 'MOCK_LOCATION:\tallow\n'
        exit 0
        ;;
      appops-time-without-ago)
        printf 'MOCK_LOCATION: allow; time=+1s0ms\n'
        exit 0
        ;;
      appops-reject-without-ago)
        printf 'MOCK_LOCATION: allow; rejectTime=+1s0ms\n'
        exit 0
        ;;
      appops-duration-with-ago)
        printf 'MOCK_LOCATION: allow; time=+1ms ago; duration=+5s0ms ago\n'
        exit 0
        ;;
      appops-duration-negative)
        printf 'MOCK_LOCATION: allow; time=+1ms ago; duration=-1ms\n'
        exit 0
        ;;
      appops-duration-running)
        printf 'MOCK_LOCATION: allow; time=+1ms ago (running); duration=+5s0ms\n'
        exit 0
        ;;
      appops-duration-running-reverse)
        printf 'MOCK_LOCATION: allow; time=+1ms ago; duration=+5s0ms (running)\n'
        exit 0
        ;;
      appops-orphan-duration)
        printf 'MOCK_LOCATION: allow; duration=+1ms\n'
        exit 0
        ;;
      appops-orphan-running)
        printf 'MOCK_LOCATION: allow (running)\n'
        exit 0
        ;;
      appops-wrong-order)
        printf 'MOCK_LOCATION: allow; time=+1s0ms ago; duration=+5s0ms; rejectTime=+2s0ms ago\n'
        exit 0
        ;;
      appops-metadata-tab)
        printf 'MOCK_LOCATION: allow;\ttime=+1s0ms ago\n'
        exit 0
        ;;
      appops-duration-missing-ms)
        printf 'MOCK_LOCATION: allow; time=+1ms ago; duration=+1s\n'
        exit 0
        ;;
      appops-time-missing-ms)
        printf 'MOCK_LOCATION: allow; time=+1s ago\n'
        exit 0
        ;;
      appops-reject-missing-ms)
        printf 'MOCK_LOCATION: allow; rejectTime=+1s ago\n'
        exit 0
        ;;
      appops-duration-gap)
        printf 'MOCK_LOCATION: allow; time=+1d0ms ago\n'
        exit 0
        ;;
      appops-duration-range)
        printf 'MOCK_LOCATION: allow; time=+24h0m0s0ms ago\n'
        exit 0
        ;;
      appops-duration-minute-range)
        printf 'MOCK_LOCATION: allow; time=+60m0s0ms ago\n'
        exit 0
        ;;
      appops-duration-second-range)
        printf 'MOCK_LOCATION: allow; time=+60s0ms ago\n'
        exit 0
        ;;
      appops-duration-residual-hour-range)
        printf 'MOCK_LOCATION: allow; time=+1d24h0m0s0ms ago\n'
        exit 0
        ;;
      appops-duration-residual-minute-range)
        printf 'MOCK_LOCATION: allow; time=+1h60m0s0ms ago\n'
        exit 0
        ;;
      appops-duration-residual-second-range)
        printf 'MOCK_LOCATION: allow; time=+1m60s0ms ago\n'
        exit 0
        ;;
      appops-duration-ms-range)
        printf 'MOCK_LOCATION: allow; time=+1s1000ms ago\n'
        exit 0
        ;;
      appops-duration-top-ms-range)
        printf 'MOCK_LOCATION: allow; time=+1000ms ago\n'
        exit 0
        ;;
      appops-duration-day-overflow)
        printf 'MOCK_LOCATION: allow; time=+24856d0h0m0s0ms ago\n'
        exit 0
        ;;
      appops-duration-day-residual-overflow)
        printf 'MOCK_LOCATION: allow; time=+24855d3h14m8s0ms ago\n'
        exit 0
        ;;
      appops-duration-hour-gap)
        printf 'MOCK_LOCATION: allow; time=+1h0s0ms ago\n'
        exit 0
        ;;
      appops-duration-minute-gap)
        printf 'MOCK_LOCATION: allow; time=+1m0ms ago\n'
        exit 0
        ;;
      appops-duration-day-missing-hour)
        printf 'MOCK_LOCATION: allow; time=+1d0m0s0ms ago\n'
        exit 0
        ;;
      appops-duration-day-missing-minute)
        printf 'MOCK_LOCATION: allow; time=+1d0h0s0ms ago\n'
        exit 0
        ;;
      appops-duration-day-missing-second)
        printf 'MOCK_LOCATION: allow; time=+1d0h0m0ms ago\n'
        exit 0
        ;;
      appops-duration-hour-missing-second)
        printf 'MOCK_LOCATION: allow; time=+1h0m0ms ago\n'
        exit 0
        ;;
      appops-duration-leading-zero)
        printf 'MOCK_LOCATION: allow; time=+1s000ms ago\n'
        exit 0
        ;;
      appops-duration-day-leading-zero)
        printf 'MOCK_LOCATION: allow; time=+01d0h0m0s0ms ago\n'
        exit 0
        ;;
      appops-duration-hour-leading-zero)
        printf 'MOCK_LOCATION: allow; time=+01h0m0s0ms ago\n'
        exit 0
        ;;
      appops-duration-minute-leading-zero)
        printf 'MOCK_LOCATION: allow; time=+01m0s0ms ago\n'
        exit 0
        ;;
      appops-duration-second-leading-zero)
        printf 'MOCK_LOCATION: allow; time=+01s0ms ago\n'
        exit 0
        ;;
      appops-duration-residual-hour-leading-zero)
        printf 'MOCK_LOCATION: allow; time=+1d00h0m0s0ms ago\n'
        exit 0
        ;;
      appops-duration-residual-minute-leading-zero)
        printf 'MOCK_LOCATION: allow; time=+1h00m0s0ms ago\n'
        exit 0
        ;;
      appops-duration-residual-second-leading-zero)
        printf 'MOCK_LOCATION: allow; time=+1m00s0ms ago\n'
        exit 0
        ;;
      appops-duration-top-ms-leading-zero)
        printf 'MOCK_LOCATION: allow; time=+01ms ago\n'
        exit 0
        ;;
      appops-duration-signed-zero)
        printf 'MOCK_LOCATION: allow; time=+0ms ago\n'
        exit 0
        ;;
      appops-duration-negative-signed-zero)
        printf 'MOCK_LOCATION: allow; time=-0ms ago\n'
        exit 0
        ;;
      appops-time-unsigned)
        printf 'MOCK_LOCATION: allow; time=1ms ago\n'
        exit 0
        ;;
      appops-elapsed-unsigned)
        printf 'MOCK_LOCATION: allow; time=+1ms ago; duration=1ms\n'
        exit 0
        ;;
      appops-elapsed-signed-zero)
        printf 'MOCK_LOCATION: allow; time=+1ms ago; duration=+0ms\n'
        exit 0
        ;;
      appops-duration-unicode-digit)
        printf 'MOCK_LOCATION: allow; time=+1s1\331\241ms ago\n'
        exit 0
        ;;
      appops-unicode-op-name)
        printf 'MOC\342\204\252_LOCATION: allow\n'
        exit 0
        ;;
      appops-reject-before-time)
        printf 'MOCK_LOCATION: allow; rejectTime=+2s0ms ago; time=+1s0ms ago\n'
        exit 0
        ;;
      appops-duplicate-time)
        printf 'MOCK_LOCATION: allow; time=+1s0ms ago; time=+2s0ms ago\n'
        exit 0
        ;;
      appops-duplicate-reject)
        printf 'MOCK_LOCATION: allow; rejectTime=+1ms ago; rejectTime=+2ms ago\n'
        exit 0
        ;;
      appops-duplicate-duration)
        printf 'MOCK_LOCATION: allow; time=+1ms ago; duration=+1ms; duration=+2ms\n'
        exit 0
        ;;
      appops-duplicate-running)
        printf 'MOCK_LOCATION: allow; time=+1ms ago (running) (running)\n'
        exit 0
        ;;
      appops-canonical-boundaries)
        printf 'MOCK_LOCATION: allow; time=+1d0h0m0s0ms ago; rejectTime=+1h0m0s0ms ago; duration=+1ms\n'
        exit 0
        ;;
      appops-canonical-zero-negative)
        printf 'MOCK_LOCATION: allow; time=0 ago; rejectTime=-1ms ago; duration=0\n'
        exit 0
        ;;
      appops-no-operations)
        printf 'No operations.\nDefault mode: deny\n'
        exit 0
        ;;
      appops-uid-mode)
        printf 'Uid mode: MOCK_LOCATION: default\n'
        exit 0
        ;;
      appops-uid-allow)
        printf 'Uid mode: MOCK_LOCATION: allow\n'
        exit 0
        ;;
      appops-uid-foreground)
        printf 'Uid mode: MOCK_LOCATION: foreground\n'
        exit 0
        ;;
      appops-uid-and-package)
        printf 'Uid mode: MOCK_LOCATION: ignore\nMOCK_LOCATION: allow; time=+1ms ago; duration=+1ms\n'
        exit 0
        ;;
      appops-uid-default-deny)
        printf 'Uid mode: MOCK_LOCATION: deny\n'
        exit 0
        ;;
      appops-uid-after-package)
        printf 'MOCK_LOCATION: allow\nUid mode: MOCK_LOCATION: ignore\n'
        exit 0
        ;;
      appops-duplicate-uid)
        printf 'Uid mode: MOCK_LOCATION: ignore\nUid mode: MOCK_LOCATION: default\n'
        exit 0
        ;;
      appops-uid-metadata)
        printf 'Uid mode: MOCK_LOCATION: ignore; time=+1ms ago\n'
        exit 0
        ;;
      appops-uid-wrong-operation)
        printf 'Uid mode: FINE_LOCATION: allow\n'
        exit 0
        ;;
      appops-no-operations-wrong-default)
        printf 'No operations.\nDefault mode: ignore\n'
        exit 0
        ;;
      appops-no-operations-missing-default)
        printf 'No operations.\n'
        exit 0
        ;;
      appops-no-operations-extra-line)
        printf 'No operations.\nDefault mode: deny\nextra\n'
        exit 0
        ;;
      appops-no-operations-wrong-case)
        printf 'no operations.\nDefault mode: deny\n'
        exit 0
        ;;
      appops-no-operations-spacing)
        printf 'No operations.\nDefault mode:  deny\n'
        exit 0
        ;;
      appops-missing-newline)
        printf 'MOCK_LOCATION: allow'
        exit 0
        ;;
      appops-crlf)
        printf 'No operations.\r\nDefault mode: deny\r\n'
        exit 0
        ;;
      appops-canonical-minute-second)
        printf 'MOCK_LOCATION: foreground; time=+59m59s999ms ago; rejectTime=+59s999ms ago; duration=+999ms\n'
        exit 0
        ;;
      appops-reject-only)
        printf 'MOCK_LOCATION: deny; rejectTime=+1ms ago\n'
        exit 0
        ;;
      appops-time-only)
        printf 'MOCK_LOCATION: allow; time=+1ms ago\n'
        exit 0
        ;;
      appops-time-reject-only)
        printf 'MOCK_LOCATION: allow; time=+1ms ago; rejectTime=+2ms ago\n'
        exit 0
        ;;
      appops-time-reject-running)
        printf 'MOCK_LOCATION: allow; time=+1ms ago; rejectTime=+2ms ago (running)\n'
        exit 0
        ;;
      appops-elapsed-day)
        printf 'MOCK_LOCATION: allow; time=+1ms ago; duration=+1d0h0m0s0ms\n'
        exit 0
        ;;
      appops-elapsed-hour)
        printf 'MOCK_LOCATION: allow; time=+1ms ago; duration=+1h0m0s0ms\n'
        exit 0
        ;;
      appops-elapsed-minute)
        printf 'MOCK_LOCATION: allow; time=+1ms ago; duration=+1m0s0ms\n'
        exit 0
        ;;
      appops-canonical-day-hour-boundaries)
        printf 'MOCK_LOCATION: allow; time=+12d0h0m0s0ms ago; rejectTime=+1d23h0m0s0ms ago; duration=+23h0m0s0ms\n'
        exit 0
        ;;
      appops-canonical-max-duration)
        printf 'MOCK_LOCATION: allow; time=+24855d3h14m7s999ms ago\n'
        exit 0
        ;;
      appops-lone-cr)
        printf 'MOCK_LOCATION: allow\r'
        exit 0
        ;;
      appops-failure)
        printf 'fixture appops transport failure\n' >&2
        exit 7
        ;;
    esac
  fi
  printf 'MOCK_LOCATION: ignore\n'
  exit 0
fi

if [ "$#" -eq 3 ] && [ "$1" = exec-out ] && [ "$2" = cat ]; then
  path="$3"
  if [ "$path" = /system/framework/services.jar ]; then
    if [ "$scenario" = services-exit13 ]; then
      printf 'fixture services.jar transport failure\n' >&2
      exit 13
    fi
    if [ "$scenario" = services-truncated ]; then
      printf 'P'
      exit 0
    fi
    emit_fixture_archive services
    exit 0
  fi
  for package in "${known_packages[@]}"; do
    if [ "$path" = "/data/app/~~issue66/$package-fixture/base.apk" ]; then
      if [ "$scenario" = apk-truncated ] && [ "$package" = name.caiyao.fakegps ]; then
        printf 'P'
        exit 0
      fi
      emit_fixture_archive apk "$package"
      exit 0
    fi
  done
  printf 'fake adb: unsafe/unhandled exec-out path: %s\n' "$path" >&2
  exit 98
fi

case "$*" in
  "get-state") printf 'device\n' ;;
  "shell getprop ro.serialno")
    case "$scenario" in
      wrong-live-serial) printf 'OTHER_SERIAL\n' ;;
      empty-live-serial) printf '\n' ;;
      serial-multiline) printf 'ZY22\nJHW9M4\n' ;;
      serial-nul) printf 'ZY22JH\000W9M4\n' ;;
      serial-extra-lf) printf '%s\n\n' "$authorized" ;;
      serial-edge-space) printf ' %s \n' "$authorized" ;;
      *) printf '%s\n' "$authorized" ;;
    esac
    ;;
  "shell getprop ro.product.manufacturer")
    if [ "$scenario" = wrong-manufacturer ]; then printf 'Google\n'; else printf 'motorola\n'; fi
    ;;
  "shell getprop ro.build.version.sdk")
    case "$scenario" in
      wrong-api) printf '34\n' ;;
      api-multiline) printf '3\n5\n' ;;
      *) printf '35\n' ;;
    esac
    ;;
  "shell getprop ro.build.fingerprint")
    if [ "$scenario" = incomplete-core ]; then
      printf '\n'
    elif [ "$scenario" = fingerprint-control ]; then
      printf '\t\n'
    elif [ "$scenario" = fingerprint-c1 ]; then
      printf '\302\205\n'
    elif [ "$scenario" = fingerprint-line-separator ]; then
      printf '\342\200\250\n'
    elif [ "$scenario" = fingerprint-exit7 ]; then
      printf 'fixture fingerprint transport failure\n' >&2
      exit 7
    else
      printf 'motorola/cancunf_g/cancunf:15/V1/1:user/release-keys\n'
    fi
    ;;
  "shell getprop ro.product.model") printf 'moto g54 5G\n' ;;
  "shell getprop ro.product.device") printf 'cancunf\n' ;;
  "shell getprop ro.build.version.release") printf '15\n' ;;
  "shell getprop ro.product.cpu.abilist") printf 'arm64-v8a,armeabi-v7a\n' ;;
  "shell getprop ro.zygote") printf 'zygote64_32\n' ;;
  "shell getprop sys.boot_completed") printf '1\n' ;;
  "shell getenforce")
    case "$scenario" in
      selinux-malformed) printf 'Maybe\n' ;;
      selinux-extra-line) printf 'Enforcing\n\n' ;;
      selinux-embedded-cr) printf 'Enfor\rcing\n' ;;
      selinux-lone-cr) printf 'Enforcing\r' ;;
      *) printf 'Enforcing\n' ;;
    esac
    ;;
  "shell am get-current-user")
    if [ "$scenario" = current-user-nonzero ]; then printf '10\n'; else printf '0\n'; fi
    ;;
  "shell ps -A"|"shell ps -A -o USER,PID,NAME")
    case "$scenario" in
      process-header-malformed)
        printf 'USER PPID NAME\n'
        printf 'root 1 init\n'
        ;;
      process-row-malformed)
        printf 'USER PID NAME\n'
        printf 'root not-a-pid init\n'
        ;;
      process-crlf)
        printf '%-12s %6s %-27s\r\n' USER PID NAME
        printf 'root 1 init\r\n'
        printf 'u0_a208 12008 name.caiyao.fakegps.codexbench\r\n'
        printf 'root 777 lspd\r\n'
        ;;
      *)
        # Android 15 toybox renders selected columns at configured widths.
        printf '%-12s %6s %-27s\n' USER PID NAME
        printf 'root 1 init\n'
        printf 'u0_a208 12008 name.caiyao.fakegps.codexbench\n'
        printf 'root 777 lspd\n'
        ;;
    esac
    ;;
  "shell cmd location is-location-enabled --user 0") printf 'true\n' ;;
  "shell cat /proc/sys/kernel/random/boot_id")
    boot_reads="$(grep -F -c -- "shell cat /proc/sys/kernel/random/boot_id" "$log" || true)"
    if [ "$scenario" = boot-id-malformed ]; then
      printf 'not-a-canonical-boot-id\n'
    elif [ "$scenario" = boot-changed ] && [ "$boot_reads" -ge 2 ]; then
      printf 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\n'
    else
      printf '11111111-2222-3333-4444-555555555555\n'
    fi
    ;;
  "shell cat /proc/uptime")
    uptime_reads="$(grep -F -c -- "shell cat /proc/uptime" "$log" || true)"
    case "$scenario" in
      uptime-negative)
        if [ "$uptime_reads" -ge 2 ]; then printf '1.00 999.00\n'; else printf '%s\n' '-1.00 998.00'; fi
        ;;
      uptime-nonfinite)
        if [ "$uptime_reads" -ge 2 ]; then printf '12346.00 999.00\n'; else printf 'nan 998.00\n'; fi
        ;;
      uptime-decreased)
        if [ "$uptime_reads" -ge 2 ]; then printf '12344.00 999.00\n'; else printf '12345.67 998.00\n'; fi
        ;;
      *)
        if [ "$uptime_reads" -ge 2 ]; then printf '12346.00 999.00\n'; else printf '12345.67 998.00\n'; fi
        ;;
    esac
    ;;
  "shell id")
    case "$scenario" in
      root-adbd) printf 'uid=0(root) gid=0(root) groups=0(root)\n' ;;
      shell-id-multiline)
        printf 'uid=2000(shell) gid=2000(shell) groups=2000(shell)\n'
        printf 'uid=0(root) gid=0(root) groups=0(root)\n'
        ;;
      *) printf 'uid=2000(shell) gid=2000(shell) groups=2000(shell)\n' ;;
    esac
    ;;
  *)
    printf 'fake adb: unhandled read-only command: %s\n' "$*" >&2
    exit 98
    ;;
esac
