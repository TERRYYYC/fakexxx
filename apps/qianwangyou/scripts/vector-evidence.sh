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
# caller's `dev` seam so device-free selftests can fake it):
#
#   ve_capture_evidence <package> <prefs-file> <dest-dir>
#
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
#     (<file>, labeled historical). If it diverges from the live copy a
#     VE_DIVERGENCE line names both hashes; mirror absence is noted, never
#     fatal — the live zone is the only canonical source.
#
# This file contains ONLY read-only capture logic. It never launches an
# Activity (app startup can perform recovery writes and contaminate the state
# being collected) and never mutates the device.

VE_LIVE_GLOB_TEMPLATE='/data/misc/*/prefs/%s/%s'

ve_capture_evidence() {
    # $1 package, $2 prefs file name, $3 destination dir. Uses dev().
    if [ "$#" -ne 3 ]; then
        echo "VE_FAIL ve_capture_evidence expects <package> <file> <dest-dir>, got $#" >&2
        return 1
    fi
    ve_pkg="$1"; ve_file="$2"; ve_dest="$3"

    ve_glob=$(printf "$VE_LIVE_GLOB_TEMPLATE" "$ve_pkg" "$ve_file")
    if ! ve_paths=$(dev shell su -c "ls -d $ve_glob" 2>/dev/null | tr -d '\r'); then
        echo "VE_FAIL package=$ve_pkg file=$ve_file root (su) unavailable or transport failed — cannot read the live Vector zone, refusing to emit any canonical marker" >&2
        return 1
    fi
    ve_paths=$(printf '%s\n' "$ve_paths" | sed '/^$/d')
    ve_count=$(printf '%s\n' "$ve_paths" | grep -c .)
    if [ "$ve_count" -ne 1 ]; then
        echo "VE_FAIL package=$ve_pkg file=$ve_file expected exactly 1 live Vector source, found $ve_count — $(printf 'candidate-paths: %s ' $ve_paths)fail-closed; the app-private shared_prefs copy is a stale mirror, never canonical" >&2
        return 1
    fi
    ve_live_path=$(printf '%s\n' "$ve_paths" | head -1)

    mkdir -p "$ve_dest/vector-prefs" "$ve_dest/app-private-mirror" 2>/dev/null || {
        echo "VE_FAIL cannot create evidence dirs under $ve_dest" >&2
        return 1
    }
    if ! dev shell su -c "cat $ve_live_path" >"$ve_dest/vector-prefs/$ve_file.raw" 2>/dev/null \
        || [ ! -s "$ve_dest/vector-prefs/$ve_file.raw" ]; then
        rm -f "$ve_dest/vector-prefs/$ve_file.raw"
        echo "VE_FAIL package=$ve_pkg live read failed or empty at $ve_live_path" >&2
        return 1
    fi
    tr -d '\r' <"$ve_dest/vector-prefs/$ve_file.raw" >"$ve_dest/vector-prefs/$ve_file"
    rm -f "$ve_dest/vector-prefs/$ve_file.raw"
    ve_live_hash=$(shasum -a 256 "$ve_dest/vector-prefs/$ve_file" | awk '{print $1}')

    # Historical mirror (best-effort, labeled, never canonical, never fatal).
    ve_mirror_note="absent"
    if dev shell run-as "$ve_pkg" cat "shared_prefs/$ve_file" >"$ve_dest/app-private-mirror/$ve_file.raw" 2>/dev/null \
        && [ -s "$ve_dest/app-private-mirror/$ve_file.raw" ]; then
        tr -d '\r' <"$ve_dest/app-private-mirror/$ve_file.raw" >"$ve_dest/app-private-mirror/$ve_file"
        rm -f "$ve_dest/app-private-mirror/$ve_file.raw"
        ve_mirror_hash=$(shasum -a 256 "$ve_dest/app-private-mirror/$ve_file" | awk '{print $1}')
        if [ "$ve_live_hash" != "$ve_mirror_hash" ]; then
            ve_mirror_note="divergent"
            echo "VE_DIVERGENCE package=$ve_pkg file=$ve_file live(vector)=$ve_live_hash mirror(app-private)=$ve_mirror_hash — app-private copy is a stale pre-Vector mirror, historical only" >&2
        else
            ve_mirror_note="identical"
        fi
    else
        rm -f "$ve_dest/app-private-mirror/$ve_file.raw"
    fi

    printf 'package=%s\nfile=%s\nsourceZone=vector-live\nremotePath=%s\ncardinality=%s/1\nsha256=%s\nmirror=%s\n' \
        "$ve_pkg" "$ve_file" "$ve_live_path" "$ve_count" "$ve_live_hash" "$ve_mirror_note" \
        >"$ve_dest/vector-prefs/$ve_file.provenance"
    echo "VE_OK package=$ve_pkg file=$ve_file zone=vector-live path=$ve_live_path sha256=$ve_live_hash mirror=$ve_mirror_note" >&2
    return 0
}
