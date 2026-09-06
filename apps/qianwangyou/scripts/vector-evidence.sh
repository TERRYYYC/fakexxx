#!/bin/sh
# vector-evidence.sh — #90 Vector-aware, exact-package, read-only evidence capture.
#
# WHY THIS EXISTS (issue #90; lap-3 JUDGMENT F3'): when Vector redirects a
# package's live preferences directory, the app-private `shared_prefs/<name>`
# file keeps existing with well-formed, plausible, internally-consistent STALE
# content and no staleness marker. An offline judge consuming it as canonical
# produced a confident false P1 ("provider did not persist exhausted") in lap 3.
# The live zone is `/data/misc/*/prefs/<exact-package>/` and MUST be read via
# root; the app-private copy is at best a historical mirror.
#
# CONTRACT (sourced by evidence collectors; all device I/O goes through the
# caller's `dev` seam so device-free selftests can fake it). The
# `ve_live_root_shell` seam receives exactly one validated shell program. Its
# implementation MUST preserve that program as one remotely quoted `su -c`
# argument through `adb shell`; splitting it into `dev shell su -c "$1"` loses
# the argument boundary when adb joins/reparses the remote command:
#
#   ve_capture_evidence <package> <prefs-file> <fresh-dest-dir>
#
#   - The destination must not already exist. Capture happens in a sibling
#     staging directory and is published with a native atomic no-replace rename
#     only after every artifact, hash and provenance sidecar is complete.
#     Existing evidence is never overwritten, nested into or silently reused.
#   - Resolves the live Vector source with the EXACT package path segment:
#     `su -c 'ls -d /data/misc/*/prefs/<package>/<file>'`. Zero or multiple
#     matches, unreadable output, or missing root (su) => FAIL CLOSED: nothing
#     is emitted under <dest-dir>/vector-prefs and the function returns 1.
#     There is NO fallback to app-private state for a canonical marker — ever.
#   - On the exactly-one source: captures to <dest-dir>/vector-prefs/<file>
#     (CR stripped) and writes <file>.provenance recording package,
#     sourceZone=vector-live, the exact remote path, cardinality proof and
#     sha256.
#   - Best-effort app-private mirror capture to <dest-dir>/app-private-mirror/
#     (<file> plus its own provenance, labeled historical). If it diverges a
#     VE_DIVERGENCE line names both hashes. The optional mirror is explicitly
#     classified as absent, unavailable, read-failed, empty, identical or
#     divergent; attempted path and probe/read status are always recorded.
#     Mirror problems are never fatal — the live zone is the only canonical
#     source.
#
# This file contains ONLY read-only capture logic. It never launches an
# Activity (app startup can perform recovery writes and contaminate the state
# being collected) and never mutates the device.

VE_LIVE_GLOB_TEMPLATE='/data/misc/*/prefs/%s/%s'
VE_CAPTURE_LIVE_PATH=''
VE_CAPTURE_LIVE_HASH=''
VE_CAPTURE_MIRROR_PATH=''
VE_CAPTURE_MIRROR_HASH=''
VE_CAPTURE_MIRROR_STATE='absent'
VE_CAPTURE_MIRROR_PROBE_RC='not-attempted'
VE_CAPTURE_MIRROR_READ_RC='not-attempted'
VE_CAPTURE_PACKAGE=''
VE_CAPTURE_FILE=''

ve_validate_coordinate() {
    ve_coordinate="$1"
    ve_coordinate_kind="$2"
    case "$ve_coordinate_kind" in
        package)
            case "$ve_coordinate" in
                ""|.*|*.|*..*|*[!A-Za-z0-9._]*)
                    echo "VE_FAIL invalid package coordinate: $ve_coordinate" >&2
                    return 1 ;;
            esac
            ve_saved_ifs=$IFS
            IFS=.
            # shellcheck disable=SC2086 # intentional package-segment split
            set -- $ve_coordinate
            IFS=$ve_saved_ifs
            [ "$#" -ge 2 ] || {
                echo "VE_FAIL invalid package coordinate: $ve_coordinate" >&2
                return 1
            }
            for ve_segment do
                case "$ve_segment" in
                    [A-Za-z]*) ;;
                    *)
                        echo "VE_FAIL invalid package coordinate: $ve_coordinate" >&2
                        return 1 ;;
                esac
                case "$ve_segment" in
                    *[!A-Za-z0-9_]*)
                        echo "VE_FAIL invalid package coordinate: $ve_coordinate" >&2
                        return 1 ;;
                esac
            done
            ;;
        file|live-zone)
            case "$ve_coordinate" in
                ""|"."|".."|[!A-Za-z0-9_]*|*[!A-Za-z0-9_.-]*)
                    echo "VE_FAIL invalid $ve_coordinate_kind coordinate: $ve_coordinate" >&2
                    return 1 ;;
            esac
            ;;
        *)
            echo "VE_FAIL unknown coordinate kind: $ve_coordinate_kind" >&2
            return 1 ;;
    esac
}

ve_path_exists() {
    # POSIX -e follows symlinks; -L keeps a dangling historical destination
    # create-only too.
    [ -e "$1" ] || [ -L "$1" ]
}

ve_remove_staged_artifact() {
    # A local staging cleanup failure is an integrity failure, even when the
    # artifact came from the optional historical mirror. Publishing a `.raw`
    # or partially-normalized file would make the final directory ambiguous.
    if [ "$#" -ne 2 ]; then
        echo "VE_FAIL ve_remove_staged_artifact expects <path> <label>, got $#" >&2
        return 1
    fi
    if ! rm -f "$1" 2>/dev/null; then
        echo "VE_FAIL cannot remove staged $2: $1" >&2
        return 1
    fi
    if ve_path_exists "$1"; then
        echo "VE_FAIL staged $2 survived cleanup: $1" >&2
        return 1
    fi
}

ve_hash_file() {
    if [ "$#" -ne 1 ] || [ ! -s "$1" ]; then
        echo "VE_FAIL cannot hash missing/empty artifact: ${1:-<missing>}" >&2
        return 1
    fi
    ve_hash_record=$(shasum -a 256 "$1" 2>/dev/null)
    ve_hash_rc=$?
    if [ "$ve_hash_rc" -ne 0 ]; then
        echo "VE_FAIL sha256 command failed for $1" >&2
        return 1
    fi
    ve_hash_record_count=$(printf '%s\n' "$ve_hash_record" | awk 'NF { count++ } END { print count + 0 }') || {
        echo "VE_FAIL cannot parse sha256 output for $1" >&2
        return 1
    }
    if [ "$ve_hash_record_count" -ne 1 ]; then
        echo "VE_FAIL expected one sha256 record for $1, got $ve_hash_record_count" >&2
        return 1
    fi
    ve_hash=$(printf '%s\n' "$ve_hash_record" | awk 'NF { print $1; exit }') || {
        echo "VE_FAIL cannot extract sha256 for $1" >&2
        return 1
    }
    if ! printf '%s\n' "$ve_hash" | grep -Eq '^[0-9a-f]{64}$'; then
        echo "VE_FAIL invalid sha256 for $1: ${ve_hash:-<empty>}" >&2
        return 1
    fi
    printf '%s\n' "$ve_hash"
}

ve_publish_directory_create_only() {
    # Publish a complete sibling staging directory without ever replacing an
    # existing final pathname. POSIX `mv` cannot express this for directories:
    # a destination that wins the race is treated as a container and the stage
    # is silently nested inside it. Native exclusive rename is the authority;
    # unsupported hosts/filesystems fail closed and never fall back to `mv`.
    if [ "$#" -ne 2 ] || [ ! -d "$1" ] || [ -L "$1" ]; then
        echo "VE_FAIL create-only directory publish expects one real source directory and one destination" >&2
        return 1
    fi
    ve_publish_python="${VE_PUBLISH_PYTHON:-}"
    if [ -z "$ve_publish_python" ]; then
        ve_publish_python=$(command -v python3 2>/dev/null) || ve_publish_python=''
    fi
    if [ -z "$ve_publish_python" ]; then
        echo "VE_FAIL python3 is required for atomic create-only directory publication" >&2
        return 1
    fi
    "$ve_publish_python" - "$1" "$2" <<'PY'
import ctypes
import errno
import os
import stat
import sys

source, destination = sys.argv[1:]
try:
    source_stat = os.lstat(source)
except OSError as error:
    print(f"publish source unavailable: {error}", file=sys.stderr)
    raise SystemExit(1)
if not stat.S_ISDIR(source_stat.st_mode):
    print("publish source is not a real directory", file=sys.stderr)
    raise SystemExit(1)

libc = ctypes.CDLL(None, use_errno=True)
old = os.fsencode(source)
new = os.fsencode(destination)
ctypes.set_errno(0)
if sys.platform == "darwin":
    try:
        rename = libc.renamex_np
    except AttributeError:
        print("renamex_np is unavailable", file=sys.stderr)
        raise SystemExit(1)
    rename.argtypes = (ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint)
    rename.restype = ctypes.c_int
    result = rename(old, new, 0x00000004)  # RENAME_EXCL
elif sys.platform.startswith("linux"):
    try:
        rename = libc.renameat2
    except AttributeError:
        print("renameat2 is unavailable", file=sys.stderr)
        raise SystemExit(1)
    rename.argtypes = (
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_uint,
    )
    rename.restype = ctypes.c_int
    result = rename(-100, old, -100, new, 1)  # AT_FDCWD, RENAME_NOREPLACE
else:
    print(f"unsupported host for exclusive directory rename: {sys.platform}", file=sys.stderr)
    raise SystemExit(1)

if result != 0:
    error_number = ctypes.get_errno()
    if error_number == errno.EEXIST:
        print("publish destination already exists", file=sys.stderr)
    else:
        print(
            f"atomic create-only publish failed: [{error_number}] {os.strerror(error_number)}",
            file=sys.stderr,
        )
    raise SystemExit(1)
PY
}

ve_verify_staged_inventory() {
    # stage, prefs filename, resolved mirror state. This is the final
    # pre-publication allowlist: neither a stale `.raw`, a symlink nor an
    # unexpected producer artifact may cross the atomic rename boundary.
    if [ "$#" -ne 3 ] || [ ! -d "$1" ] || [ -L "$1" ]; then
        echo "VE_FAIL staged inventory expects one real stage directory, file and mirror state" >&2
        return 1
    fi
    ve_inventory_python="${VE_PUBLISH_PYTHON:-}"
    if [ -z "$ve_inventory_python" ]; then
        ve_inventory_python=$(command -v python3 2>/dev/null) || ve_inventory_python=''
    fi
    if [ -z "$ve_inventory_python" ]; then
        echo "VE_FAIL python3 is required for staged evidence inventory validation" >&2
        return 1
    fi
    "$ve_inventory_python" - "$1" "$2" "$3" <<'PY'
import os
import stat
import sys

stage, prefs_file, mirror_state = sys.argv[1:]
mirror_with_artifacts = {"identical", "divergent"}
mirror_without_artifacts = {"absent", "unavailable", "read-failed", "empty"}


def reject(message):
    print(f"staged evidence inventory rejected: {message}", file=sys.stderr)
    raise SystemExit(1)


def entries(path):
    try:
        return {entry.name: entry for entry in os.scandir(path)}
    except OSError as error:
        reject(f"cannot scan {path}: {error}")


root = entries(stage)
if set(root) != {"vector-prefs", "app-private-mirror"}:
    reject(f"unexpected stage entries: {sorted(root)!r}")
for directory_name, directory_entry in root.items():
    try:
        mode = directory_entry.stat(follow_symlinks=False).st_mode
    except OSError as error:
        reject(f"cannot inspect {directory_name}: {error}")
    if not stat.S_ISDIR(mode):
        reject(f"{directory_name} is not a real directory")

canonical_expected = {prefs_file, f"{prefs_file}.provenance"}
canonical = entries(os.path.join(stage, "vector-prefs"))
if set(canonical) != canonical_expected:
    reject(f"unexpected canonical entries: {sorted(canonical)!r}")

if mirror_state in mirror_with_artifacts:
    mirror_expected = {prefs_file, f"{prefs_file}.provenance"}
elif mirror_state in mirror_without_artifacts:
    mirror_expected = set()
else:
    reject(f"unpublishable mirror state: {mirror_state!r}")
mirror = entries(os.path.join(stage, "app-private-mirror"))
if set(mirror) != mirror_expected:
    reject(
        f"mirror state {mirror_state!r} has unexpected entries: {sorted(mirror)!r}"
    )

for zone_name, zone_entries in (("vector-prefs", canonical), ("app-private-mirror", mirror)):
    for artifact_name, artifact_entry in zone_entries.items():
        try:
            artifact_stat = artifact_entry.stat(follow_symlinks=False)
        except OSError as error:
            reject(f"cannot inspect {zone_name}/{artifact_name}: {error}")
        if not stat.S_ISREG(artifact_stat.st_mode):
            reject(f"{zone_name}/{artifact_name} is not a regular file")
        if artifact_stat.st_size <= 0:
            reject(f"{zone_name}/{artifact_name} is empty")
PY
}

ve_emit_capture_success() {
    echo "VE_OK package=$VE_CAPTURE_PACKAGE file=$VE_CAPTURE_FILE zone=vector-live path=$VE_CAPTURE_LIVE_PATH sha256=$VE_CAPTURE_LIVE_HASH mirror=$VE_CAPTURE_MIRROR_STATE" >&2
}

ve_write_provenance() {
    # path package file sourceZone remotePath cardinality sha256 canonical [derivedFromSha256]
    if [ "$#" -lt 8 ] || [ "$#" -gt 9 ]; then
        echo "VE_FAIL ve_write_provenance expects 8 or 9 arguments, got $#" >&2
        return 1
    fi
    ve_prov_path="$1"
    if ! printf 'package=%s\nfile=%s\nsourceZone=%s\nremotePath=%s\ncardinality=%s\nsha256=%s\ncanonical=%s\n' \
        "$2" "$3" "$4" "$5" "$6" "$7" "$8" >"$ve_prov_path"; then
        echo "VE_FAIL cannot write provenance: $ve_prov_path" >&2
        return 1
    fi
    if [ "$#" -eq 9 ] && ! printf 'derivedFromSha256=%s\n' "$9" >>"$ve_prov_path"; then
        echo "VE_FAIL cannot finish provenance: $ve_prov_path" >&2
        return 1
    fi
    if [ ! -s "$ve_prov_path" ]; then
        echo "VE_FAIL empty provenance: $ve_prov_path" >&2
        return 1
    fi
}

ve_resolve_single_live_path() {
    # $1 package, $2 prefs file name. Caller supplies ve_live_root_shell().
    if [ "$#" -ne 2 ]; then
        echo "VE_FAIL ve_resolve_single_live_path expects <package> <file>, got $#" >&2
        return 1
    fi
    if ! command -v ve_live_root_shell >/dev/null 2>&1; then
        echo "VE_FAIL no privileged Vector read seam was supplied" >&2
        return 1
    fi

    ve_pkg="$1"; ve_file="$2"
    ve_validate_coordinate "$ve_pkg" package || return 1
    ve_validate_coordinate "$ve_file" file || return 1
    ve_glob=$(printf "$VE_LIVE_GLOB_TEMPLATE" "$ve_pkg" "$ve_file")
    ve_glob_rc=$?
    if [ "$ve_glob_rc" -ne 0 ] || [ -z "$ve_glob" ]; then
        echo "VE_FAIL package=$ve_pkg file=$ve_file could not construct the live Vector coordinate" >&2
        return 1
    fi
    ve_paths_raw=$(ve_live_root_shell "ls -d $ve_glob" 2>/dev/null)
    ve_root_rc=$?
    if [ "$ve_root_rc" -ne 0 ]; then
        echo "VE_FAIL package=$ve_pkg file=$ve_file root (su) unavailable or transport failed — cannot resolve the live Vector zone" >&2
        return 1
    fi
    ve_paths=$(printf '%s\n' "$ve_paths_raw" | tr -d '\r') || {
        echo "VE_FAIL package=$ve_pkg file=$ve_file could not normalize live Vector resolver output" >&2
        return 1
    }
    ve_paths=$(printf '%s\n' "$ve_paths" | sed '/^$/d') || {
        echo "VE_FAIL package=$ve_pkg file=$ve_file could not filter live Vector resolver output" >&2
        return 1
    }
    ve_count=$(printf '%s\n' "$ve_paths" | awk 'NF { count++ } END { print count + 0 }') || {
        echo "VE_FAIL package=$ve_pkg file=$ve_file could not count live Vector resolver output" >&2
        return 1
    }
    if [ "$ve_count" -ne 1 ]; then
        echo "VE_FAIL package=$ve_pkg file=$ve_file expected exactly 1 live Vector source, found $ve_count — $(printf 'candidate-paths: %s ' $ve_paths)fail-closed; the app-private shared_prefs copy is a stale mirror, never canonical" >&2
        return 1
    fi
    ve_relative=${ve_paths#/data/misc/}
    if [ "$ve_relative" = "$ve_paths" ]; then
        echo "VE_FAIL package=$ve_pkg file=$ve_file resolver returned a path outside /data/misc: $ve_paths" >&2
        return 1
    fi
    ve_zone=${ve_relative%%/*}
    if ! ve_validate_coordinate "$ve_zone" live-zone \
        || [ "$ve_relative" != "$ve_zone/prefs/$ve_pkg/$ve_file" ]; then
        echo "VE_FAIL package=$ve_pkg file=$ve_file resolver returned a mismatched or unsafe path: $ve_paths" >&2
        return 1
    fi
    printf '%s\n' "$ve_paths"
}

ve_read_single_live_file() {
    # package prefs-file fresh-local-output. Sets VE_CAPTURE_LIVE_PATH.
    if [ "$#" -ne 3 ]; then
        echo "VE_FAIL ve_read_single_live_file expects <package> <file> <output>, got $#" >&2
        return 1
    fi
    ve_read_pkg="$1"; ve_read_file="$2"; ve_read_out="$3"
    if ve_path_exists "$ve_read_out" || ve_path_exists "$ve_read_out.raw"; then
        echo "VE_FAIL local read output already exists: $ve_read_out" >&2
        return 1
    fi
    if ! VE_CAPTURE_LIVE_PATH=$(ve_resolve_single_live_path "$ve_read_pkg" "$ve_read_file"); then
        return 1
    fi
    ve_live_root_shell "cat $VE_CAPTURE_LIVE_PATH" >"$ve_read_out.raw" 2>/dev/null
    ve_read_rc=$?
    if [ "$ve_read_rc" -ne 0 ]; then
        rm -f "$ve_read_out.raw" "$ve_read_out"
        echo "VE_FAIL package=$ve_read_pkg live read failed rc=$ve_read_rc at $VE_CAPTURE_LIVE_PATH" >&2
        return "$ve_read_rc"
    fi
    if [ ! -s "$ve_read_out.raw" ]; then
        rm -f "$ve_read_out.raw" "$ve_read_out"
        echo "VE_FAIL package=$ve_read_pkg live read was empty at $VE_CAPTURE_LIVE_PATH" >&2
        return 1
    fi
    if ! tr -d '\r' <"$ve_read_out.raw" >"$ve_read_out" || [ ! -s "$ve_read_out" ]; then
        rm -f "$ve_read_out.raw" "$ve_read_out"
        echo "VE_FAIL package=$ve_read_pkg could not normalize live bytes at $VE_CAPTURE_LIVE_PATH" >&2
        return 1
    fi
    if ! ve_remove_staged_artifact "$ve_read_out.raw" "canonical raw file"; then
        rm -f "$ve_read_out" 2>/dev/null || true
        return 1
    fi
}

ve_capture_evidence() {
    # $1 package, $2 prefs file name, $3 fresh destination dir. The optional
    # internal --defer-ok flag lets an enclosing bundle delay VE_OK until its
    # own create-only publication commits.
    if [ "$#" -lt 3 ] || [ "$#" -gt 4 ] \
        || { [ "$#" -eq 4 ] && [ "$4" != "--defer-ok" ]; }; then
        echo "VE_FAIL ve_capture_evidence expects <package> <file> <dest-dir> [--defer-ok], got $#" >&2
        return 1
    fi
    ve_pkg="$1"; ve_file="$2"; ve_dest="$3"
    ve_defer_ok=0
    if [ "$#" -eq 4 ]; then ve_defer_ok=1; fi
    VE_CAPTURE_PACKAGE="$ve_pkg"
    VE_CAPTURE_FILE="$ve_file"
    ve_validate_coordinate "$ve_pkg" package || return 1
    ve_validate_coordinate "$ve_file" file || return 1
    if ve_path_exists "$ve_dest"; then
        echo "VE_FAIL evidence destination already exists and is not fresh: $ve_dest" >&2
        return 1
    fi
    ve_parent=$(dirname -- "$ve_dest")
    ve_parent_rc=$?
    ve_base=$(basename -- "$ve_dest")
    ve_base_rc=$?
    if [ "$ve_parent_rc" -ne 0 ] || [ "$ve_base_rc" -ne 0 ] \
        || [ -z "$ve_parent" ] || [ -z "$ve_base" ]; then
        echo "VE_FAIL could not construct local evidence destination coordinates: $ve_dest" >&2
        return 1
    fi
    if ! mkdir -p "$ve_parent" 2>/dev/null; then
        echo "VE_FAIL cannot create evidence parent: $ve_parent" >&2
        return 1
    fi
    # A separate inner lock cannot strengthen the commit guarantee: staging
    # names are unique and the final native rename is already create-only.
    # Avoiding that redundant lock also avoids stale-lock denial and a state in
    # which evidence was published but lock cleanup failed afterward.
    if ve_path_exists "$ve_dest"; then
        echo "VE_FAIL evidence destination appeared during publish: $ve_dest" >&2
        return 1
    fi
    ve_stage=$(mktemp -d "$ve_parent/.${ve_base}.vector-evidence.XXXXXX" 2>/dev/null) || {
        echo "VE_FAIL cannot create evidence staging directory beside $ve_dest" >&2
        return 1
    }
    if ! mkdir -p "$ve_stage/vector-prefs" "$ve_stage/app-private-mirror" 2>/dev/null; then
        rm -rf "$ve_stage" 2>/dev/null || true
        echo "VE_FAIL cannot create staged evidence directories" >&2
        return 1
    fi
    if ! ve_read_single_live_file "$ve_pkg" "$ve_file" "$ve_stage/vector-prefs/$ve_file"; then
        rm -rf "$ve_stage" 2>/dev/null || true
        return 1
    fi
    if ! VE_CAPTURE_LIVE_HASH=$(ve_hash_file "$ve_stage/vector-prefs/$ve_file"); then
        rm -rf "$ve_stage" 2>/dev/null || true
        return 1
    fi

    # Historical mirror (best-effort, labeled, never canonical, never fatal).
    VE_CAPTURE_MIRROR_STATE="unavailable"
    VE_CAPTURE_MIRROR_PATH="run-as:$ve_pkg/shared_prefs/$ve_file"
    VE_CAPTURE_MIRROR_HASH=""
    VE_CAPTURE_MIRROR_PROBE_RC="not-attempted"
    VE_CAPTURE_MIRROR_READ_RC="not-attempted"
    # adb shell joins argv with spaces and sends the result to a remote shell;
    # argv quoting is not retained. Pass one complete command string containing
    # the quotes that the remote parser must see. Coordinates are already
    # grammar-validated above and cannot contain a single quote.
    ve_mirror_probe_command=$(printf "run-as %s sh -c '%s' sh '%s'" \
        "$ve_pkg" \
        'if [ -e "$1" ] || [ -L "$1" ]; then printf "__VE_MIRROR_EXISTS=1\n"; else printf "__VE_MIRROR_EXISTS=0\n"; fi' \
        "shared_prefs/$ve_file")
    ve_mirror_command_rc=$?
    if [ "$ve_mirror_command_rc" -ne 0 ]; then
        rm -rf "$ve_stage" 2>/dev/null || true
        echo "VE_FAIL cannot construct the remotely quoted mirror probe" >&2
        return 1
    fi
    ve_mirror_probe=$(dev shell "$ve_mirror_probe_command" 2>/dev/null)
    ve_mirror_probe_rc=$?
    VE_CAPTURE_MIRROR_PROBE_RC="$ve_mirror_probe_rc"
    if [ "$ve_mirror_probe_rc" -eq 0 ]; then
        ve_mirror_probe_normalized=$(printf '%s\n' "$ve_mirror_probe" | tr -d '\r')
        ve_mirror_normalize_rc=$?
        if [ "$ve_mirror_normalize_rc" -ne 0 ]; then
            VE_CAPTURE_MIRROR_STATE="unavailable"
            VE_CAPTURE_MIRROR_PROBE_RC="$ve_mirror_normalize_rc"
        elif [ "$ve_mirror_probe_normalized" = "__VE_MIRROR_EXISTS=0" ]; then
            VE_CAPTURE_MIRROR_STATE="absent"
        elif [ "$ve_mirror_probe_normalized" = "__VE_MIRROR_EXISTS=1" ]; then
            dev shell run-as "$ve_pkg" cat "shared_prefs/$ve_file" >"$ve_stage/app-private-mirror/$ve_file.raw" 2>/dev/null
            ve_mirror_read_rc=$?
            VE_CAPTURE_MIRROR_READ_RC="$ve_mirror_read_rc"
            if [ "$ve_mirror_read_rc" -ne 0 ]; then
                VE_CAPTURE_MIRROR_STATE="read-failed"
                if ! ve_remove_staged_artifact \
                    "$ve_stage/app-private-mirror/$ve_file.raw" "mirror raw file"; then
                    rm -rf "$ve_stage" 2>/dev/null || true
                    return 1
                fi
            elif [ ! -s "$ve_stage/app-private-mirror/$ve_file.raw" ]; then
                VE_CAPTURE_MIRROR_STATE="empty"
                if ! ve_remove_staged_artifact \
                    "$ve_stage/app-private-mirror/$ve_file.raw" "mirror raw file"; then
                    rm -rf "$ve_stage" 2>/dev/null || true
                    return 1
                fi
            else
                VE_CAPTURE_MIRROR_STATE="present"
            fi
        else
            # A transport success is not a protocol success. Noise, duplicate
            # markers and plausible prefixes remain non-canonical, but the
            # optional mirror may fail without invalidating the live capture.
            VE_CAPTURE_MIRROR_STATE="unavailable"
            VE_CAPTURE_MIRROR_PROBE_RC="protocol-invalid"
            echo "VE_MIRROR_UNAVAILABLE package=$ve_pkg file=$ve_file reason=invalid-probe-protocol" >&2
        fi
    fi
    if [ "$VE_CAPTURE_MIRROR_STATE" = "present" ]; then
        tr -d '\r' <"$ve_stage/app-private-mirror/$ve_file.raw" >"$ve_stage/app-private-mirror/$ve_file"
        ve_mirror_transform_rc=$?
        if [ "$ve_mirror_transform_rc" -ne 0 ] || [ ! -s "$ve_stage/app-private-mirror/$ve_file" ]; then
            VE_CAPTURE_MIRROR_STATE="read-failed"
            if [ "$ve_mirror_transform_rc" -ne 0 ]; then
                VE_CAPTURE_MIRROR_READ_RC="$ve_mirror_transform_rc"
            else
                VE_CAPTURE_MIRROR_READ_RC=1
            fi
            if ! ve_remove_staged_artifact \
                "$ve_stage/app-private-mirror/$ve_file.raw" "mirror raw file" \
                || ! ve_remove_staged_artifact \
                    "$ve_stage/app-private-mirror/$ve_file" "partial mirror file"; then
                rm -rf "$ve_stage" 2>/dev/null || true
                return 1
            fi
        else
            if ! ve_remove_staged_artifact \
                "$ve_stage/app-private-mirror/$ve_file.raw" "mirror raw file"; then
                rm -rf "$ve_stage" 2>/dev/null || true
                return 1
            fi
            if ! VE_CAPTURE_MIRROR_HASH=$(ve_hash_file "$ve_stage/app-private-mirror/$ve_file"); then
                rm -rf "$ve_stage" 2>/dev/null || true
                return 1
            fi
            if [ "$VE_CAPTURE_LIVE_HASH" != "$VE_CAPTURE_MIRROR_HASH" ]; then
                VE_CAPTURE_MIRROR_STATE="divergent"
                echo "VE_DIVERGENCE package=$ve_pkg file=$ve_file live(vector)=$VE_CAPTURE_LIVE_HASH mirror(app-private)=$VE_CAPTURE_MIRROR_HASH — app-private copy is a stale pre-Vector mirror, historical only" >&2
            else
                VE_CAPTURE_MIRROR_STATE="identical"
            fi
            if ! ve_write_provenance "$ve_stage/app-private-mirror/$ve_file.provenance" \
                "$ve_pkg" "$ve_file" app-private-mirror "$VE_CAPTURE_MIRROR_PATH" 1/1 \
                "$VE_CAPTURE_MIRROR_HASH" false; then
                rm -rf "$ve_stage" 2>/dev/null || true
                return 1
            fi
        fi
    fi

    if ! ve_write_provenance "$ve_stage/vector-prefs/$ve_file.provenance" \
        "$ve_pkg" "$ve_file" vector-live "$VE_CAPTURE_LIVE_PATH" 1/1 \
        "$VE_CAPTURE_LIVE_HASH" true \
        || ! printf 'mirror=%s\nmirrorAttemptedPath=%s\nmirrorProbeRc=%s\nmirrorReadRc=%s\n' \
            "$VE_CAPTURE_MIRROR_STATE" "$VE_CAPTURE_MIRROR_PATH" \
            "$VE_CAPTURE_MIRROR_PROBE_RC" "$VE_CAPTURE_MIRROR_READ_RC" \
            >>"$ve_stage/vector-prefs/$ve_file.provenance"; then
        rm -rf "$ve_stage" 2>/dev/null || true
        echo "VE_FAIL cannot finish canonical provenance" >&2
        return 1
    fi
    if ! ve_verify_staged_inventory "$ve_stage" "$ve_file" "$VE_CAPTURE_MIRROR_STATE"; then
        rm -rf "$ve_stage" 2>/dev/null || true
        echo "VE_FAIL staged evidence failed the exact pre-publication inventory" >&2
        return 1
    fi
    if ! ve_publish_directory_create_only "$ve_stage" "$ve_dest"; then
        rm -rf "$ve_stage" 2>/dev/null || true
        echo "VE_FAIL cannot atomically publish evidence to $ve_dest" >&2
        return 1
    fi
    if [ "$ve_defer_ok" -ne 1 ]; then
        ve_emit_capture_success
    fi
    return 0
}
