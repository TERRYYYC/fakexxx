#!/usr/bin/env bash
#
# row2-packet.sh — execution-packet builder, PRE-00 schema validator and
# executable-manifest freezer for the G2 Row 2 execution plane.
#
# Contract: docs/acceptance/g2-p10-row2-evidence-contract.md (PR #55, blob
# c072c83fa979cf9d222a544faf8366e6fa691d21) §3.1 (packet fields), §3.1-8
# (executable manifest), PRE-00 (schema validation), §3.1-4 (no access
# labels).
#
# HOST PREP TOOLING — not a packet leaf. It may use ordinary host tools
# (shasum, stat); at authorized run time its outputs are frozen, hashed by
# HOST-HASH command units, and consumed by the builtins-only payloads.
#
# Subcommands:
#   row2-packet.sh manifest-freeze <evidence-dir> <executableId>...
#       Resolve each executableId through the runner's frozen LOCATION map
#       (parsed from row2-runner.sh — single source of truth), hash the
#       canonical path (shasum -a 256), record its stat mode, and write
#       meta/executable-manifest.json in canonical key order.
#   row2-packet.sh build <evidence-dir> <spec.bash>
#       A spec is a bash file that sets run-specific variables and calls
#       add_command (see spec_spec_vars below). The builder emits
#       meta/execution-packet.json with the frozen canonical key order the
#       classifier/runner parse; carrier paths are derived mechanically from
#       each command's phase/seq/slug (contract §3.2 stem rule).
#   row2-packet.sh validate <evidence-dir>
#       PRE-00: schemaVersion, required keys, NO unknown keys, seq
#       contiguous+unique from 001, carrier paths unique + inside the
#       evidence root, envelope completeness, policy literals, and ZERO
#       access-label fields anywhere in the packet (§3.1-4).
#
# Exit codes: 0 ok; 1 validation/verification failure (specific finding on
# stderr); 2 usage.

set -uo pipefail
LC_ALL=C; LANG=C; TZ=UTC; export LC_ALL LANG TZ

# builtin-only, ABSOLUTE SELF_DIR (dirname is an external binary — the PATH=
# review finding; and ${var%/*} eats the only slash for single-dir relative
# invocations, so resolve through the cd/pwd builtins instead)
if [[ ${BASH_SOURCE[0]} == */* ]]; then
  SELF_DIR=$(cd -- "${BASH_SOURCE[0]%/*}" && pwd)
else
  SELF_DIR=$PWD
fi
. "$SELF_DIR/row2-envelope.sh"

ENV_BASE="ROW2-CLEAN-ENV-V1"
ENV_GIT="ROW2-CLEAN-ENV-GIT-V1"
STDIN_CLOSED="ROW2-STDIN-CLOSED-V1"

RUNNER_SRC="$SELF_DIR/row2-runner.sh"
REPO="$(cd "$SELF_DIR/../.." && pwd)"

# frozen executable semantics (argv0Token / kind mirror the classifier's
# fixture manifest and the executable-manifest template)
exec_meta() { # <execId> -> argv0Token kind
  case "$1" in
    adb) echo "adb system" ;;
    bash) echo "bash system" ;;
    shasum) echo "shasum system" ;;
    cat) echo "cat system" ;;
    find) echo "find system" ;;
    sort) echo "sort system" ;;
    ls) echo "ls system" ;;
    stat) echo "stat system" ;;
    wc) echo "wc system" ;;
    cmp) echo "cmp system" ;;
    diff) echo "diff system" ;;
    cp) echo "cp system" ;;
    chmod) echo "chmod system" ;;
    mkdir) echo "mkdir system" ;;
    touch) echo "touch system" ;;
    grep) echo "grep system" ;;
    sqlite3) echo "sqlite3 system" ;;
    date) echo "date system" ;;
    uname) echo "uname system" ;;
    java) echo "java system" ;;
    sleep) echo "sleep system" ;;
    kill) echo "kill system" ;;
    git) echo "git system" ;;
    fastboot) echo "fastboot system" ;;
    row2-runner) echo "bash repo-payload" ;;
    row2-classifier) echo "bash repo-payload" ;;
    *) return 1 ;;
  esac
}

location_for() { # <execId> -> canonical path (parsed from the runner's frozen map)
  local id=$1 locid line
  case "$id" in
    row2-runner) locid="SYS_BIN_BASH"; local p="LOCATION_PAYLOAD_ROW2_RUNNER" ;;
    row2-classifier) locid="SYS_BIN_BASH"; local p="LOCATION_PAYLOAD_ROW2_CLASSIFIER" ;;
    *) locid="SYS_BIN_$(echo "$id" | tr '[:lower:]' '[:upper:]')" ;;
  esac
  # repo payloads resolve through their own map entry
  case "$id" in
    row2-runner|row2-classifier)
      line=$(grep -E "^LOCATION_PAYLOAD_$(echo "$id" | tr '[:lower:]' '[:upper:]' | tr -- - _ )=" "$RUNNER_SRC") ;;
    *)
      line=$(grep -E "^LOCATION_${locid}=" "$RUNNER_SRC") ;;
  esac
  [[ -n $line ]] || return 1
  local v="${line#*=\"}"
  v=${v%\"}
  # the runner's map stores $REPO_ROOT for repo payloads — resolve it against
  # THIS repo root (single source of truth stays the runner source)
  printf '%s' "${v//\$REPO_ROOT/$REPO}"
}

stat_mode() { # <path> -> portable mode string
  # GNU `stat -f '%Sp'` does NOT fail — it "succeeds" printing default
  # filesystem-stat output (CI lesson: mode drift "File: ..." != frozen).
  # So: try GNU -c first, VALIDATE the shape, then BSD -f, validate again.
  local m
  m=$(/usr/bin/stat -c '%A' "$1" 2>/dev/null || /bin/stat -c '%A' "$1" 2>/dev/null) \
    || m=$(/usr/bin/stat -f '%Sp' "$1" 2>/dev/null || /bin/stat -f '%Sp' "$1" 2>/dev/null)
  [[ $m =~ ^[bcdlps-]?[rwxStTs-]{9}$ ]] || return 1
  printf '%s' "$m"
}

# ---------------------------------------------------------------------------
# manifest-freeze
# ---------------------------------------------------------------------------
cmd_manifest_freeze() {
  local ev=$1; shift
  mkdir -p "$ev/meta"
  local id path sha mode meta argv0 kind out=""
  for id in "$@"; do
    meta=$(exec_meta "$id") || { printf 'manifest-freeze: unknown executableId %s\n' "$id" >&2; return 1; }
    argv0=${meta%% *}; kind=${meta##* }
    path=$(location_for "$id") || { printf 'manifest-freeze: no LOCATION entry for %s\n' "$id" >&2; return 1; }
    [[ -f $path && ! -L $path ]] || { printf 'manifest-freeze: %s -> %s missing/symlink\n' "$id" "$path" >&2; return 1; }
    sha=$(shasum -a 256 -- "$path" | { read -r h _; echo "$h"; })
    mode=$(stat_mode "$path")
    [[ -n $sha && -n $mode ]] || return 1
    local interp=""
    if [[ $kind == repo-payload ]]; then interp=',"interpreterId":"bash"'; fi
    out+="{\"executableId\":\"$id\",\"argv0Token\":\"$argv0\",\"kind\":\"$kind\",\"locationId\":\"$(locid_for "$id")\",\"sha256\":\"$sha\",\"modePattern\":\"$(json_escape "$mode")\"$interp}"$'\n'
  done
  # canonical single-line JSON array
  local json=""
  while IFS= read -r line; do
    [[ -z $line ]] && continue
    [[ -n $json ]] && json+=","
    json+="$line"
  done <<< "$out"
  printf '{"schemaVersion":1,"executables":[%s]}\n' "$json" > "$ev/meta/executable-manifest.json"
  printf 'manifest-freeze: %d executables -> %s/meta/executable-manifest.json\n' "$#" "$ev"
}

locid_for() {
  case "$1" in
    row2-runner) echo "PAYLOAD_ROW2_RUNNER" ;;
    row2-classifier) echo "PAYLOAD_ROW2_CLASSIFIER" ;;
    *) echo "SYS_BIN_$(echo "$1" | tr '[:lower:]' '[:upper:'])" ;;
  esac
}

# ---------------------------------------------------------------------------
# build
# ---------------------------------------------------------------------------
CMDS=""      # accumulated canonical command objects
CMD_COUNT=0

add_command() { # <seq> <phase> <slug> <execId> <cwdRef> <envPolicyId> -- <argv...>
  local seq=$1 phase=$2 slug=$3 execid=$4 cwd=$5 envp=$6; shift 6
  [[ ${1:-} == -- ]] || { printf 'add_command: expected -- before argv\n' >&2; exit 2; }
  shift
  local sha=$(mft_sha_for "$execid")
  [[ -n $sha ]] || { printf 'add_command: %s not in frozen manifest — run manifest-freeze first\n' "$execid" >&2; exit 1; }
  local stem="$phase/$seq-$slug"
  local argv_json="" first=1 a
  for a in "$@"; do
    if (( first )); then first=0; else argv_json+=","; fi
    argv_json+="\"$(json_escape "$a")\""
  done
  local obj
  printf -v obj '{"seq":"%s","checklistIds":[%s],"phase":"%s","slug":"%s","executableId":"%s","executableSha256":"%s","cwdRef":"%s","envPolicyId":"%s","stdinPolicyId":"%s","argv":[%s],"carrier":{"command":"%s.command.txt","stdout":"%s.stdout.txt","stderr":"%s.stderr.bin","exit":"%s.exit.txt","startUtc":"%s.start-utc.txt","endUtc":"%s.end-utc.txt"}}' \
    "$seq" "$(checklist_ids_json "$slug")" "$phase" "$slug" \
    "$(json_escape "$execid")" "$sha" "$(json_escape "$cwd")" "$(json_escape "$envp")" "$STDIN_CLOSED" "$argv_json" \
    "$stem" "$stem" "$stem" "$stem" "$stem" "$stem"
  [[ -n $CMDS ]] && CMDS+=","
  CMDS+="$obj"
  CMD_COUNT=$((CMD_COUNT+1))
}

# placeholder mapping so specs stay terse: slug -> checklist ids list
checklist_ids_json() {
  case "$1" in
    bash-n) echo '"PRE-03"' ;;
    classifier-fixture) echo '"PRE-04"' ;;
    hash-direct) echo '"PRE-02"' ;;
    host-cat) echo '"PRE-02"' ;;
    fileset-sort) echo '"FRZ-01"' ;;
    text-grep) echo '"INJ-04"' ;;
    *) echo "\"$1\"" ;;
  esac
}

mft_sha_for() { # <execId> from <EVID>/meta/executable-manifest.json
  local anchor="\"executableId\":\"$1\"" rest
  [[ $MFT_TEXT == *"$anchor"* ]] || return 1
  rest=${MFT_TEXT#*"$anchor"}
  rest=${rest#*'"sha256":"'}
  printf '%s' "${rest%%\"*}"
}

MFT_TEXT=""

spec_spec_vars() {
  cat >&2 <<'EOF'
spec files set (bash):
  SPEC_contractGitHead SPEC_contractBlobSha SPEC_contractSha256
  SPEC_evidenceDirName SPEC_runId
  SPEC_candidateHead SPEC_candidateTree SPEC_buildType SPEC_gradleTasks (array)
  SPEC_contractYamlSha256
  SPEC_build_commandDigest SPEC_build_reportDigest SPEC_build_manifestDigest SPEC_build_sandboxReportDigest
  SPEC_host_os ... SPEC_host_gitRepoConfigSha256   (hostEnvironment)
  SPEC_device_serial SPEC_device_model ... (device; adbServerPolicyId fixed)
  SPEC_pkg_bench_* SPEC_pkg_auto_* SPEC_pkg_production_id
  SPEC_comp_* (5 components)
  SPEC_p8_db/wal/shm SPEC_p8_expectedModules...
  SPEC_kyiv_*
  SPEC_roles_executorTaskId ... SPEC_roles_validityOwner
  SPEC_holdMs SPEC_terminalTimeoutSeconds
then call: add_command <seq> <phase> <slug> <execId> <cwdRef> <envPolicyId> -- <argv...>
EOF
}

cmd_build() {
  local ev=$1 spec=$2
  [[ -r "$ev/meta/executable-manifest.json" ]] || {
    printf 'build: meta/executable-manifest.json missing — run manifest-freeze first\n' >&2
    return 1
  }
  # the spec's relative file writes and the runner's cwdRef=evidence semantics
  # both expect the evidence root as CWD — run the spec there
  cd "$ev" || return 1
  MFT_TEXT=""
  local ln
  while IFS= read -r ln; do MFT_TEXT+="$ln"; done < "$ev/meta/executable-manifest.json"
  # shellcheck disable=SC1090
  source "$spec"
  local manifest_sha runner_sha classifier_sha
  manifest_sha=$(shasum -a 256 -- "$ev/meta/executable-manifest.json" | { read -r h _; echo "$h"; })
  runner_sha=$(shasum -a 256 -- "$SELF_DIR/row2-runner.sh" | { read -r h _; echo "$h"; })
  classifier_sha=$(shasum -a 256 -- "$SELF_DIR/row2-classifier-v2.sh" | { read -r h _; echo "$h"; })
  local gradle_tasks=""
  if (( ${#SPEC_gradleTasks[@]} > 0 )); then
    local t first=1
    for t in "${SPEC_gradleTasks[@]}"; do
      if (( first )); then first=0; else gradle_tasks+=","; fi
      gradle_tasks+="\"$(json_escape "$t")\""
    done
  fi
  local seal=""
  if [[ -n ${SPEC_sealControlPaths[@]+x} ]] && (( ${#SPEC_sealControlPaths[@]} > 0 )); then
    local p first=1
    for p in "${SPEC_sealControlPaths[@]}"; do
      if (( first )); then first=0; else seal+=","; fi
      seal+="\"$(json_escape "$p")\""
    done
  fi
  local pkt
  printf -v pkt '{"schemaVersion":2,"contractGitHead":"%s","contractBlobSha":"%s","contractSha256":"%s","runnerRepoRelativePath":"scripts/row2/row2-runner.sh","runnerSha256":"%s","accessClassifier":{"policyId":"ROW2-EXEC-ACCESS-V2","implementationRepoRelativePath":"scripts/row2/row2-classifier-v2.sh","implementationSha256":"%s"},"executionEnvelope":{"executableManifestRelativePath":"meta/executable-manifest.json","executableManifestSha256":"%s","envPolicyId":"%s","stdinPolicyId":"%s"},"evidenceDirName":"%s","runId":"%s","candidateHead":"%s","candidateTree":"%s","buildType":"%s","gradleTasks":[%s],"contractYamlSha256":"%s","buildEvidence":{"commandDigest":"%s","reportDigest":"%s","manifestDigest":"%s","sandboxPolicyId":"ROW2-BUILD-NO-DEVICE-V1","sandboxReportDigest":"%s"},"hostEnvironment":{"os":"%s","kernel":"%s","java":"%s","gradle":"%s","androidSdk":"%s","adb":"%s","sqlite":"%s","shasum":"%s","bash":"%s","gitRepoConfigSha256":"%s"},"device":{"serial":"%s","model":"%s","fingerprint":"%s","androidRelease":"%s","api":"%s","timezone":"%s","adbServerPolicyId":"ROW2-PREEXISTING-LOCAL-ADB-SERVER-V1"},"packages":{"bench":{"applicationId":"%s","artifactRepoRelativePath":"%s","artifactSha256":"%s","versionCode":"%s","versionName":"%s","signerSha256":"%s","installedBaseApkPath":"%s"},"auto":{"applicationId":"%s","artifactRepoRelativePath":"%s","artifactSha256":"%s","versionCode":"%s","versionName":"%s","signerSha256":"%s","installedBaseApkPath":"%s"},"production":{"applicationId":"%s"}},"components":{"benchAcceptance":"%s","qwyCollector":"%s","autoHandshake":"%s","autoState":"%s","autoProbe":"%s"},"p8":{"rawDevicePaths":{"db":"%s","wal":"%s","shm":"%s"},"expectedModules":"%s","expectedScopes":"%s","expectedMockAllowPackages":"%s"},"kyiv":{"scheduleId":"%s","scheduleVersion":"%s","currentItemId":"%s","expectedBeforeState":"%s","expectedAfterState":"%s"},"roles":{"executorTaskId":"%s","executorOwner":"%s","recorderTaskId":"%s","recorderOwner":"%s","validityTaskId":"%s","validityOwner":"%s"},"holdMs":%s,"terminalTimeoutSeconds":%s,"terminalReadMaxDelaySeconds":%s,"commands":[%s],"sealControlPaths":[%s]}\n' \
    "$SPEC_contractGitHead" "$SPEC_contractBlobSha" "$SPEC_contractSha256" \
    "$runner_sha" "$classifier_sha" "$manifest_sha" "$ENV_BASE" "$STDIN_CLOSED" \
    "$SPEC_evidenceDirName" "$SPEC_runId" \
    "$SPEC_candidateHead" "$SPEC_candidateTree" "$SPEC_buildType" "$gradle_tasks" "$SPEC_contractYamlSha256" \
    "$SPEC_build_commandDigest" "$SPEC_build_reportDigest" "$SPEC_build_manifestDigest" "$SPEC_build_sandboxReportDigest" \
    "$SPEC_host_os" "$SPEC_host_kernel" "$SPEC_host_java" "$SPEC_host_gradle" "$SPEC_host_androidSdk" "$SPEC_host_adb" "$SPEC_host_sqlite" "$SPEC_host_shasum" "$SPEC_host_bash" "$SPEC_host_gitRepoConfigSha256" \
    "$SPEC_device_serial" "${SPEC_device_model:-}" "${SPEC_device_fingerprint:-}" "${SPEC_device_androidRelease:-}" "${SPEC_device_api:-}" "${SPEC_device_timezone:-}" \
    "$SPEC_pkg_bench_applicationId" "$SPEC_pkg_bench_artifactRepoRelativePath" "$SPEC_pkg_bench_artifactSha256" "$SPEC_pkg_bench_versionCode" "$SPEC_pkg_bench_versionName" "$SPEC_pkg_bench_signerSha256" "$SPEC_pkg_bench_installedBaseApkPath" \
    "$SPEC_pkg_auto_applicationId" "$SPEC_pkg_auto_artifactRepoRelativePath" "$SPEC_pkg_auto_artifactSha256" "$SPEC_pkg_auto_versionCode" "$SPEC_pkg_auto_versionName" "$SPEC_pkg_auto_signerSha256" "$SPEC_pkg_auto_installedBaseApkPath" \
    "$SPEC_pkg_production_id" \
    "$SPEC_comp_benchAcceptance" "$SPEC_comp_qwyCollector" "$SPEC_comp_autoHandshake" "$SPEC_comp_autoState" "$SPEC_comp_autoProbe" \
    "$SPEC_p8_db" "$SPEC_p8_wal" "$SPEC_p8_shm" "$SPEC_p8_expectedModules" "$SPEC_p8_expectedScopes" "$SPEC_p8_expectedMockAllowPackages" \
    "$SPEC_kyiv_scheduleId" "$SPEC_kyiv_scheduleVersion" "$SPEC_kyiv_currentItemId" "$SPEC_kyiv_expectedBeforeState" "$SPEC_kyiv_expectedAfterState" \
    "$SPEC_roles_executorTaskId" "$SPEC_roles_executorOwner" "$SPEC_roles_recorderTaskId" "$SPEC_roles_recorderOwner" "$SPEC_roles_validityTaskId" "$SPEC_roles_validityOwner" \
    "${SPEC_holdMs:-30000}" "${SPEC_terminalTimeoutSeconds:-70}" "${SPEC_terminalReadMaxDelaySeconds:-10}" \
    "$CMDS" "$seal"
  printf '%s\n' "$pkt" > "$ev/meta/execution-packet.json"
  printf 'build: %d commands -> %s/meta/execution-packet.json\n' "$CMD_COUNT" "$ev"
}

# ---------------------------------------------------------------------------
# validate (PRE-00)
# ---------------------------------------------------------------------------
REQUIRED_TOP="schemaVersion contractGitHead contractBlobSha contractSha256 runnerRepoRelativePath runnerSha256 accessClassifier executionEnvelope evidenceDirName runId candidateHead candidateTree buildType gradleTasks contractYamlSha256 buildEvidence hostEnvironment device packages components p8 kyiv roles holdMs terminalTimeoutSeconds terminalReadMaxDelaySeconds commands sealControlPaths"
FORBIDDEN_ACCESS_KEYS='"deviceAccess" '"'"'declaredAccess'"'"' "accessClass" "deviceAccessClass" "declaredRuleId"'

cmd_validate() {
  local ev=$1
  [[ -r "$ev/meta/execution-packet.json" ]] || { printf 'PRE-00 FAIL: packet missing\n' >&2; return 1; }
  local pkt="" ln
  while IFS= read -r ln; do pkt+="$ln"; done < "$ev/meta/execution-packet.json"
  local fails=0
  # schemaVersion
  [[ $pkt == '{"schemaVersion":2,'* ]] || { printf 'PRE-00 FAIL: schemaVersion must be 2 (canonical prefix)\n' >&2; fails=1; }
  # top-level executionEnvelope policy literals (the command-level checks in
  # validate_command_object are NOT enough — the sed lesson: a mutant hit only
  # this top-level field and passed). The pattern is built via printf so the
  # digest glob stays a GLOB while the policy ids substitute literally.
  local ee_pat
  printf -v ee_pat '*"executionEnvelope":{"executableManifestRelativePath":"meta/executable-manifest.json","executableManifestSha256":"[0-9a-f]*","envPolicyId":"%s","stdinPolicyId":"%s"*' \
    "$ENV_BASE" "$STDIN_CLOSED"
  [[ $pkt == $ee_pat ]] \
    || { printf 'PRE-00 FAIL: executionEnvelope env/stdin policy literals\n' >&2; fails=1; }
  # access-label scan FIRST (§3.1-4: the packet schema must not contain any)
  local key
  for key in deviceAccess declaredAccess accessClass deviceAccessClass declaredClass; do
    if [[ $pkt == *'"'$key'":'* ]]; then
      printf 'PRE-00 FAIL: access-label field "%s" present — packet cannot self-report access (§3.1-4)\n' "$key" >&2
      fails=1
    fi
  done
  # required top-level keys, in canonical order, no unknown keys: rebuild the
  # expected key skeleton and require byte presence of each "key": in order
  local prev_pos=0 k
  for k in $REQUIRED_TOP; do
    local pat='"'$k'":'
    if [[ $pkt != *"$pat"* ]]; then
      printf 'PRE-00 FAIL: required key missing: %s\n' "$k" >&2
      fails=1
      continue
    fi
  done
  # no unknown top-level keys: walk depth-1 with the quote-aware scanner and
  # require the key sequence to be EXACTLY REQUIRED_TOP (order is frozen by
  # the builder; a reordered or extra key breaks every prefix-scan consumer).
  local -a top_keys=()
  n=${#pkt}; i=0; depth=0; in_str=0; esc=0
  local expect_key=0 keybuf="" in_key=0
  while (( i < n )); do
    local c=${pkt:i:1}
    if (( esc )); then esc=0; ((i+=1)); continue; fi
    if [[ $c == '\\' ]]; then esc=1; ((i+=1)); continue; fi
    if (( in_key )); then
      if [[ $c == '"' ]]; then top_keys+=("$keybuf"); in_key=0
      else keybuf+=$c; fi
      ((i+=1)); continue
    fi
    if (( in_str )); then
      [[ $c == '"' ]] && in_str=0
      ((i+=1)); continue
    fi
    case "$c" in
      '"')
        if (( depth == 1 && expect_key )); then in_key=1; keybuf=""
        else in_str=1; fi ;;
      '['|'{') ((depth+=1)); [[ $c == '{' ]] && expect_key=1 ;;
      ']'|'}') ((depth-=1)) ;;
      ',') if (( depth == 1 )); then expect_key=1; fi ;;
      ':') expect_key=0 ;;
    esac
    ((i+=1))
  done
  local expected=($REQUIRED_TOP) actual="${top_keys[*]}"
  if [[ "$actual" != "${expected[*]}" ]]; then
    printf 'PRE-00 FAIL: top-level key sequence mismatch (unknown/reordered key)\n  expected: %s\n  actual:   %s\n' "${expected[*]}" "$actual" >&2
    fails=1
  fi
  # seq contiguous + unique, carrier paths unique + inside root
  local rest=${pkt#*'"commands":['}
  local n=${#rest} i=0 depth=0 in_str=0 esc=0 obj_start=-1 obj count=0 expect=1
  while (( i < n )); do
    local c=${rest:i:1}
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
             obj=${rest:obj_start:i-obj_start+1}
             count=$((count+1))
             validate_command_object "$obj" || fails=1
           fi ;;
      ']') if (( depth == 0 )); then break; fi ;;
    esac
    ((i+=1))
  done
  if (( count == 0 )); then
    printf 'PRE-00 FAIL: commands[] empty or unterminated\n' >&2
    fails=1
  fi
  (( fails )) && return 1
  printf 'PRE-00 ok: %d commands, no access labels, canonical skeleton\n' "$count"
  return 0
}

validate_command_object() { # <canonical command object>
  local obj=$1
  local seq=${obj#*'"seq":"'}; seq=${seq%%\"*}
  if [[ ! $seq =~ ^[0-9]{3}$ ]]; then
    printf 'PRE-00 FAIL: seq "%s" not 3-digit decimal\n' "$seq" >&2
    return 1
  fi
  # contract §3.1-1: seq is 三位十进制、从 001 开始、连续、不得跳号. The
  # review's gap mutant (001,004,003 accepted) proved the uniqueness check
  # alone is not contiguity.
  local expected
  printf -v expected '%03d' "$EXPECTED_SEQ"
  if [[ $seq != "$expected" ]]; then
    printf 'PRE-00 FAIL: seq not contiguous: got %s, expected %s\n' "$seq" "$expected" >&2
    return 1
  fi
  EXPECTED_SEQ=$((EXPECTED_SEQ + 1))
  if seq_seen "$seq"; then
    printf 'PRE-00 FAIL: duplicate seq %s\n' "$seq" >&2
    return 1
  fi
  SEEN_SEQS+=("$seq")
  local carrier=${obj#*'"carrier":{'}
  carrier=${carrier%\}}
  local f v p
  for f in command stdout stderr exit startUtc endUtc; do
    v=${carrier#*'"'$f'":"'}; v=${v%%\"*}
    valid_rel_path "$v" || { printf 'PRE-00 FAIL: seq %s carrier path %s=%s invalid\n' "$seq" "$f" "$v" >&2; return 1; }
    if path_seen "$v"; then
      printf 'PRE-00 FAIL: carrier path reused: %s\n' "$v" >&2
      return 1
    fi
    SEEN_PATHS+=("$v")
  done
  # envelope fields
  local envp=${obj#*'"envPolicyId":"'}; envp=${envp%%\"*}
  [[ $envp == "$ENV_BASE" || $envp == "$ENV_GIT" ]] || { printf 'PRE-00 FAIL: seq %s envPolicyId %s\n' "$seq" "$envp" >&2; return 1; }
  local stdp=${obj#*'"stdinPolicyId":"'}; stdp=${stdp%%\"*}
  [[ $stdp == "$STDIN_CLOSED" ]] || { printf 'PRE-00 FAIL: seq %s stdinPolicyId %s\n' "$seq" "$stdp" >&2; return 1; }
  local sha=${obj#*'"executableSha256":"'}; sha=${sha%%\"*}
  [[ $sha =~ ^[0-9a-f]{64}$ ]] || { printf 'PRE-00 FAIL: seq %s executableSha256 not 64-hex\n' "$seq" >&2; return 1; }
  local argv=${obj#*'"argv":['}
  argv=${argv%%\]*}
  [[ $argv == \"* ]] || { printf 'PRE-00 FAIL: seq %s argv empty\n' "$seq" >&2; return 1; }
  return 0
}

# bash 3.2 (macOS dev host) has no associative arrays: linear membership
# lists are fine at packet scale (~100 commands, ~600 carrier paths).
SEEN_SEQS=()
SEEN_PATHS=()
EXPECTED_SEQ=1
seq_seen()   { [[ -n ${SEEN_SEQS[@]+x} ]] || return 1; local x; for x in "${SEEN_SEQS[@]}";   do [[ $x == "$1" ]] && return 0; done; return 1; }
path_seen()  { [[ -n ${SEEN_PATHS[@]+x} ]] || return 1; local x; for x in "${SEEN_PATHS[@]}";  do [[ $x == "$1" ]] && return 0; done; return 1; }

main() {
  if (( $# < 2 )); then usage; exit 2; fi
  case "$1" in
    manifest-freeze) (( $# >= 3 )) || { usage; exit 2; }; shift; cmd_manifest_freeze "$@" ;;
    build)           (( $# == 3 )) || { usage; exit 2; }; cmd_build "$2" "$3" ;;
    validate)        (( $# == 2 )) || { usage; exit 2; }; cmd_validate "$2" ;;
    *) usage; exit 2 ;;
  esac
}

usage() {
  # builtin-only (the PATH= review finding: no external cat even here)
  printf '%s\n' 'usage:
  row2-packet.sh manifest-freeze <evidence-dir> <executableId>...
  row2-packet.sh build <evidence-dir> <spec.bash>
  row2-packet.sh validate <evidence-dir>' >&2
}

main "$@"
