#!/usr/bin/env bash
#
# row2-runner.sh — the G2 Row 2 execution runner (supervisor + leaf helper
# modes).
#
# Contract: docs/acceptance/g2-p10-row2-evidence-contract.md (PR #55, blob
# c072c83fa979cf9d222a544faf8366e6fa691d21) §3.1-8 (launcher), §3.2
# (per-command carriers, single process-spawn call site), §4.1 (PRE-00
# schema validation, prefire write boundary), §3.1.1 HOST-RUNNER-MODE.
#
# TWO FACES, one file:
#
#   1. LEAF HELPER — the frozen HOST-RUNNER-MODE shape, exact 6 argv:
#        bash row2-runner.sh <mode> <mode-id> <input-rel> <output-rel>
#      mode ∈ {parse,audit,gate,seal}; mode-id ∈ RUNNER_MODE_IDS below.
#      Builtins only in this face (no external processes, no eval).
#
#   2. SUPERVISOR — the orchestration root the executor starts (contract
#      §3.2: "digest-bound runner supervisor 是 orchestration root，不是
#      packet command unit"). NOT a HOST-RUNNER-MODE leaf:
#        bash row2-runner.sh supervise <evidence-dir>
#      It walks the frozen packet's commands[] in seq order and runs every
#      external process through exactly ONE spawn call site — launcher_exec
#      below. Zero device knowledge: what it may launch is decided entirely
#      by the classifier verdict for each frozen envelope plus the phase
#      allowlist. This line (host-side) exercises it with HOST-NONE packets
#      only; device packets require ADM-01 operator authorization first.
#
# Launcher properties (contract §3.1-8):
#   - resolves executableId → locationId → canonical location from the map
#     below (never PATH, never basename);
#   - rejects symlinks / non-regular files / mode drift (stat via the
#     launcher's own verification spawn, see I8);
#   - verifies SHA-256 pre-exec and re-verifies identity+digest after wait;
#   - constructs the child env from scratch (ROW2-CLEAN-ENV-V1: LC_ALL=C,
#     LANG=C, TZ=UTC only; GIT commands add exactly GIT_CONFIG_NOSYSTEM=1
#     and GIT_CONFIG_GLOBAL=/dev/null — policy ROW2-CLEAN-ENV-GIT-V1) by
#     unsetting every inherited variable before exec (no env(1) wrapper);
#   - closed stdin: every child reads /dev/null (ROW2-STDIN-CLOSED-V1).
#
# Frozen interpretations (companion to the classifier's I1-I7):
#   I8  The launcher's own identity verification (stat/shasum/date spawns)
#       goes through the same single spawn call site but is launcher
#       machinery, not a packet command unit: §3.1-8 makes pre/post digest
#       verification PART of the launcher. Packet leaves stay one-per-unit;
#       a dedicated HOST-HASH command unit is still how payload digests are
#       evidenced (PRE-02).
#   I9  stdout carrier selection (.stdout.txt vs .stdout.bin) is frozen by
#       the packet at freeze time (the packet owns carrier paths, §3.1-1);
#       the launcher writes exactly the packet-frozen paths.
#   I10 write budget: normal-path DEVICE-WRITE envelopes ≤ 12 (Sol's gate-B
#       freeze: "B 正常路径最多 12 次 write-classified adb invocation"),
#       every write's checklist set inside the §4.1 allowlist, first write
#       is exactly SET-01 / ADB-WRITE-PREPARE, RST-01 stays conditional
#       (only after TERM-04 PASS, outside the 12).
#
# Exit codes: 0 ok; 1 STOP (gate violation / verification failure); 2 usage.

set -uo pipefail
LC_ALL=C; LANG=C; TZ=UTC; export LC_ALL LANG TZ

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SELF_DIR/../.." && pwd)"
. "$SELF_DIR/row2-envelope.sh"

CLASSIFIER_REL="$SELF_DIR/row2-classifier-v2.sh"

ENV_BASE="ROW2-CLEAN-ENV-V1"
ENV_GIT="ROW2-CLEAN-ENV-GIT-V1"
STDIN_CLOSED="ROW2-STDIN-CLOSED-V1"

# ---------------------------------------------------------------------------
# Frozen mode-id manifest (HOST-RUNNER-MODE). check-row2-exec.sh proves this
# is byte-equal to the classifier's RUNNER_MODE_IDS — adding a mode to one
# without the other fails the gate.
# ---------------------------------------------------------------------------
RUNNER_MODE_IDS=(
  "parse:envelope" "parse:packet"
  "audit:command-surface"
  "gate:six-file-carrier" "gate:prefire-write-boundary"
  "seal:file-set-equality"
)

# ---------------------------------------------------------------------------
# Frozen locationId → canonical location map (contract §3.1-8). Absolute
# host paths live HERE and only here (reviewed source), never in packets or
# carriers. /usr/bin on both ubuntu CI and macOS hosts real files for these;
# /bin/{bash,cat} are real files on both. A host where an entry is missing
# or a symlink makes the launcher STOP (fail-closed), not fall back to PATH.
# adb is deliberately never exercised on this host-side line (zero device
# commands); its entry exists so the manifest can bind it at real-freeze
# time and the map is complete for review.
# ---------------------------------------------------------------------------
LOCATION_SYS_BIN_BASH="/bin/bash"
LOCATION_SYS_BIN_CAT="/bin/cat"
LOCATION_SYS_BIN_SHASUM="/usr/bin/shasum"
LOCATION_SYS_BIN_SORT="/usr/bin/sort"
LOCATION_SYS_BIN_GREP="/usr/bin/grep"
LOCATION_SYS_BIN_FIND="/usr/bin/find"
LOCATION_SYS_BIN_LS="/bin/ls"
LOCATION_SYS_BIN_STAT="/usr/bin/stat"
LOCATION_SYS_BIN_WC="/usr/bin/wc"
LOCATION_SYS_BIN_CMP="/usr/bin/cmp"
LOCATION_SYS_BIN_DIFF="/usr/bin/diff"
LOCATION_SYS_BIN_CP="/bin/cp"
LOCATION_SYS_BIN_CHMOD="/bin/chmod"
LOCATION_SYS_BIN_MKDIR="/bin/mkdir"
LOCATION_SYS_BIN_TOUCH="/usr/bin/touch"
LOCATION_SYS_BIN_SQLITE3="/usr/bin/sqlite3"
LOCATION_SYS_BIN_DATE="/bin/date"
LOCATION_SYS_BIN_UNAME="/usr/bin/uname"
LOCATION_SYS_BIN_JAVA="/usr/bin/java"
LOCATION_SYS_BIN_SLEEP="/bin/sleep"
LOCATION_SYS_BIN_KILL="/bin/kill"
LOCATION_SYS_BIN_GIT="/usr/bin/git"
LOCATION_SYS_BIN_ADB="/usr/bin/adb"
LOCATION_SYS_BIN_FASTBOOT="/usr/bin/fastboot"
LOCATION_PAYLOAD_ROW2_RUNNER="$REPO_ROOT/scripts/row2/row2-runner.sh"
LOCATION_PAYLOAD_ROW2_CLASSIFIER="$REPO_ROOT/scripts/row2/row2-classifier-v2.sh"

location_of() { # <locationId> -> LOCATION_PATH (rc1 if unknown)
  local var="LOCATION_$1"
  [[ -n ${!var:-} ]] || return 1
  printf '%s' "${!var}"
}

# ===========================================================================
# LEAF HELPER FACE (builtins only)
# ===========================================================================

read_all() { # <path> -> REPLY_ALL (content, newlines preserved minus trailing blanks)
  local c=""
  IFS= read -r -d '' c < "$1" || true
  REPLY_ALL=$c
}

emit_error() { # <out-path> <code> <message...>
  local out=$1 code=$2; shift 2
  printf '{"error":"%s","code":"%s"}\n' "$(json_escape "$*")" "$code" > "$out"
}

# --- parse:envelope ---------------------------------------------------------
mode_parse_envelope() { # <carrier-rel> <out-rel>
  local carrier=$1 out=$2 line
  [[ -r "$carrier" ]] || { emit_error "$out" "io" "carrier not readable: $carrier"; return 1; }
  IFS= read -r line < "$carrier" || true
  if ! envelope_parse "$line"; then
    emit_error "$out" "parse" "carrier is not a parseable envelope: $carrier"
    return 1
  fi
  if ! envelope_parse_canonical "$line"; then
    emit_error "$out" "canonical" "carrier envelope is not canonical: $carrier"
    return 1
  fi
  local re line2
  line2=$(envelope_emit "$E_EXECID" "$E_SHA" "$E_CWD" "$E_ENVP" "$E_STDP" "${E_ARGV[@]}")
  local aj="" elem sep=$'\x01'
  for elem in "${E_ARGV[@]}"; do aj+="${elem}${sep}"; done
  aj=${aj%"$sep"}
  local argv_json="" first=1
  for elem in "${E_ARGV[@]}"; do
    if (( first )); then first=0; else argv_json+=","; fi
    argv_json+="\"$(json_escape "$elem")\""
  done
  printf '{"carrier":"%s","executableId":"%s","executableSha256":"%s","cwdRef":"%s","envPolicyId":"%s","stdinPolicyId":"%s","argvCount":%d,"argvSha256":"%s","envelopeSha256":"%s","argv":[%s]}\n' \
    "$(json_escape "$carrier")" "$(json_escape "$E_EXECID")" "$(json_escape "$E_SHA")" \
    "$(json_escape "$E_CWD")" "$(json_escape "$E_ENVP")" "$(json_escape "$E_STDP")" \
    "${#E_ARGV[@]}" "$(sha256_hex "$aj")" "$(sha256_hex "$line2")" "$argv_json" > "$out"
}

# --- parse:packet -----------------------------------------------------------
# Extracts every commands[] object with a quote-aware balanced-brace scan
# (canonical strings may contain braces inside quotes; a depth counter alone
# would corrupt on those).
mode_parse_packet() { # <packet-rel> <out-rel>
  local pkt=$1 out=$2
  [[ -r "$pkt" ]] || { emit_error "$out" "io" "packet not readable: $pkt"; return 1; }
  read_all "$pkt"
  local s=$REPLY_ALL
  local anchor='"commands":['
  [[ $s == *"$anchor"* ]] || { emit_error "$out" "parse" "no commands[] in packet"; return 1; }
  s=${s#*"$anchor"}
  local n=${#s} i=0 depth=0 in_str=0 esc=0 obj_start=-1 obj lines=() count=0
  local -a objs=()
  while (( i < n )); do
    local c=${s:i:1}
    if (( esc )); then esc=0; ((i+=1)); continue; fi
    if [[ $c == '\' ]]; then esc=1; ((i+=1)); continue; fi
    if (( in_str )); then
      [[ $c == '"' ]] && in_str=0
      ((i+=1)); continue
    fi
    case "$c" in
      '"') in_str=1 ;;
      '{') if (( depth == 0 )); then obj_start=$i; fi; ((depth+=1)) ;;
      '}') ((depth-=1))
           if (( depth == 0 )); then
             obj=${s:obj_start:i-obj_start+1}
             objs+=("$obj")
             count=$((count+1))
           fi ;;
      ']') if (( depth == 0 )); then break; fi ;;
    esac
    ((i+=1))
  done
  if (( count == 0 )); then
    emit_error "$out" "parse" "commands[] empty or unterminated"
    return 1
  fi
  {
    for obj in "${objs[@]}"; do
      # canonical command object: seq/checklistIds/phase/slug/executableId/
      # executableSha256/cwdRef/envPolicyId/stdinPolicyId/argv/carrier
      local seq phase slug
      seq=${obj#*'"seq":"'};   seq=${seq%%\"*}
      phase=${obj#*'"phase":"'}; phase=${phase%%\"*}
      slug=${obj#*'"slug":"'};  slug=${slug%%\"*}
      local cc cs co cx ce cst
      cc=${obj#*'"command":"'}; cc=${cc%%\"*}
      cs=${obj#*'"stdout":"'};  cs=${cs%%\"*}
      co=${obj#*'"stderr":"'};  co=${co%%\"*}
      cx=${obj#*'"exit":"'};    cx=${cx%%\"*}
      cst=${obj#*'"startUtc":"'}; cst=${cst%%\"*}
      ce=${obj#*'"endUtc":"'};  ce=${ce%%\"*}
      # envelope substring: starts at "executableId" (the six envelope keys
      # are contiguous in the canonical command object) and ends with the
      # argv array — everything from ,"carrier":{ onward is stripped, then
      # the object braces are restored (row2-packet.sh freezes this order).
      local envelope
      envelope=${obj#*'"executableId"'}
      envelope=${envelope%%,'"carrier":{'*}
      envelope="{\"executableId\"$envelope}"
      printf '{"seq":"%s","phase":"%s","slug":"%s","envelope":%s,"commandCarrier":"%s","stdoutCarrier":"%s","stderrCarrier":"%s","exitCarrier":"%s","startCarrier":"%s","endCarrier":"%s"}\n' \
        "$seq" "$phase" "$slug" "$envelope" "$cc" "$cs" "$co" "$cx" "$cst" "$ce"
    done
  } > "$out"
}

# --- audit:command-surface --------------------------------------------------
# Input: concatenated classifier `classify` output lines (one per executed
# envelope). Cross-checks against the packet under meta/execution-packet.json:
#   - every packet command's carrier must have exactly one classification line
#   - each line's envelopeSha256 must equal the packet envelope's digest
#     (catches "packet and carrier do not match yet execution continues")
#   - reject / ambiguous / duplicate counts
mode_audit_command_surface() { # <input-rel> <out-rel>
  local inp=$1 out=$2
  [[ -r "$inp" && -r "meta/execution-packet.json" ]] || {
    emit_error "$out" "io" "input or meta/execution-packet.json missing"
    return 1
  }
  local rejects=0 mismatch=0 ambiguous=0 writes=0 reads=0 host=0 dupseq=0 lines=0 carrier_mismatch=0
  local -a seen=()   # bash 3.2: no associative arrays; linear scan is fine here
  local -a cls_carriers=() cls_shas=()
  local line seen_x
  while IFS= read -r line; do
    [[ -z $line ]] && continue
    ((lines+=1))
    local rid cls esha seq carrier
    rid=${line#*'"ruleId":"'};  rid=${rid%%\"*}
    cls=${line#*'"derivedClass":"'}; cls=${cls%%\"*}
    esha=${line#*'"envelopeSha256":"'}; esha=${esha%%\"*}
    carrier=${line#*'"commandCarrier":"'}; carrier=${carrier%%\"*}
    case "$cls" in
      CLASSIFIER-REJECT)
        case "$rid" in "") rejects=$((rejects+1)) ;; *) ambiguous=$((ambiguous+1)) ;; esac ;;
      DEVICE-WRITE) writes=$((writes+1)) ;;
      DEVICE-READ)  reads=$((reads+1)) ;;
      HOST-NONE)    host=$((host+1)) ;;
    esac
    local dup=0
    if [[ -n ${seen[@]+x} ]]; then
      for seen_x in "${seen[@]}"; do
        [[ $seen_x == "$esha" ]] && { dup=1; break; }
      done
    fi
    if (( dup )); then dupseq=$((dupseq+1)); else seen+=("$esha"); fi
    cls_carriers+=("$carrier")
    cls_shas+=("$esha")
  done < "$inp"
  # packet side: per-command carrier + recomputed envelope digest
  local pkt_cmds="" pline
  local tmp_out="meta/.audit-parse.json"
  mode_parse_packet "meta/execution-packet.json" "$tmp_out" || {
    emit_error "$out" "parse" "packet unparseable"
    return 1
  }
  read_all "$tmp_out"; pkt_cmds=$REPLY_ALL
  local pkt_count=0
  while IFS= read -r pline; do
    [[ -z $pline ]] && continue
    ((pkt_count+=1))
    local pcarrier envjson psha
    pcarrier=${pline#*'"commandCarrier":"'}; pcarrier=${pcarrier%%\"*}
    envjson=${pline#*'"envelope":'}; envjson=${envjson%%,'"commandCarrier"'*}
    psha=$(sha256_hex "$envjson")
    # find the matching classification line
    local found=0 k
    for k in "${cls_carriers[@]}"; do
      [[ $k == "$pcarrier" ]] && { found=1; break; }
    done
    if (( ! found )); then
      carrier_mismatch=$((carrier_mismatch+1))
      continue
    fi
    local csha="" idx=0 j
    for j in "${cls_carriers[@]}"; do
      if [[ $j == "$pcarrier" ]]; then csha=${cls_shas[idx]}; break; fi
      ((idx+=1))
    done
    [[ $csha == "$psha" ]] || carrier_mismatch=$((carrier_mismatch+1))
    # AND the frozen carrier bytes on disk must still equal the packet
    # envelope (post-run tampering of command.txt is a mismatch even when
    # the recorded classification was honest at execution time)
    if [[ -r "$pcarrier" ]]; then
      local cline=""
      IFS= read -r cline < "$pcarrier" || true
      local csha2
      csha2=$(sha256_hex "$cline")
      [[ $csha2 == "$psha" ]] || carrier_mismatch=$((carrier_mismatch+1))
    else
      carrier_mismatch=$((carrier_mismatch+1))
    fi
  done <<< "$pkt_cmds"
  printf '{"inputLines":%d,"packetCommands":%d,"hostNone":%d,"deviceRead":%d,"deviceWrite":%d,"rejects":%d,"ambiguous":%d,"duplicateEnvelope":%d,"packetCarrierMismatch":%d}\n' \
    "$lines" "$pkt_count" "$host" "$reads" "$writes" "$rejects" "$ambiguous" "$dupseq" "$carrier_mismatch" > "$out"
  if (( rejects + ambiguous + dupseq + carrier_mismatch > 0 )); then return 1; fi
  return 0
}

# --- gate:six-file-carrier --------------------------------------------------
# Input: one carrier STEM per line (a stem is <phase>/<seq>-<slug>). For each
# stem the six carrier files must exist; stdout exactly one of .txt/.bin;
# exit.txt one decimal + newline; start/end RFC3339 UTC. This is the gate
# that makes "missing quadruple reported as success" impossible.
mode_gate_six_file_carrier() { # <input-rel> <out-rel>
  local inp=$1 out=$2
  [[ -r "$inp" ]] || { emit_error "$out" "io" "stem list not readable: $inp"; return 1; }
  local total=0 bad=0 stem report=0
  : > "$out"
  while IFS= read -r stem; do
    [[ -z $stem ]] && continue
    ((total+=1))
    local why=""
    [[ -s "$stem.command.txt" ]] || why="command-missing"
    [[ -f "$stem.stderr.bin" ]] || why="${why:+$why,}stderr-missing"   # 0-byte stderr is legal (§3.2-8)
    [[ -s "$stem.exit.txt" ]] || why="${why:+$why,}exit-missing"
    [[ -s "$stem.start-utc.txt" ]] || why="${why:+$why,}start-missing"
    [[ -s "$stem.end-utc.txt" ]] || why="${why:+$why,}end-missing"
    local st=0
    [[ -f "$stem.stdout.txt" ]] && st=$((st+1))
    [[ -f "$stem.stdout.bin" ]] && st=$((st+1))
    if (( st != 1 )); then why="${why:+$why,}stdout-not-unique($st)"; fi
    if [[ -z $why ]]; then
      local ex
      IFS= read -r ex < "$stem.exit.txt" || true
      [[ $ex =~ ^[0-9]+$ ]] || why="exit-not-decimal"
      local ts
      IFS= read -r ts < "$stem.start-utc.txt" || true
      [[ $ts =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || why="${why:+$why,}start-not-rfc3339"
      IFS= read -r ts < "$stem.end-utc.txt" || true
      [[ $ts =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || why="${why:+$why,}end-not-rfc3339"
    fi
    if [[ -n $why ]]; then
      ((bad+=1))
      printf '{"stem":"%s","verdict":"FAIL","why":"%s"}\n' "$stem" "$why" >> "$out"
    fi
  done < "$inp"
  printf '{"stems":%d,"failures":%d,"verdict":"%s"}\n' "$total" "$bad" "$(( bad == 0 ))" >> "$out"
  (( bad == 0 ))
}

# --- gate:prefire-write-boundary --------------------------------------------
# Input: concatenated classifier output lines for a packet (in seq order),
# optionally containing a literal line TERM-04-PASS. Enforces I10.
WRITE_ALLOWLIST="SET-01 SET-02 BFR-01 BFR-02 WIN-01 TERM-01 AFT-01 AFT-02 INJ-02 RST-01"
mode_gate_prefire_write_boundary() { # <input-rel> <out-rel>
  local inp=$1 out=$2
  [[ -r "$inp" ]] || { emit_error "$out" "io" "classification input missing: $inp"; return 1; }
  local normal_writes=0 first_write_rule="" rst01=0 term04=0 violation="" line
  while IFS= read -r line; do
    [[ $line == "TERM-04-PASS" ]] && { term04=1; continue; }
    [[ -z $line ]] && continue
    local cls rid
    cls=${line#*'"derivedClass":"'}; cls=${cls%%\"*}
    [[ $cls == "DEVICE-WRITE" ]] || continue
    rid=${line#*'"ruleId":"'}; rid=${rid%%\"*}
    [[ -z $first_write_rule ]] && first_write_rule=$rid
    if [[ $rid == "ADB-WRITE-RERELEASE" ]]; then
      rst01=$((rst01+1))
    else
      normal_writes=$((normal_writes+1))
    fi
  done < "$inp"
  if (( rst01 > 0 )) && (( term04 == 0 )); then
    violation="rst01-without-term04"
  elif (( normal_writes > 12 )); then
    violation="normal-path-writes-exceed-12($normal_writes)"
  elif [[ -n $first_write_rule ]] && [[ $first_write_rule != "ADB-WRITE-PREPARE" ]]; then
    violation="first-write-not-prepare($first_write_rule)"
  fi
  printf '{"normalPathWrites":%d,"rst01Writes":%d,"term04":%d,"firstWriteRule":"%s","verdict":"%s","violation":"%s"}\n' \
    "$normal_writes" "$rst01" "$term04" "$first_write_rule" \
    "$([[ -z $violation ]] && echo PASS || echo STOP)" "$violation" > "$out"
  [[ -z $violation ]]
}

# --- seal:file-set-equality -------------------------------------------------
# Input: path lists in two sections — a line "LIST1" starts list 1, "LIST2"
# starts list 2; every other nonempty line is a path. Verdict PASS iff the
# two sets are byte-equal after sorting.
mode_seal_file_set_equality() { # <input-rel> <out-rel>
  local inp=$1 out=$2
  [[ -r "$inp" ]] || { emit_error "$out" "io" "input missing: $inp"; return 1; }
  local cur=0 line l1="" l2="" c1=0 c2=0
  while IFS= read -r line; do
    case "$line" in
      LIST1) cur=1; continue ;;
      LIST2) cur=2; continue ;;
    esac
    [[ -z $line ]] && continue
    if (( cur == 1 )); then l1+="$line"$'\n'; ((c1+=1))
    elif (( cur == 2 )); then l2+="$line"$'\n'; ((c2+=1))
    fi
  done < "$inp"
  local s1 s2
  s1=$(printf '%s' "$l1" | sort_map_builtin)
  s2=$(printf '%s' "$l2" | sort_map_builtin)
  # builtins-only sort: see sort_map_builtin — but leaf face may not spawn;
  # implement a simple insertion via `printf | sort` is NOT allowed, so use
  # the pure-builtin path below.
  local verdict=PASS
  [[ "$s1" == "$s2" ]] || verdict=FAIL
  printf '{"list1":%d,"list2":%d,"verdict":"%s"}\n' "$c1" "$c2" "$verdict" > "$out"
  [[ $verdict == PASS ]]
}

# builtins-only sorted rendering of newline-separated input on stdin
sort_map_builtin() {
  local -a arr=() x
  while IFS= read -r x; do
    [[ -n $x ]] || continue
    arr+=("$x")
  done
  # insertion sort (sets are small and this face must not spawn processes)
  local i j tmp n=${#arr[@]}
  for ((i = 1; i < n; i++)); do
    tmp=${arr[i]}
    for ((j = i-1; j >= 0; j--)); do
      [[ ${arr[j]} > $tmp ]] || break
      arr[j+1]=${arr[j]}
    done
    arr[j+1]=$tmp
  done
  printf '%s\n' "${arr[@]}"
}

# ===========================================================================
# SUPERVISOR FACE
# ===========================================================================

MANIFEST=""    # canonical manifest text
EVID="."       # evidence root (CWD)

mft_field() { # <manifestText> <execId> <field> -> value
  local mft=$1 id=$2 field=$3 anchor rest
  anchor="\"executableId\":\"$id\""
  [[ $mft == *"$anchor"* ]] || return 1
  rest=${mft#*"$anchor"}
  local f="\"$field\":\""
  [[ $rest == *"$f"* ]] || return 1
  rest=${rest#*"$f"}
  printf '%s' "${rest%%\"*}"
}

# THE single process-spawn call site (contract §3.2-2). Every external
# process the supervisor starts — classifier, stat/shasum/date verification,
# and the classified packet leaf itself — goes through here.
launcher_exec() { # <locationId> <argv...> ; stdout->LAUNCH_STDOUT
  local locid=$1; shift
  local path
  path=$(location_of "$locid") || { printf 'launcher: unknown locationId %s\n' "$locid" >&2; return 3; }
  [[ -e "$path" ]] || { printf 'launcher: canonical path missing: %s\n' "$locid" >&2; return 3; }
  [[ -L "$path" ]] && { printf 'launcher: refusing symlink: %s\n' "$locid" >&2; return 3; }
  [[ -f "$path" ]] || { printf 'launcher: not a regular file: %s\n' "$locid" >&2; return 3; }
  "$path" "$@"
}

# verify_executable <execId> — stat + digest pre-check against the manifest
verify_executable() { # <execId> -> 0 ok; 3 verification STOP
  local id=$1 locid sha path
  locid=$(mft_field "$MANIFEST" "$id" "locationId") || { printf 'verify: %s not in manifest\n' "$id" >&2; return 3; }
  sha=$(mft_field "$MANIFEST" "$id" "sha256") || return 3
  path=$(location_of "$locid") || return 3
  [[ -L "$path" || ! -f "$path" ]] && { printf 'verify: %s bad file type\n' "$id" >&2; return 3; }
  local mode_ok
  mode_ok=$(launcher_exec SYS_BIN_STAT -f '%Sp' "$path" 2>/dev/null) || mode_ok=$(launcher_exec SYS_BIN_STAT -c '%A' "$path" 2>/dev/null) || {
    printf 'verify: stat failed for %s\n' "$id" >&2; return 3; }
  local frozen_mode
  frozen_mode=$(mft_field "$MANIFEST" "$id" "modePattern")
  if [[ -n ${frozen_mode//\"/} && -n $frozen_mode ]] && [[ $frozen_mode != "-" && "$mode_ok" != "$frozen_mode" ]]; then
    printf 'verify: %s mode drift (%s != %s)\n' "$id" "$mode_ok" "$frozen_mode" >&2
    return 3
  fi
  local actual
  actual=$(launcher_exec SYS_BIN_SHASUM -a 256 -- "$path") || { printf 'verify: shasum failed for %s\n' "$id" >&2; return 3; }
  actual=${actual%% *}
  [[ $actual == "$sha" ]] || { printf 'verify: %s digest mismatch (%s != %s)\n' "$id" "$actual" "$sha" >&2; return 3; }
  return 0
}

# run_command_unit — one packet command: classify, verify, exec, carriers.
# Globals from packet parsing: PU_SEQ PU_PHASE PU_SLUG PU_EXECID PU_SHA
# PU_CWD PU_ENVP PU_STDP PU_ARGV[] PU_CARRIER_{command,stdout,stderr,exit,start,end}
run_command_unit() {
  local stem="${PU_PHASE}/${PU_SEQ}-${PU_SLUG}"
  # 1. write the canonical command carrier FIRST (contract §3.2-1: the
  #    launcher writes the carrier, then classifies the same envelope)
  local envelope
  envelope=$(envelope_emit "$PU_EXECID" "$PU_SHA" "$PU_CWD" "$PU_ENVP" "$PU_STDP" ${PU_ARGV[@]+"${PU_ARGV[@]}"})
  printf '%s\n' "$envelope" > "$PU_CARRIER_COMMAND"
  # 2. classify the carrier with the frozen classifier (spawn #1 via launcher)
  local cls_out="meta/.cls-$PU_SEQ.json"
  if ! launcher_exec SYS_BIN_BASH "$LOCATION_PAYLOAD_ROW2_CLASSIFIER" classify "$PU_CARRIER_COMMAND" "$cls_out"; then
    printf 'STOP: classifier invocation failed for %s\n' "$stem" >&2
    return 1
  fi
  local cls rid cls2 rkind
  read_all "$cls_out"; cls=$REPLY_ALL
  rid=${cls#*'"ruleId":"'};  rid=${rid%%\"*}
  cls2=${cls#*'"derivedClass":"'}; cls2=${cls2%%\"*}
  rkind=${cls#*'"rejectKind":"'}; rkind=${rkind%%\"*}
  if [[ $cls2 == "CLASSIFIER-REJECT" ]]; then
    printf 'STOP: %s classified CLASSIFIER-REJECT (%s)\n' "$stem" "$rkind" >&2
    return 1
  fi
  # 3. phase allowlist (host-side line: only HOST-NONE may run — zero device)
  if [[ $cls2 != "HOST-NONE" ]]; then
    printf 'STOP: %s derived %s — this build of the supervisor is host-side only (zero device commands)\n' "$stem" "$cls2" >&2
    return 1
  fi
  # 4. executable verification (stat+digest via launcher machinery spawns)
  verify_executable "$PU_EXECID" || return 1
  # 5. exec the leaf with clean env + closed stdin, carriers attached
  local locid path
  locid=$(mft_field "$MANIFEST" "$PU_EXECID" "locationId")
  path=$(location_of "$locid")
  local t0 t1 rc
  t0=$(launcher_exec SYS_BIN_DATE -u +%Y-%m-%dT%H:%M:%SZ)
  # the envelope's logical argv[0] is the manifest's argv0Token (identity
  # only); the canonical path becomes the child's argv[0] — passing both
  # would hand the leaf its own name as an operand (the exit=2 sort lesson)
  clean_env_exec "$path" "$PU_CARRIER_STDOUT" "$PU_CARRIER_STDERR" ${PU_ARGV[@]+"${PU_ARGV[@]:1}"}
  rc=$?
  t1=$(launcher_exec SYS_BIN_DATE -u +%Y-%m-%dT%H:%M:%SZ)
  printf '%s\n' "$t0" > "$PU_CARRIER_START"
  printf '%s\n' "$t1" > "$PU_CARRIER_END"
  printf '%s\n' "$rc" > "$PU_CARRIER_EXIT"
  # 6. post-wait re-verification (the exec redirections created stdout/stderr
  #    carriers even when the leaf produced 0 bytes — contract §3.2-3/8)
  verify_executable "$PU_EXECID" || return 1
  [[ -f "$PU_CARRIER_STDOUT" && -f "$PU_CARRIER_STDERR" ]] || {
    printf 'STOP: %s carriers not created\n' "$stem" >&2; return 1; }
  printf 'unit %s rule=%s class=%s exit=%s\n' "$stem" "$rid" "$cls2" "$rc" >&2
  return 0
}

# clean_env_exec — runs the leaf in a SUBSHELL whose environment is rebuilt
# from scratch (the supervisor's own env survives); no env(1) wrapper.
clean_env_exec() {
  local path=$1 outf=$2 errf=$3; shift 3
  local git_env=0
  [[ $PU_ENVP == "$ENV_GIT" ]] && git_env=1
  (
    local v
    while IFS= read -r v; do
      case "$v" in
        LC_ALL|LANG|TZ|GIT_CONFIG_NOSYSTEM|GIT_CONFIG_GLOBAL) ;;
        *) unset "$v" 2>/dev/null || true ;;
      esac
    done < <(compgen -e)
    export LC_ALL=C LANG=C TZ=UTC
    (( git_env )) && export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null
    "$path" "$@" > "$outf" 2> "$errf" < /dev/null
  )
}

supervise() { # <evidence-dir>
  local ev=$1
  cd "$ev" || return 2
  [[ -r "meta/execution-packet.json" && -r "meta/executable-manifest.json" ]] || {
    printf 'supervise: meta/execution-packet.json + meta/executable-manifest.json required under evidence dir\n' >&2
    return 2
  }
  read_all "meta/executable-manifest.json"; MANIFEST=$REPLY_ALL
  # parse packet commands via the leaf face through the single spawn site
  # (single parser, no duplicated packet logic in the supervisor)
  launcher_exec SYS_BIN_BASH "$LOCATION_PAYLOAD_ROW2_RUNNER" parse packet \
    meta/execution-packet.json meta/.packet-commands.json || return 2
  read_all "meta/.packet-commands.json"
  local cmds=$REPLY_ALL line envjson
  while IFS= read -r line; do
    [[ -z $line ]] && continue
    PU_SEQ=${line#*'"seq":"'}; PU_SEQ=${PU_SEQ%%\"*}
    PU_PHASE=${line#*'"phase":"'}; PU_PHASE=${PU_PHASE%%\"*}
    PU_SLUG=${line#*'"slug":"'}; PU_SLUG=${PU_SLUG%%\"*}
    PU_CARRIER_COMMAND=${line#*'"commandCarrier":"'}; PU_CARRIER_COMMAND=${PU_CARRIER_COMMAND%%\"*}
    PU_CARRIER_STDOUT=${line#*'"stdoutCarrier":"'}; PU_CARRIER_STDOUT=${PU_CARRIER_STDOUT%%\"*}
    PU_CARRIER_STDERR=${line#*'"stderrCarrier":"'}; PU_CARRIER_STDERR=${PU_CARRIER_STDERR%%\"*}
    PU_CARRIER_EXIT=${line#*'"exitCarrier":"'}; PU_CARRIER_EXIT=${PU_CARRIER_EXIT%%\"*}
    PU_CARRIER_START=${line#*'"startCarrier":"'}; PU_CARRIER_START=${PU_CARRIER_START%%\"*}
    PU_CARRIER_END=${line#*'"endCarrier":"'}; PU_CARRIER_END=${PU_CARRIER_END%%\"*}
    envjson=${line#*'"envelope":'}; envjson=${envjson%%,'"commandCarrier"'*}
    if ! envelope_parse "$envjson"; then
      printf 'supervise: envelope for seq %s not parseable\n' "$PU_SEQ" >&2
      return 1
    fi
    PU_EXECID=$E_EXECID; PU_SHA=$E_SHA; PU_CWD=$E_CWD; PU_ENVP=$E_ENVP; PU_STDP=$E_STDP
    PU_ARGV=("${E_ARGV[@]}")
    run_command_unit || return 1
  done <<< "$cmds"
  printf 'supervise: all units completed\n' >&2
  return 0
}

# ---------------------------------------------------------------------------
# dispatch
# ---------------------------------------------------------------------------
main() {
  if (( $# == 0 )); then usage; exit 2; fi
  case "$1" in
    supervise)
      (( $# == 2 )) || { usage; exit 2; }
      supervise "$2"
      ;;
    parse|audit|gate|seal)
      (( $# == 4 )) || { usage; exit 2; }
      local mode=$1 mode_id=$2 inp=$3 out=$4
      local combined="$1:$2" id ok=0
      for id in "${RUNNER_MODE_IDS[@]}"; do
        [[ $combined == "$id" ]] && ok=1
      done
      (( ok )) || { printf 'unknown mode-id: %s\n' "$combined" >&2; exit 2; }
      valid_rel_path "$inp" || { printf 'bad input path\n' >&2; exit 2; }
      valid_rel_path "$out" || { printf 'bad output path\n' >&2; exit 2; }
      case "$combined" in
        parse:envelope)          mode_parse_envelope "$inp" "$out" ;;
        parse:packet)            mode_parse_packet "$inp" "$out" ;;
        audit:command-surface)   mode_audit_command_surface "$inp" "$out" ;;
        gate:six-file-carrier)   mode_gate_six_file_carrier "$inp" "$out" ;;
        gate:prefire-write-boundary) mode_gate_prefire_write_boundary "$inp" "$out" ;;
        seal:file-set-equality)  mode_seal_file_set_equality "$inp" "$out" ;;
        *) exit 2 ;;
      esac
      ;;
    *)
      usage; exit 2
      ;;
  esac
}

usage() {
  cat >&2 <<'EOF'
usage:
  bash row2-runner.sh <mode> <mode-id> <input-rel> <output-rel>   (HOST-RUNNER-MODE leaf)
      modes: parse:{envelope,packet} audit:command-surface
             gate:{six-file-carrier,prefire-write-boundary}
             seal:file-set-equality
  bash row2-runner.sh supervise <evidence-dir>                   (orchestration root)
EOF
}

main "$@"
