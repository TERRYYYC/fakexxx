#!/usr/bin/env bash
#
# row2-classifier-v2.sh — the ROW2-EXEC-ACCESS-V2 canonical
# execution-envelope → device-access classifier.
#
# Contract: docs/acceptance/g2-p10-row2-evidence-contract.md (PR #55, blob
# c072c83fa979cf9d222a544faf8366e6fa691d21) §3.1.1, §3.2-1, §4.1.
#
# THIS FILE IS THE FROZEN CLASSIFIER PAYLOAD. It is not a general tool:
#
#   - CLI surface is exactly the contract's HOST-CLASSIFIER row:
#       bash row2-classifier-v2.sh classify <command-carrier-rel> <output-rel>
#       bash row2-classifier-v2.sh fixture  <fixture-id>       <output-rel>
#     exact arity, no other subcommands. Any other invocation exits 2.
#   - Builtins only (contract: "只用 shell builtins，不得调用
#     launcher／外部进程／eval"). No eval, no external processes, no pipes to
#     programs; file reads use the read builtin. Subshell $(...) forks are
#     limited to bash builtins (e.g. $(sha256_hex ...)).
#   - Inputs are exactly: packet constants, the frozen executable manifest,
#     and one canonical JSON execution envelope (contract §3.1.1). It NEVER
#     reads a packet access label — the packet schema has none (PRE-00).
#
# Classification is mechanical: EVERY rule in RULE_IDS is evaluated; exactly
# one match ⇒ that rule's class; zero or ≥2 matches ⇒ CLASSIFIER-REJECT
# (contract: "任何 envelope 命中 0 条或 2 条以上均输出 CLASSIFIER-REJECT").
# Envelope pre-gates (field completeness, manifest/digest binding, env/stdin
# policy, cwdRef) reject BEFORE argv rules are consulted.
#
# Frozen interpretations (documented here because the contract fixes the
# grammar in prose and this file is the executable form — flag any
# disagreement as a contract defect, do not loosen this file):
#
#   I1  env policy ids: ROW2-CLEAN-ENV-V1 is the base clean env (LC_ALL=C,
#       LANG=C, TZ=UTC only). Contract §3.1-9 additionally allows Git
#       envelopes to carry exactly GIT_CONFIG_NOSYSTEM=1 and
#       GIT_CONFIG_GLOBAL=/dev/null; that second frozen variant is the id
#       ROW2-CLEAN-ENV-GIT-V1. HOST-GIT-READ requires the GIT variant; every
#       other rule requires the base variant.
#   I2  HOST-PROCESS sleep bound: "秒数只为 packet 固定正十进制且不超过
#       timeout" is enforced as: positive decimal (^[1-9][0-9]*$) and ≤ the
#       packet's terminalTimeoutSeconds (fixture value 70).
#   I3  HOST-PROCESS kill: "-TERM <capture-pid>" is classified on shape
#       (positive decimal PID); "PID 只属于本 runner 已冻结的 host capture
#       process" is a launcher-RUNTIME property (PIDs are not packet-frozen)
#       and is enforced by the runner's launcher, not here.
#   I4  HOST-FILESET sort / HOST-HASH check-form pin cwdRef=evidence
#       ("sort 输入为冻结 path-list carrier"; manifest.sha256 is the evidence
#       root's canonical manifest).
#   I5  HOST-TEXT carrier count: exactly one carrier operand (contract:
#       "恰好一个已冻结 carrier 输入"), pattern 1..512 printable-ASCII bytes,
#       classifier outputs the pattern's SHA-256 (computed by the pure-bash
#       implementation in row2-envelope.sh).
#   I6  cwdRef pins: repo for HOST-GIT-READ (git runs inside the repo and
#       its operands are repo-rel); evidence for HOST-RUNNER-MODE and
#       HOST-CLASSIFIER (their input/output operands are evidence carriers)
#       and for HOST-FILESET / HOST-SQLITE (canonical roots). Payload tokens
#       like the runner/classifier path are NOT cwd-resolved argv paths —
#       the launcher resolves them via the executable manifest locationId
#       (contract §3.1-8), so they only need literal equality with the
#       packet-frozen repo-relative paths. ADB rules, HOST-BASH-SYNTAX and
#       HOST-ENV-TIME accept any declared root.
#   I7  ADB-READ-LSPOSED's "su -c" argument is the single argv element
#       "cat <p8-path>" for exactly the three packet-frozen device paths.
#
# Exit codes: 0 = classification result written (including CLASSIFIER-REJECT,
# which is a RESULT, not an error); 2 = usage / IO error.

set -uo pipefail
LC_ALL=C; LANG=C; TZ=UTC; export LC_ALL LANG TZ

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$SELF_DIR/row2-envelope.sh"

ENV_BASE="ROW2-CLEAN-ENV-V1"
ENV_GIT="ROW2-CLEAN-ENV-GIT-V1"
STDIN_CLOSED="ROW2-STDIN-CLOSED-V1"

# ---------------------------------------------------------------------------
# Frozen fixture constants (fixture mode only). Values are byte-identical to
# the contract's §3.1.1 packet-constant block; the real `classify` mode reads
# them from meta/execution-packet.json instead.
# ---------------------------------------------------------------------------
F_SERIAL="ZY22-FIXTURE-SERIAL"
F_PKG_BENCH="name.caiyao.fakegps.bench"
F_PKG_AUTO="com.example.cellrebelauto"
F_PKG_PROD="name.caiyao.fakegps"
F_COMP_BENCH_ACCEPT="$F_PKG_BENCH/name.caiyao.fakegps.mockprovider.MockProviderAcceptanceActivity"
F_COMP_QWY_COLLECTOR="$F_PKG_BENCH/name.caiyao.fakegps.integration.v1.FaultCollectorActivity"
F_COMP_AUTO_HANDSHAKE="$F_PKG_AUTO/com.example.cellrebelauto.integration.v1.HandshakeProbeActivity"
F_COMP_AUTO_STATE="$F_PKG_AUTO/com.example.cellrebelauto.integration.v1.ProviderRevokeCollectorActivity"
F_COMP_AUTO_PROBE="$F_PKG_AUTO/com.example.cellrebelauto.integration.v1.FullLoopProbeActivity"
F_P8_DB="/data/adb/lspd/config/modules_config.db"
F_P8_WAL="/data/adb/lspd/config/modules_config.db-wal"
F_P8_SHM="/data/adb/lspd/config/modules_config.db-shm"
F_BASEAPK_BENCH="/data/app/~~benchfixture/base.apk"
F_BASEAPK_AUTO="/data/app/~~autofixture/base.apk"
F_AUTO_SIGNER="5555555555555555555555555555555555555555555555555555555555555555"
F_RUNNER_REL="scripts/row2/row2-runner.sh"
F_CLASSIFIER_REL="scripts/row2/row2-classifier-v2.sh"
F_TERMINAL_TIMEOUT=70

# Synthetic canonical packet fragment for fixture mode — the SAME extractor
# and the SAME canonical serialization `classify` mode reads, so fixture mode
# exercises the production extraction path, not a parallel one.
fixture_packet_text() {
  printf '{"schemaVersion":2,"device":{"serial":"%s","adbServerPolicyId":"ROW2-PREEXISTING-LOCAL-ADB-SERVER-V1"},"packages":{"bench":{"applicationId":"%s","installedBaseApkPath":"%s"},"auto":{"applicationId":"%s","installedBaseApkPath":"%s","signerSha256":"%s"},"production":{"applicationId":"%s"}},"components":{"benchAcceptance":"%s","qwyCollector":"%s","autoHandshake":"%s","autoState":"%s","autoProbe":"%s"},"p8":{"rawDevicePaths":{"db":"%s","wal":"%s","shm":"%s"}},"runnerRepoRelativePath":"%s","accessClassifier":{"implementationRepoRelativePath":"%s"},"terminalTimeoutSeconds":%d}' \
    "$F_SERIAL" \
    "$F_PKG_BENCH" "$F_BASEAPK_BENCH" \
    "$F_PKG_AUTO" "$F_BASEAPK_AUTO" "$F_AUTO_SIGNER" \
    "$F_PKG_PROD" \
    "$F_COMP_BENCH_ACCEPT" "$F_COMP_QWY_COLLECTOR" "$F_COMP_AUTO_HANDSHAKE" "$F_COMP_AUTO_STATE" "$F_COMP_AUTO_PROBE" \
    "$F_P8_DB" "$F_P8_WAL" "$F_P8_SHM" \
    "$F_RUNNER_REL" "$F_CLASSIFIER_REL" "$F_TERMINAL_TIMEOUT"
}

# Frozen fixture executable manifest. Digests are fixture-visible 64-hex
# values (repeated two-char patterns) — distinct per executable so a wrong
# digest fixture can use a neighbor's value.
fixture_manifest_text() {
  cat <<'EOF'
{"schemaVersion":1,"executables":[
{"executableId":"adb","argv0Token":"adb","kind":"system","locationId":"SYS_BIN_ADB","sha256":"adadadadadadadadadadadadadadadadadadadadadadadadadadadadadadadad"},
{"executableId":"bash","argv0Token":"bash","kind":"system","locationId":"SYS_BIN_BASH","sha256":"babababababababababababababababababababababababababababababababa"},
{"executableId":"shasum","argv0Token":"shasum","kind":"system","locationId":"SYS_BIN_SHASUM","sha256":"5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a"},
{"executableId":"cat","argv0Token":"cat","kind":"system","locationId":"SYS_BIN_CAT","sha256":"cacacacacacacacacacacacacacacacacacacacacacacacacacacacacacacaca"},
{"executableId":"find","argv0Token":"find","kind":"system","locationId":"SYS_BIN_FIND","sha256":"f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1"},
{"executableId":"sort","argv0Token":"sort","kind":"system","locationId":"SYS_BIN_SORT","sha256":"5050505050505050505050505050505050505050505050505050505050505050"},
{"executableId":"ls","argv0Token":"ls","kind":"system","locationId":"SYS_BIN_LS","sha256":"1515151515151515151515151515151515151515151515151515151515151515"},
{"executableId":"stat","argv0Token":"stat","kind":"system","locationId":"SYS_BIN_STAT","sha256":"5757575757575757575757575757575757575757575757575757575757575757"},
{"executableId":"wc","argv0Token":"wc","kind":"system","locationId":"SYS_BIN_WC","sha256":"c0dec0dec0dec0dec0dec0dec0dec0dec0dec0dec0dec0dec0dec0dec0dec0de"},
{"executableId":"cmp","argv0Token":"cmp","kind":"system","locationId":"SYS_BIN_CMP","sha256":"cafecafecafecafecafecafecafecafecafecafecafecafecafecafecafecafe"},
{"executableId":"diff","argv0Token":"diff","kind":"system","locationId":"SYS_BIN_DIFF","sha256":"d1f1d1f1d1f1d1f1d1f1d1f1d1f1d1f1d1f1d1f1d1f1d1f1d1f1d1f1d1f1d1f1"},
{"executableId":"cp","argv0Token":"cp","kind":"system","locationId":"SYS_BIN_CP","sha256":"c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3"},
{"executableId":"chmod","argv0Token":"chmod","kind":"system","locationId":"SYS_BIN_CHMOD","sha256":"c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0"},
{"executableId":"mkdir","argv0Token":"mkdir","kind":"system","locationId":"SYS_BIN_MKDIR","sha256":"deaddeaddeaddeaddeaddeaddeaddeaddeaddeaddeaddeaddeaddeaddeaddead"},
{"executableId":"touch","argv0Token":"touch","kind":"system","locationId":"SYS_BIN_TOUCH","sha256":"7070707070707070707070707070707070707070707070707070707070707070"},
{"executableId":"grep","argv0Token":"grep","kind":"system","locationId":"SYS_BIN_GREP","sha256":"9e9e9e9e9e9e9e9e9e9e9e9e9e9e9e9e9e9e9e9e9e9e9e9e9e9e9e9e9e9e9e9e"},
{"executableId":"sqlite3","argv0Token":"sqlite3","kind":"system","locationId":"SYS_BIN_SQLITE3","sha256":"5151515151515151515151515151515151515151515151515151515151515151"},
{"executableId":"date","argv0Token":"date","kind":"system","locationId":"SYS_BIN_DATE","sha256":"da7eda7eda7eda7eda7eda7eda7eda7eda7eda7eda7eda7eda7eda7eda7eda7e"},
{"executableId":"uname","argv0Token":"uname","kind":"system","locationId":"SYS_BIN_UNAME","sha256":"d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4"},
{"executableId":"java","argv0Token":"java","kind":"system","locationId":"SYS_BIN_JAVA","sha256":"a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"},
{"executableId":"sleep","argv0Token":"sleep","kind":"system","locationId":"SYS_BIN_SLEEP","sha256":"5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e"},
{"executableId":"kill","argv0Token":"kill","kind":"system","locationId":"SYS_BIN_KILL","sha256":"1111111111111111111111111111111111111111111111111111111111111111"},
{"executableId":"git","argv0Token":"git","kind":"system","locationId":"SYS_BIN_GIT","sha256":"9179917991799179917991799179917991799179917991799179917991799179"},
{"executableId":"row2-runner","argv0Token":"bash","kind":"repo-payload","locationId":"PAYLOAD_ROW2_RUNNER","sha256":"4242424242424242424242424242424242424242424242424242424242424242","interpreterId":"bash"},
{"executableId":"row2-classifier","argv0Token":"bash","kind":"repo-payload","locationId":"PAYLOAD_ROW2_CLASSIFIER","sha256":"c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1","interpreterId":"bash"},
{"executableId":"fastboot","argv0Token":"fastboot","kind":"system","locationId":"SYS_BIN_FASTBOOT","sha256":"fbfbfbfbfbfbfbfbfbfbfbfbfbfbfbfbfbfbfbfbfbfbfbfbfbfbfbfbfbfbfbfb"}
]}
EOF
}

# ---------------------------------------------------------------------------
# Packet-constant extraction (canonical prefix scans; the packet is emitted
# by row2-packet.sh in this exact canonical serialization).
# ---------------------------------------------------------------------------
PKT=""; MFT=""

pkt_str() { # <literal-anchor> -> PKT_VALUE (empty + rc1 if absent)
  local anchor=$1 rest
  [[ $PKT == *"$anchor"* ]] || { PKT_VALUE=""; return 1; }
  rest=${PKT#*"$anchor"}
  PKT_VALUE=${rest%%\"*}
  [[ -n $PKT_VALUE ]] || { PKT_VALUE=""; return 1; }
  return 0
}

mft_entry() { # <executableId> -> M_SHA M_ARGV0 M_KIND (rc1 if absent)
  local id=$1 anchor rest entry
  anchor="\"executableId\":\"$id\""
  [[ $MFT == *"$anchor"* ]] || return 1
  rest=${MFT#*"$anchor"}
  M_ARGV0=${rest#*\"argv0Token\":\"}; M_ARGV0=${M_ARGV0%%\"*}
  M_KIND=${rest#*\"kind\":\"};   M_KIND=${M_KIND%%\"*}
  M_SHA=${rest#*\"sha256\":\"};  M_SHA=${M_SHA%%\"*}
  [[ -n $M_SHA && -n $M_ARGV0 ]] || return 1
  return 0
}

# ---------------------------------------------------------------------------
# Rule engine helpers
# ---------------------------------------------------------------------------
argc() { echo "${#E_ARGV[@]}"; }
a() { echo "${E_ARGV[$1]:-}"; }   # NOTE: $(a 3) strips nothing; empty elems ok

argv_eq_exact() { # <expected tokens...> — full array equality
  local exp=("$@")
  (( ${#E_ARGV[@]} == ${#exp[@]} )) || return 1
  local i
  for ((i = 0; i < ${#exp[@]}; i++)); do
    [[ ${E_ARGV[i]} == "${exp[i]}" ]] || return 1
  done
  return 0
}

is_64lowerhex() { [[ $1 =~ ^[0-9a-f]{64}$ ]]; }
is_pos_dec()    { [[ $1 =~ ^[1-9][0-9]*$ ]]; }
is_full_uuid()  { [[ $1 =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]]; }

valid_text_pattern() { # 1..512 bytes, every byte 0x20..0x7e
  local p=$1 n=${#1} i b
  (( n >= 1 && n <= 512 )) || return 1
  for ((i = 0; i < n; i++)); do
    printf -v b '%d' "'${p:i:1}"
    (( b &= 255 ))
    (( b >= 0x20 && b <= 0x7e )) || return 1
  done
  return 0
}

# raw P8 trio as evidence-relative carrier paths (HOST-CAT ban)
is_raw_p8_carrier() {
  [[ $1 == "raw/p8/modules_config.db" || $1 == "raw/p8/modules_config.db-wal" || $1 == "raw/p8/modules_config.db-shm" ]]
}

# grep-mode tuples (contract §3.1.1 HOST-TEXT): exact token sequences
GREP_MODES=(
  "-E" "-F"
  "-E -c" "-F -c"
  "-E -n" "-F -n"
  "-E -o" "-F -o"
  "-E -v" "-F -v"
  "-E -v -c" "-F -v -c"
)

# The three frozen SQL statements (byte-exact, contract §3.1.1)
SQL_1="PRAGMA integrity_check;"
SQL_2="SELECT module_pkg_name, enabled FROM modules ORDER BY mid;"
SQL_3="SELECT m.module_pkg_name, s.app_pkg_name, s.user_id FROM modules m JOIN scope s ON m.mid=s.mid ORDER BY m.module_pkg_name, s.app_pkg_name, s.user_id;"

# HOST-RUNNER-MODE finite mode-id manifest. Must stay byte-equal to the
# manifest statically extracted from row2-runner.sh — check-row2-exec.sh
# proves the equality, so a runner mode added without a classifier update
# fails the gate (and vice versa).
RUNNER_MODE_IDS=(
  "parse:envelope" "parse:packet"
  "audit:command-surface"
  "gate:six-file-carrier" "gate:prefire-write-boundary"
  "seal:file-set-equality"
)

runner_mode_ok() { # <mode> <mode-id> — combined "mode:mode-id" must be in the frozen manifest
  local combined="$1:$2" id
  for id in "${RUNNER_MODE_IDS[@]}"; do
    [[ $combined == "$id" ]] && return 0
  done
  return 1
}

# Fixture-id membership is checked against the frozen fixtures file (the
# same file fixture mode reads — no second manifest).
fixture_id_ok() {
  local id=$1 line
  [[ -r "$SELF_DIR/row2-classifier-v2-fixtures.json" ]] || return 1
  while IFS= read -r line; do
    [[ $line == \#* || -z $line ]] && continue
    [[ ${line%%|*} == "$id" ]] && return 0
  done < "$SELF_DIR/row2-classifier-v2-fixtures.json"
  return 1
}

# ---------------------------------------------------------------------------
# Rules — host allowlist (HOST-NONE)
# ---------------------------------------------------------------------------
r_HOST_ADB_VERSION() {
  argv_eq_exact adb version
}

r_HOST_GIT_READ() {
  [[ $E_ENVP == "$ENV_GIT" && $E_CWD == repo ]] || return 1
  local n=${#E_ARGV[@]}
  # shape a: status --porcelain=v1 (7 tokens, no operand)
  argv_eq_exact git --no-pager -c core.fsmonitor=false --no-optional-locks status --porcelain=v1 && return 0
  # shape b: rev-parse <HEAD-literal>
  if (( n == 4 )); then
    argv_eq_exact git --no-pager rev-parse "${E_ARGV[3]}" || return 1
    case "${E_ARGV[3]}" in
      HEAD|'HEAD^{tree}'|HEAD^) return 0 ;;
      HEAD:*) valid_rel_path "${E_ARGV[3]#HEAD:}" && return 0 ;;
    esac
    return 1
  fi
  # shape c: hash-object -- <repo-rel> (exactly one operand)
  if (( n == 5 )); then
    [[ ${E_ARGV[3]} == -- ]] || return 1
    valid_rel_path "${E_ARGV[4]}" || return 1
    argv_eq_exact git --no-pager hash-object "${E_ARGV[3]}" "${E_ARGV[4]}"
    return $?
  fi
  # shape d: diff ... -- <repo-rel>... (one or more operands)
  if (( n >= 8 )); then
    local p=0 i
    [[ ${E_ARGV[0]} == git && ${E_ARGV[1]} == --no-pager &&
       ${E_ARGV[2]} == diff && ${E_ARGV[3]} == --no-ext-diff &&
       ${E_ARGV[4]} == --no-textconv && ${E_ARGV[5]} == --exit-code &&
       ${E_ARGV[6]} == -- ]] || return 1
    for ((i = 7; i < n; i++)); do
      valid_rel_path "${E_ARGV[i]}" || return 1
      p=1
    done
    (( p )) && return 0
    return 1
  fi
  return 1
}

r_HOST_BASH_SYNTAX() {
  (( ${#E_ARGV[@]} == 3 )) || return 1
  argv_eq_exact bash -n "${E_ARGV[2]}"
  [[ ${E_ARGV[2]} == "$RUNNER_REL" ]] || return 1
  valid_rel_path "$RUNNER_REL"
}

r_HOST_RUNNER_MODE() {
  [[ $E_CWD == evidence ]] || return 1
  (( ${#E_ARGV[@]} == 6 )) || return 1
  argv_eq_exact bash "$RUNNER_REL" "${E_ARGV[2]}" "${E_ARGV[3]}" "${E_ARGV[4]}" "${E_ARGV[5]}" || return 1
  case "${E_ARGV[2]}" in parse|audit|gate|seal) ;; *) return 1 ;; esac
  runner_mode_ok "${E_ARGV[2]}" "${E_ARGV[3]}" || return 1
  valid_rel_path "${E_ARGV[4]}" && valid_rel_path "${E_ARGV[5]}"
}

r_HOST_CLASSIFIER() {
  [[ $E_CWD == evidence ]] || return 1
  (( ${#E_ARGV[@]} == 5 )) || return 1
  argv_eq_exact bash "$CLASSIFIER_REL" "${E_ARGV[2]}" "${E_ARGV[3]}" "${E_ARGV[4]}" || return 1
  case "${E_ARGV[2]}" in
    fixture) fixture_id_ok "${E_ARGV[3]}" && valid_rel_path "${E_ARGV[4]}" ;;
    classify) valid_rel_path "${E_ARGV[3]}" && valid_rel_path "${E_ARGV[4]}" ;;
    *) return 1 ;;
  esac
}

r_HOST_HASH() {
  local n=${#E_ARGV[@]}
  # shape 2: check the canonical manifest (cwdRef pinned to evidence)
  if (( n == 5 )); then
    [[ $E_CWD == evidence ]] || return 1
    argv_eq_exact shasum -a 256 -c manifest.sha256
    return $?
  fi
  # shape 1: direct hashing of one or more rel paths (any logical root)
  if (( n >= 5 )); then
    [[ ${E_ARGV[0]} == shasum && ${E_ARGV[1]} == -a && ${E_ARGV[2]} == 256 && ${E_ARGV[3]} == -- ]] || return 1
    local i
    for ((i = 4; i < n; i++)); do
      valid_rel_path "${E_ARGV[i]}" || return 1
    done
    return 0
  fi
  return 1
}

r_HOST_CAT() {
  (( ${#E_ARGV[@]} == 3 )) || return 1
  argv_eq_exact cat -- "${E_ARGV[2]}" || return 1
  valid_rel_path "${E_ARGV[2]}" || return 1
  if [[ $E_CWD == evidence ]]; then
    ! is_raw_p8_carrier "${E_ARGV[2]}"
  else
    return 0
  fi
}

r_HOST_FILESET() {
  [[ $E_CWD == evidence ]] || return 1
  argv_eq_exact find . -type f -print && return 0
  argv_eq_exact ls -A . && return 0
  if (( ${#E_ARGV[@]} == 3 )); then
    argv_eq_exact sort -- "${E_ARGV[2]}" || return 1
    valid_rel_path "${E_ARGV[2]}"
    return $?
  fi
  return 1
}

r_HOST_FILEMETA() {
  local n=${#E_ARGV[@]}
  if (( n == 3 )); then
    argv_eq_exact stat -- "${E_ARGV[2]}" || return 1
    valid_rel_path "${E_ARGV[2]}"
    return $?
  fi
  if (( n == 4 )); then
    case "${E_ARGV[0]}:${E_ARGV[1]}" in
      wc:-c|wc:-l)
        [[ ${E_ARGV[2]} == -- ]] || return 1
        argv_eq_exact "${E_ARGV[0]}" "${E_ARGV[1]}" -- "${E_ARGV[3]}" || return 1
        valid_rel_path "${E_ARGV[3]}" ;;
      cmp:-*|diff:-*)
        [[ ${E_ARGV[1]} == -- ]] || return 1
        valid_rel_path "${E_ARGV[2]}" && valid_rel_path "${E_ARGV[3]}" ;;
      *) return 1 ;;
    esac
    return $?
  fi
  return 1
}

r_HOST_EVIDENCE_FS() {
  local n=${#E_ARGV[@]}
  if (( n == 3 )); then
    if [[ ${E_ARGV[0]} == chmod ]]; then
      argv_eq_exact chmod 0444 "${E_ARGV[2]}" || return 1
      valid_rel_path "${E_ARGV[2]}"
      return $?
    fi
    if [[ ${E_ARGV[0]} == touch ]]; then
      argv_eq_exact touch -- "${E_ARGV[2]}" || return 1
      valid_rel_path "${E_ARGV[2]}"
      return $?
    fi
    return 1
  fi
  if (( n == 4 )); then
    if [[ ${E_ARGV[0]} == cp ]]; then
      argv_eq_exact cp -- "${E_ARGV[2]}" "${E_ARGV[3]}" || return 1
    elif [[ ${E_ARGV[0]} == mkdir ]]; then
      argv_eq_exact mkdir -p -- "${E_ARGV[3]}" || return 1
      valid_rel_path "${E_ARGV[3]}"
      return $?
    else
      return 1
    fi
    valid_rel_path "${E_ARGV[2]}" && valid_rel_path "${E_ARGV[3]}"
    return $?
  fi
  return 1
}

r_HOST_TEXT() {
  [[ ${E_ARGV[0]} == grep ]] || return 1
  local n=${#E_ARGV[@]} m mode_tokens=() mi i ok pat carrier
  for m in "${GREP_MODES[@]}"; do
    mode_tokens=($m)
    mi=${#mode_tokens[@]}
    (( n == mi + 5 )) || continue
    ok=1
    for ((i = 0; i < mi; i++)); do
      [[ ${E_ARGV[1+i]} == "${mode_tokens[i]}" ]] || { ok=0; break; }
    done
    (( ok )) || continue
    [[ ${E_ARGV[1+mi]} == -e ]] || continue
    pat=${E_ARGV[2+mi]}
    [[ ${E_ARGV[3+mi]} == -- ]] || continue
    carrier=${E_ARGV[4+mi]}
    valid_text_pattern "$pat" || return 1
    valid_rel_path "$carrier" || return 1
    TEXT_PATTERN_SHA=$(sha256_hex "$pat")
    return 0
  done
  return 1
}

r_HOST_SQLITE() {
  [[ $E_CWD == evidence ]] || return 1
  (( ${#E_ARGV[@]} == 8 )) || return 1
  argv_eq_exact sqlite3 -safe -batch -bail -readonly -nofollow query/p8/modules_config.db "${E_ARGV[7]}" || return 1
  case "${E_ARGV[7]}" in
    "$SQL_1"|"$SQL_2"|"$SQL_3") return 0 ;;
    *) return 1 ;;
  esac
}

r_HOST_ENV_TIME() {
  argv_eq_exact date -u +%Y-%m-%dT%H:%M:%SZ && return 0
  argv_eq_exact uname -a && return 0
  argv_eq_exact java -version && return 0
  return 1
}

r_HOST_PROCESS() {
  if (( ${#E_ARGV[@]} == 2 )) && [[ ${E_ARGV[0]} == sleep ]]; then
    is_pos_dec "${E_ARGV[1]}" || return 1
    (( ${E_ARGV[1]} <= TERMINAL_TIMEOUT )) || return 1
    return 0
  fi
  if (( ${#E_ARGV[@]} == 3 )) && [[ ${E_ARGV[0]} == kill && ${E_ARGV[1]} == -TERM ]]; then
    is_pos_dec "${E_ARGV[2]}"
    return $?
  fi
  return 1
}

# ---------------------------------------------------------------------------
# Rules — device allowlists (ADB-READ-* / ADB-WRITE-*)
# Literal packet-constant prefix ["adb","-s",<serial>,...] (ADB-READ-DEVICES
# is the exact ["adb","devices","-l"] exception, contract §3.1.1).
# ---------------------------------------------------------------------------
adb_rule() { # <expected tokens from index 3 onwards...>
  local exp=(adb -s "$SERIAL" "$@")
  argv_eq_exact "${exp[@]}"
}

r_ADB_READ_DEVICES()     { argv_eq_exact adb devices -l; }

r_ADB_READ_GETPROP() {
  adb_rule shell getprop ro.product.model && return 0
  adb_rule shell getprop ro.build.fingerprint && return 0
  adb_rule shell getprop ro.build.version.release && return 0
  adb_rule shell getprop ro.build.version.sdk && return 0
  adb_rule shell getprop persist.sys.timezone
}

r_ADB_READ_PM_PATH() {
  adb_rule shell pm path "$PKG_BENCH" && return 0
  adb_rule shell pm path "$PKG_AUTO"
}

r_ADB_READ_PACKAGE() {
  adb_rule shell dumpsys package "$PKG_BENCH" && return 0
  adb_rule shell dumpsys package "$PKG_AUTO" && return 0
  adb_rule shell dumpsys package "$PKG_PROD"
}

r_ADB_READ_APK_BYTES() {
  adb_rule exec-out cat "$BASEAPK_BENCH" && return 0
  adb_rule exec-out cat "$BASEAPK_AUTO"
}

r_ADB_READ_EPOCH() {
  adb_rule shell date +%s%3N
}

r_ADB_READ_LSPOSED() {
  adb_rule exec-out su -c "cat $P8_DB" && return 0
  adb_rule exec-out su -c "cat $P8_WAL" && return 0
  adb_rule exec-out su -c "cat $P8_SHM"
}

r_ADB_READ_APPOPS() {
  adb_rule shell appops query-op android:mock_location allow
}

r_ADB_READ_LOCATION() {
  adb_rule shell dumpsys location
}

r_ADB_READ_LOGCAT() {
  adb_rule logcat -v epoch
}

r_ADB_READ_SCREENSHOT() {
  adb_rule exec-out screencap -p
}

r_ADB_WRITE_PREPARE() {
  adb_rule shell am start -W -n "$COMP_BENCH_ACCEPT" --es command prepare_kyiv
}

r_ADB_WRITE_BENCH_STOP() {
  adb_rule shell am force-stop "$PKG_BENCH"
}

r_ADB_WRITE_DISCOVER() {
  adb_rule shell am start -W -n "$COMP_AUTO_HANDSHAKE"
}

r_ADB_WRITE_QDUMP() {
  adb_rule shell am start -W -n "$COMP_QWY_COLLECTOR" --es cmd dump --es app_id "$PKG_AUTO" --es signer "$AUTO_SIGNER"
}

r_ADB_WRITE_AUTO_STOP() {
  adb_rule shell am force-stop "$PKG_AUTO"
}

r_ADB_WRITE_ASTATE() {
  adb_rule shell am start -W -n "$COMP_AUTO_STATE" --es cmd state
}

r_ADB_WRITE_HOLD() {
  adb_rule shell am start -W -n "$COMP_AUTO_PROBE" --es fault hold_lease --el hold_ms 30000
}

r_ADB_WRITE_RERELEASE() {
  (( ${#E_ARGV[@]} == 15 )) || return 1   # bound before ${E_ARGV[14]} expansion
  adb_rule shell am start -W -n "$COMP_AUTO_PROBE" --es fault rerelease_stuck --es lease_id "${E_ARGV[14]}" || return 1
  is_full_uuid "${E_ARGV[14]}"
}

# ---------------------------------------------------------------------------
# Rule table + classification
# ---------------------------------------------------------------------------
RULE_IDS=(
  HOST_ADB_VERSION HOST_GIT_READ HOST_BASH_SYNTAX HOST_RUNNER_MODE HOST_CLASSIFIER
  HOST_HASH HOST_CAT HOST_FILESET HOST_FILEMETA HOST_EVIDENCE_FS HOST_TEXT
  HOST_SQLITE HOST_ENV_TIME HOST_PROCESS
  ADB_READ_DEVICES ADB_READ_GETPROP ADB_READ_PM_PATH ADB_READ_PACKAGE
  ADB_READ_APK_BYTES ADB_READ_EPOCH ADB_READ_LSPOSED ADB_READ_APPOPS
  ADB_READ_LOCATION ADB_READ_LOGCAT ADB_READ_SCREENSHOT
  ADB_WRITE_PREPARE ADB_WRITE_BENCH_STOP ADB_WRITE_DISCOVER ADB_WRITE_QDUMP
  ADB_WRITE_AUTO_STOP ADB_WRITE_ASTATE ADB_WRITE_HOLD ADB_WRITE_RERELEASE
)

rule_id_to_contract() { # HOST_ADB_VERSION -> HOST-ADB-VERSION
  local id=$1
  printf '%s' "${id//_/-}"
}

class_of() {
  case "$1" in
    HOST-*) echo "HOST-NONE" ;;
    ADB-READ-*) echo "DEVICE-READ" ;;
    ADB-WRITE-*) echo "DEVICE-WRITE" ;;
    *) echo "CLASSIFIER-REJECT" ;;
  esac
}

# classify_envelope <canonical-envelope-line> <carrier-label-for-output>
# -> CL_RULE_ID, CL_CLASS, CL_REJECT_KIND, CL_PATTERN_SHA (globals)
classify_envelope() {
  local line=$1
  CL_RULE_ID=""; CL_CLASS="CLASSIFIER-REJECT"; CL_REJECT_KIND=""; CL_PATTERN_SHA=""
  TEXT_PATTERN_SHA=""

  # pre-gate 1: canonical envelope
  if ! envelope_parse "$line"; then
    CL_REJECT_KIND="envelope-not-canonical"
    return 0
  fi
  if ! envelope_parse_canonical "$line"; then
    CL_REJECT_KIND="envelope-not-canonical"
    return 0
  fi

  # pre-gate 2: policies
  case "$E_ENVP" in
    "$ENV_BASE"|"$ENV_GIT") ;;
    *) CL_REJECT_KIND="env-policy"; return 0 ;;
  esac
  [[ $E_STDP == "$STDIN_CLOSED" ]] || { CL_REJECT_KIND="stdin-policy"; return 0; }

  # pre-gate 3: cwdRef
  case "$E_CWD" in repo|evidence|query) ;; *) CL_REJECT_KIND="cwd-ref"; return 0 ;; esac

  # pre-gate 4: executable manifest binding
  is_64lowerhex "$E_SHA" || { CL_REJECT_KIND="digest-format"; return 0; }
  if ! mft_entry "$E_EXECID"; then
    CL_REJECT_KIND="executable-not-in-manifest"
    return 0
  fi
  [[ $M_SHA == "$E_SHA" ]] || { CL_REJECT_KIND="executable-digest-mismatch"; return 0; }
  [[ $M_ARGV0 == "${E_ARGV[0]}" ]] || { CL_REJECT_KIND="argv0-not-manifest-token"; return 0; }

  # rules: evaluate ALL, require exactly one match
  local -a matched=()
  local rid fn
  for rid in "${RULE_IDS[@]}"; do
    fn="r_${rid}"
    if "$fn"; then
      matched+=("$(rule_id_to_contract "$rid")")
    fi
  done
  if (( ${#matched[@]} == 1 )); then
    CL_RULE_ID="${matched[0]}"
    CL_CLASS="$(class_of "$CL_RULE_ID")"
    CL_PATTERN_SHA="$TEXT_PATTERN_SHA"
  elif (( ${#matched[@]} == 0 )); then
    CL_REJECT_KIND="no-rule-match"
  else
    local joined IFS=,
    joined="${matched[*]}"
    CL_REJECT_KIND="multi-rule-match:$joined"
  fi
  return 0
}

# ---------------------------------------------------------------------------
# Output emission (canonical JSON lines)
# ---------------------------------------------------------------------------
emit_classify_output() { # <carrier> <out-path>
  local carrier=$1 out=$2
  local line
  if (( ${#E_ARGV[@]} > 0 )); then
    line=$(envelope_emit "$E_EXECID" "$E_SHA" "$E_CWD" "$E_ENVP" "$E_STDP" "${E_ARGV[@]}")
  else
    line=""
  fi
  local argv_join="" elem
  local sep=$'\x01'
  if (( ${#E_ARGV[@]} > 0 )); then
    for elem in "${E_ARGV[@]}"; do
      argv_join+="${elem}${sep}"
    done
    argv_join=${argv_join%"$sep"}
  fi
  printf '{"commandCarrier":"%s","executableId":"%s","executableSha256":"%s","cwdRef":"%s","envPolicyId":"%s","stdinPolicyId":"%s","argvCount":%d,"argvSha256":"%s","envelopeSha256":"%s","ruleId":"%s","derivedClass":"%s","rejectKind":"%s","patternSha256":"%s"}\n' \
    "$(json_escape "$carrier")" "$(json_escape "$E_EXECID")" "$(json_escape "$E_SHA")" \
    "$(json_escape "$E_CWD")" "$(json_escape "$E_ENVP")" "$(json_escape "$E_STDP")" \
    "${#E_ARGV[@]}" "$(sha256_hex "$argv_join")" "$(sha256_hex "$line")" \
    "$(json_escape "$CL_RULE_ID")" "$(json_escape "$CL_CLASS")" "$(json_escape "$CL_REJECT_KIND")" \
    "$(json_escape "$CL_PATTERN_SHA")" > "$out"
}

# ---------------------------------------------------------------------------
# Modes
# ---------------------------------------------------------------------------
load_constants_from_packet() {
  pkt_str '"device":{"serial":"' && SERIAL="$PKT_VALUE" || { SERIAL=""; return 1; }
  pkt_str '"bench":{"applicationId":"' && PKG_BENCH="$PKT_VALUE" || return 1
  pkt_str '"auto":{"applicationId":"'   && PKG_AUTO="$PKT_VALUE" || return 1
  pkt_str '"production":{"applicationId":"' && PKG_PROD="$PKT_VALUE" || return 1
  pkt_str '"benchAcceptance":"'   && COMP_BENCH_ACCEPT="$PKT_VALUE" || return 1
  pkt_str '"qwyCollector":"'      && COMP_QWY_COLLECTOR="$PKT_VALUE" || return 1
  pkt_str '"autoHandshake":"'     && COMP_AUTO_HANDSHAKE="$PKT_VALUE" || return 1
  pkt_str '"autoState":"'         && COMP_AUTO_STATE="$PKT_VALUE" || return 1
  pkt_str '"autoProbe":"'         && COMP_AUTO_PROBE="$PKT_VALUE" || return 1
  pkt_str "\"rawDevicePaths\":{\"db\":\"" && P8_DB="$PKT_VALUE" || return 1
  # wal/shm anchored on the just-extracted db value — deterministic even if
  # the packet later grows other wal/shm keys
  pkt_str "\"$P8_DB\",\"wal\":\"" && P8_WAL="$PKT_VALUE" || return 1
  pkt_str "\"$P8_WAL\",\"shm\":\"" && P8_SHM="$PKT_VALUE" || return 1
  pkt_str '"runnerRepoRelativePath":"' && RUNNER_REL="$PKT_VALUE" || return 1
  pkt_str '"implementationRepoRelativePath":"' && CLASSIFIER_REL="$PKT_VALUE" || return 1
  # bench/auto installedBaseApkPath + auto signer (scoped to their blocks)
  local rest
  rest=${PKT#*'"bench":{'}
  BASEAPK_BENCH=${rest#*'"installedBaseApkPath":"'}
  BASEAPK_BENCH=${BASEAPK_BENCH%%\"*}
  rest=${PKT#*'"auto":{'}
  BASEAPK_AUTO=${rest#*'"installedBaseApkPath":"'}
  BASEAPK_AUTO=${BASEAPK_AUTO%%\"*}
  AUTO_SIGNER=${rest#*'"signerSha256":"'}
  AUTO_SIGNER=${AUTO_SIGNER%%\"*}
  TERMINAL_TIMEOUT=${PKT#*'"terminalTimeoutSeconds":'}
  TERMINAL_TIMEOUT=${TERMINAL_TIMEOUT%%[!0-9]*}
  [[ -n $SERIAL && -n $PKG_BENCH && -n $RUNNER_REL && -n $P8_WAL && -n $BASEAPK_BENCH && -n $AUTO_SIGNER ]] || return 1
  return 0
}

read_file_line() { # <path> -> REPLY_LINE (first line; strips trailing \n)
  local line=""
  IFS= read -r line < "$1" || true
  REPLY_LINE=$line
}

main() {
  if (( $# != 3 )); then
    printf 'usage: bash row2-classifier-v2.sh classify <command-carrier-rel> <output-rel>\n       bash row2-classifier-v2.sh fixture <fixture-id> <output-rel>\n' >&2
    exit 2
  fi
  local mode=$1 arg=$2 out=$3

  if [[ $mode == fixture ]]; then
    PKT="$(fixture_packet_text)"
    MFT="$(fixture_manifest_text)"
    load_constants_from_packet || { printf 'internal: fixture constants failed to load\n' >&2; exit 2; }
    local fline found=""
    [[ -r "$SELF_DIR/row2-classifier-v2-fixtures.json" ]] || { printf 'fixtures file missing\n' >&2; exit 2; }
    while IFS= read -r fline; do
      [[ $fline == \#* || -z $fline ]] && continue
      [[ ${fline%%|*} == "$arg" ]] && { found=$fline; break; }
    done < "$SELF_DIR/row2-classifier-v2-fixtures.json"
    [[ -n $found ]] || { printf 'unknown fixture-id: %s\n' "$arg" >&2; exit 2; }
    local fixture_id exp_rule exp_class env_json
    fixture_id=${found%%|*}; found=${found#*|}
    exp_rule=${found%%|*};   found=${found#*|}
    exp_class=${found%%|*};  found=${found#*|}
    env_json=$found
    classify_envelope "$env_json" "$fixture_id"
    local verdict=FAIL
    if [[ $CL_CLASS == "$exp_class" && $CL_RULE_ID == "$exp_rule" ]]; then
      verdict=PASS
    fi
    printf '{"fixtureId":"%s","expectedRuleId":"%s","expectedClass":"%s","actualRuleId":"%s","actualClass":"%s","rejectKind":"%s","verdict":"%s"}\n' \
      "$fixture_id" "$exp_rule" "$exp_class" "$CL_RULE_ID" "$CL_CLASS" "$CL_REJECT_KIND" "$verdict" > "$out"
    [[ $verdict == PASS ]] && return 0
    return 1
  fi

  if [[ $mode == classify ]]; then
    valid_rel_path "$arg" || { printf 'carrier path not a valid logical-root rel path: %s\n' "$arg" >&2; exit 2; }
    valid_rel_path "$out" || { printf 'output path not a valid logical-root rel path: %s\n' "$out" >&2; exit 2; }
    [[ -r "meta/execution-packet.json" && -r "meta/executable-manifest.json" ]] || {
      printf 'classify mode requires meta/execution-packet.json and meta/executable-manifest.json under CWD\n' >&2
      exit 2
    }
    PKT=""
    local ln
    while IFS= read -r ln; do PKT+="$ln"; done < "meta/execution-packet.json"
    MFT=""
    while IFS= read -r ln; do MFT+="$ln"; done < "meta/executable-manifest.json"
    load_constants_from_packet || { printf 'packet constants missing/malformed\n' >&2; exit 2; }
    [[ -r "$arg" ]] || { printf 'carrier not found: %s\n' "$arg" >&2; exit 2; }
    read_file_line "$arg"
    classify_envelope "$REPLY_LINE" "$arg"
    emit_classify_output "$arg" "$out"
    return 0
  fi

  printf 'unknown mode: %s\n' "$mode" >&2
  exit 2
}

main "$@"
