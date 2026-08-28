#!/usr/bin/env bash
#
# selftest-row2-exec.sh — mutation matrix for scripts/check-row2-exec.sh and
# the frozen payloads under scripts/row2/.
#
# check-row2-exec.sh is green on this branch. That fact carries information
# only while the guards are still SENSITIVE to the drift they claim to catch.
# Each case below mutates a THROWAWAY COPY, runs the PRODUCTION components
# against it, and asserts the SPECIFIC finding — never merely "it went red".
# M-* cases then disable one guard in the copy and require the finding to
# DISAPPEAR, which distinguishes a load-bearing guard from one a broader
# check happens to cover.
#
# The three dispatch-named mutations are first-class cases:
#   R1  escape command allowed (awk 'BEGIN{system(...)}' accepted as HOST-NONE)
#   R2  a carrier quadruple segment missing yet the gate reports success
#   R3  packet and carrier do not match yet execution/audit continues
# Plus:
#   R4  write-budget off-by-one (13 writes accepted)
#   R5  grammar narrowing (a rule's positive fixture deleted)
#   M4  access-label blindness
#
# Device-free by construction: the selftest only ever classifies, hashes and
# executes sort/cat/grep-class host binaries in throwaway evidence roots.
#
# Exit 0 = all cases behave as specified.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/.." && pwd)"
ROW2="$REPO_ROOT/scripts/row2"
GATE="$HERE/check-row2-exec.sh"
PACKET="$ROW2/row2-packet.sh"
RUNNER="$ROW2/row2-runner.sh"
CLASSIFIER="$ROW2/row2-classifier-v2.sh"
FIXTURES="$ROW2/row2-classifier-v2-fixtures.json"

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

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# copy_payload_tree <dst> — the whole plane, so mutated copies still resolve
# their sourced library and fixtures file
copy_payload_tree() {
  mkdir -p "$1"
  cp "$ROW2"/*.sh "$ROW2"/*.json "$1/"
}

# copy_repo_layout <dst> — mirrors scripts/row2 nesting so SUPERVISOR-face
# copies resolve REPO_ROOT the way the real checkout does (leaf-face copies
# don't need this; supervise does)
copy_repo_layout() {
  mkdir -p "$1/scripts/row2"
  cp "$ROW2"/*.sh "$ROW2"/*.json "$1/scripts/row2/"
}

# Extract the gate's canonical spec (single source; the sed is the same
# "extract the REAL shipped pieces" pattern selftest-test-hook-package-identity.sh uses).
extract_gate_spec() { # <out-path>
  sed -n "/^write_gate_spec() { # /,/^SPEC$/p" "$GATE" | sed '1,2d;$d' | sed 's/^    //' > "$1"
  [ -s "$1" ]
}

# build_green_session <evidence-dir> — manifest-freeze + build + validate +
# supervise, exactly the gate's section-6 green path
build_green_session() {
  local ev=$1
  mkdir -p "$ev/meta" "$ev/derived"
  "$PACKET" manifest-freeze "$ev" bash cat sort grep shasum row2-runner row2-classifier >/dev/null 2>&1 \
    || return 1
  extract_gate_spec "$WORK/gate-spec.bash" || return 1
  "$PACKET" build "$ev" "$WORK/gate-spec.bash" >/dev/null 2>&1 || return 1
  "$PACKET" validate "$ev" >/dev/null 2>&1 || return 1
  (cd "$ev" && bash "$RUNNER" supervise .) >/dev/null 2>&1 || return 1
  return 0
}

# ---------------------------------------------------------------------------
# R1: escape command allowed — a loosened classifier must be caught by the
# frozen corpus with the SPECIFIC escape fixture failing.
# ---------------------------------------------------------------------------
EV="$WORK/r1-ev"
if build_green_session "$EV"; then
  report ok "R1 setup green session"
else
  report fail "R1 setup green session" "green session failed to build"
fi

T1="$WORK/r1-tree"
copy_payload_tree "$T1"
# loosen: add awk as a HOST-NONE rule (the exact 'looks read-only but spawns
# children' shape the contract's HOST-TEXT closing was about)
python3 - "$T1/row2-classifier-v2.sh" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p).read()
s = s.replace("RULE_IDS=(\n  HOST_ADB_VERSION", "RULE_IDS=(\n  HOST_AWK\n  HOST_ADB_VERSION")
s = s.replace("r_HOST_ADB_VERSION() {", "r_HOST_AWK() { [[ ${E_ARGV[0]} == awk ]]; }\n\nr_HOST_ADB_VERSION() {")
# the envelope pre-gates reject awk at the manifest before any rule fires, so
# a real loosening must add the manifest entry too (both halves of the attack);
# the digest must be the fixture envelope's own awk digest
awk_sha = "ab" * 32
entry = ('{"executableId":"awk","argv0Token":"awk","kind":"system",'
         '"locationId":"SYS_BIN_AWK","sha256":"%s"}\n' % awk_sha)
s = s.replace('{"executableId":"fastboot"', entry + '{"executableId":"fastboot"')
open(p, "w").write(s)
PYEOF
# fixture mode: rc=0 + verdict PASS = classifier agreed with the frozen
# expectation. A loosened classifier must DISAGREE on the escape negative:
# verdict FAIL and actualClass=HOST-NONE (the escape got through).
r1_out=$(cd "$WORK" && bash "$T1/row2-classifier-v2.sh" fixture NEG-ESC-AWK-SYSTEM r1.json; echo "rc=$?")
r1_body=$(cat "$WORK/r1.json" 2>/dev/null || echo missing)
if [[ $r1_body == *'"actualClass":"HOST-NONE"'* && $r1_body == *'"verdict":"FAIL"'* ]]; then
  report ok "R1 loosened classifier: escape fixture FAILS the corpus"
else
  report fail "R1 loosened classifier: escape fixture FAILS the corpus" "got rc/out=$r1_out body=$r1_body"
fi
# production classifier must still AGREE with the frozen expectation (reject)
r1b=$(cd "$WORK" && bash "$CLASSIFIER" fixture NEG-ESC-AWK-SYSTEM r1b.json; echo "rc=$?")
r1b_body=$(cat "$WORK/r1b.json" 2>/dev/null || echo missing)
if [[ $r1b_body == *'"verdict":"PASS"'* && $r1b == "rc=0" ]]; then
  report ok "R1 production classifier rejects escape (corpus agrees)"
else
  report fail "R1 production classifier rejects escape" "out=$r1b body=$r1b_body"
fi

# M1 (load-bearing): the corpus file itself is the oracle — corrupt the
# EXPECTED side instead and the production classifier must disagree.
T1b="$WORK/r1b-tree"
copy_payload_tree "$T1b"
sed 's/^NEG-ESC-AWK-SYSTEM|.*|NEG-ESC-AWK-SYSTEM||HOST-NONE|/' "$FIXTURES" > "$T1b/row2-classifier-v2-fixtures.json" 2>/dev/null || true
python3 - "$T1b/row2-classifier-v2-fixtures.json" <<'PYEOF'
import sys
p = sys.argv[1]
lines = open(p).read().split("\n")
out = []
for l in lines:
    if l.startswith("NEG-ESC-AWK-SYSTEM|"):
        parts = l.split("|")
        parts[1] = "HOST-AWK"
        parts[2] = "HOST-NONE"
        l = "|".join(parts)
    out.append(l)
open(p, "w").write("\n".join(out))
PYEOF
if (cd "$WORK" && bash "$T1b/row2-classifier-v2.sh" fixture NEG-ESC-AWK-SYSTEM r1c.json) >/dev/null 2>&1; then
  report fail "M1 corpus oracle load-bearing" "classifier agreed with a corrupted expectation (escape would pass silently)"
else
  report ok "M1 corpus oracle load-bearing (corrupted expectation disagrees)"
fi

# ---------------------------------------------------------------------------
# R2: a carrier quadruple segment missing yet reported success.
# ---------------------------------------------------------------------------
EV2="$WORK/r2-ev"
if build_green_session "$EV2"; then
  rm "$EV2/meta/002-host-cat.exit.txt"
  printf 'meta/001-fileset-sort\nmeta/002-host-cat\nmeta/003-text-grep\n' > "$EV2/meta/stems.txt"
  out2=$(cd "$EV2" && bash "$RUNNER" gate six-file-carrier meta/stems.txt meta/g2.json 2>&1; echo "rc=$?")
  if [[ $out2 == "rc=1" && $(cat "$EV2/meta/g2.json") == *'"stem":"meta/002-host-cat","verdict":"FAIL","why":"exit-missing"'* ]]; then
    report ok "R2 missing exit.txt → gate FAIL with exit-missing"
  else
    report fail "R2 missing exit.txt → gate FAIL with exit-missing" "got: $out2 / $(cat "$EV2/meta/g2.json" 2>/dev/null | tail -1)"
  fi
else
  report fail "R2 setup" "green session failed"
fi

# M2 (load-bearing): disable the exit-missing arm in a runner copy — the
# broken session must then pass, proving that arm is what catches it.
T2="$WORK/r2-tree"
copy_payload_tree "$T2"
python3 - "$T2/row2-runner.sh" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p).read()
s = s.replace('    [[ -s "$stem.exit.txt" ]] || why="${why:+$why,}exit-missing"', '    :')
open(p, "w").write(s)
PYEOF
EV2b="$WORK/r2b-ev"
if build_green_session "$EV2b"; then
  rm "$EV2b/meta/002-host-cat.exit.txt"
  printf 'meta/001-fileset-sort\nmeta/002-host-cat\nmeta/003-text-grep\n' > "$EV2b/meta/stems.txt"
  if (cd "$EV2b" && bash "$T2/row2-runner.sh" gate six-file-carrier meta/stems.txt meta/g2b.json) >/dev/null 2>&1; then
    report ok "M2 exit-missing arm load-bearing (disabled → broken session passes)"
  else
    report fail "M2 exit-missing arm load-bearing" "disabled guard still failed the session (arm not load-bearing or patch missed)"
  fi
else
  report fail "M2 setup" "green session failed"
fi

# ---------------------------------------------------------------------------
# R3: packet and carrier do not match yet audit continues.
# ---------------------------------------------------------------------------
EV3="$WORK/r3-ev"
if build_green_session "$EV3"; then
  cat "$EV3"/meta/.cls-00*.json > "$EV3/meta/cls-all.txt"
  # tamper the FROZEN carrier after the run: packet says cat, disk says catx
  sed 's/"cat"/"catx"/' "$EV3/meta/002-host-cat.command.txt" > "$WORK/t" && mv "$WORK/t" "$EV3/meta/002-host-cat.command.txt"
  out3=$(cd "$EV3" && bash "$RUNNER" audit command-surface meta/cls-all.txt meta/a3.json 2>&1; echo "rc=$?")
  if [[ $out3 == "rc=1" && $(cat "$EV3/meta/a3.json") == *'"packetCarrierMismatch":1'* ]]; then
    report ok "R3 tampered carrier → audit FAIL with packetCarrierMismatch=1"
  else
    report fail "R3 tampered carrier → audit FAIL" "got: $out3 / $(cat "$EV3/meta/a3.json" 2>/dev/null)"
  fi
else
  report fail "R3 setup" "green session failed"
fi

# M3 (load-bearing): drop the carrier-bytes comparison in a runner copy —
# the tampered session must then pass (the classification-vs-packet check
# alone cannot see post-run tampering).
T3="$WORK/r3-tree"
copy_payload_tree "$T3"
python3 - "$T3/row2-runner.sh" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p).read()
old = '''    if [[ -r "$pcarrier" ]]; then
      local cline=""
      IFS= read -r cline < "$pcarrier" || true
      local csha2
      csha2=$(sha256_hex "$cline")
      [[ $csha2 == "$psha" ]] || carrier_mismatch=$((carrier_mismatch+1))
    else
      carrier_mismatch=$((carrier_mismatch+1))
    fi'''
assert old in s
s = s.replace(old, "    :")
open(p, "w").write(s)
PYEOF
EV3b="$WORK/r3b-ev"
if build_green_session "$EV3b"; then
  cat "$EV3b"/meta/.cls-00*.json > "$EV3b/meta/cls-all.txt"
  sed 's/"cat"/"catx"/' "$EV3b/meta/002-host-cat.command.txt" > "$WORK/t" && mv "$WORK/t" "$EV3b/meta/002-host-cat.command.txt"
  if (cd "$EV3b" && bash "$T3/row2-runner.sh" audit command-surface meta/cls-all.txt meta/a3b.json) >/dev/null 2>&1; then
    report ok "M3 carrier-bytes arm load-bearing (disabled → tamper passes)"
  else
    report fail "M3 carrier-bytes arm load-bearing" "disabled guard still caught the tamper (arm not load-bearing or patch missed)"
  fi
else
  report fail "M3 setup" "green session failed"
fi

# ---------------------------------------------------------------------------
# R4: write-budget off-by-one.
# ---------------------------------------------------------------------------
T4="$WORK/r4"
mkdir -p "$T4"
printf '{"seq":"001","ruleId":"ADB-WRITE-PREPARE","derivedClass":"DEVICE-WRITE"}\n' > "$T4/w13.txt"
i=2
while [ $i -le 13 ]; do printf '{"seq":"%03d","ruleId":"ADB-WRITE-QDUMP","derivedClass":"DEVICE-WRITE"}\n' "$i" >> "$T4/w13.txt"; i=$((i + 1)); done
if (cd "$T4" && bash "$RUNNER" gate prefire-write-boundary w13.txt o.json) >/dev/null 2>&1; then
  report fail "R4 13 writes rejected" "production runner accepted 13 writes"
else
  grep -q 'writes-exceed-12' "$T4/o.json" \
    && report ok "R4 13 writes → STOP with writes-exceed-12" \
    || report fail "R4 13 writes → STOP with writes-exceed-12" "wrong finding: $(cat "$T4/o.json")"
fi
# M4 (load-bearing): shift the budget in a copy; the 13-write input passes.
T4b="$WORK/r4-tree"
copy_payload_tree "$T4b"
sed 's/normal_writes > 12/normal_writes > 13/' "$RUNNER" > "$T4b/row2-runner.sh"
if (cd "$T4" && bash "$T4b/row2-runner.sh" gate prefire-write-boundary w13.txt o.json) >/dev/null 2>&1; then
  report ok "M4 budget arm load-bearing (off-by-one → 13 writes pass)"
else
  report fail "M4 budget arm load-bearing" "patched budget still rejected 13"
fi

# ---------------------------------------------------------------------------
# R5: grammar narrowing — deleting a rule's positive fixture must be caught
# by the coverage check (replicated here against a mutated fixtures copy).
# ---------------------------------------------------------------------------
T5="$WORK/r5-tree"
copy_payload_tree "$T5"
grep -v '^POS-HOST-SQLITE-[123]|' "$FIXTURES" > "$T5/row2-classifier-v2-fixtures.json"
# same probe the gate's section 4 runs: a rule is covered only by a fixture
# expecting its ruleId AND a non-reject class
covered=$(grep -E '\|HOST-SQLITE\||[^|]+$' "$T5/row2-classifier-v2-fixtures.json" | grep -v 'CLASSIFIER-REJECT|' | grep -c '|HOST-SQLITE|' || true)
if [ "${covered:-0}" -eq 0 ]; then
  report ok "R5 all HOST-SQLITE positives deleted → coverage probe reports it uncovered"
else
  report fail "R5 deleted positives detected" "still $covered covering fixtures"
fi

# ---------------------------------------------------------------------------
# M5: access-label blindness — validator copy without the label scan.
# ---------------------------------------------------------------------------
T5b="$WORK/m5-tree"
copy_payload_tree "$T5b"
python3 - "$T5b/row2-packet.sh" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p).read()
old = '''  for key in deviceAccess declaredAccess accessClass deviceAccessClass declaredClass; do
    if [[ $pkt == *'"'$key'":'* ]]; then
      printf 'PRE-00 FAIL: access-label field "%s" present — packet cannot self-report access (§3.1-4)\\n' "$key" >&2
      fails=1
    fi
  done'''
assert old in s
s = s.replace(old, "  :")
open(p, "w").write(s)
PYEOF
EV5="$WORK/m5-ev"
if build_green_session "$EV5"; then
  # plant the label INSIDE a command object: the top-level key-sequence
  # walker cannot see it — only the whole-text access-label scan can (the
  # sequence check catching the top-level variant is defense-in-depth, but
  # this case isolates the label scan's own arm)
  sed 's/"stdinPolicyId":"ROW2-STDIN-CLOSED-V1","argv"/"stdinPolicyId":"ROW2-STDIN-CLOSED-V1","deviceAccess":"HOST-NONE","argv"/' "$EV5/meta/execution-packet.json" > "$WORK/mut.json"
  grep -q '"deviceAccess":"HOST-NONE"' "$WORK/mut.json" \
    || { report fail "M5 setup" "label not planted"; }
  mkdir -p "$WORK/m5-mut/meta"
  cp "$WORK/mut.json" "$WORK/m5-mut/meta/execution-packet.json"
  if "$T5b/row2-packet.sh" validate "$WORK/m5-mut" >/dev/null 2>&1; then
    report ok "M5 access-label arm load-bearing (disabled → labeled packet passes)"
  else
    report fail "M5 access-label arm load-bearing" "disabled scan still rejected (arm not load-bearing or patch missed)"
  fi
  if "$PACKET" validate "$WORK/m5-mut" >/dev/null 2>&1; then
    report fail "R6 production validator rejects access labels" "labeled packet ACCEPTED"
  else
    report ok "R6 production validator rejects access labels"
  fi
else
  report fail "M5 setup" "green session failed"
fi

# ---------------------------------------------------------------------------
# M6/R7: seq-contiguity arm (gpt55 review F3: 001,004,003 was accepted).
# ---------------------------------------------------------------------------
T6="$WORK/m6-tree"
copy_payload_tree "$T6"
# disable ONLY the comparison (arm-off); the counter still increments
sed 's/if \[\[ $seq != "$expected" \]\]; then/if false; then/' "$PACKET" > "$T6/row2-packet.sh"

EV6="$WORK/m6-ev"
if build_green_session "$EV6"; then
  # gap without collision: the 5-command spec has a real seq 004 (the F1
  # repo-payload unit), so 002→004 would be a DUPLICATE finding instead of a
  # contiguity finding — shift 003→013 instead
  sed 's/"seq":"003"/"seq":"013"/' "$EV6/meta/execution-packet.json" > "$WORK/mut6.json"
  mkdir -p "$WORK/m6-mut/meta"
  cp "$WORK/mut6.json" "$WORK/m6-mut/meta/execution-packet.json"
  if "$PACKET" validate "$WORK/m6-mut" >/dev/null 2>&1; then
    report fail "R7 seq-gap rejected by production validator" "gap mutant ACCEPTED"
  else
    report ok "R7 seq-gap rejected by production validator"
  fi
  if "$T6/row2-packet.sh" validate "$WORK/m6-mut" >/dev/null 2>&1; then
    report ok "M6 seq-contiguity arm load-bearing (disabled → gap passes)"
  else
    report fail "M6 seq-contiguity arm load-bearing" "disabled arm still rejected the gap"
  fi
else
  report fail "M6 setup" "green session failed"
fi

# ---------------------------------------------------------------------------
# M8: interpreter-exec arm (gpt55 review F1: repo-payload units exit 126/2).
# With the repo-payload branch removed, unit 004 must degrade to exit!=0.
# ---------------------------------------------------------------------------
T7="$WORK/m7-tree"
copy_repo_layout "$T7"
# route repo-payload units down the DIRECT-exec path (the F1 failure shape)
sed 's/if \[\[ $kind == repo-payload \]\]; then/if false; then/' "$T7/scripts/row2/row2-runner.sh" > "$T7/rr.tmp" && mv "$T7/rr.tmp" "$T7/scripts/row2/row2-runner.sh"

EV7="$WORK/m7-ev"
mkdir -p "$EV7/meta" "$EV7/derived"
# the whole session is self-hosted on the PATCHED tree (manifest digests must
# bind the patched runner, else verify_executable stops before unit 004)
"$T7/scripts/row2/row2-packet.sh" manifest-freeze "$EV7" bash cat sort grep shasum row2-runner row2-classifier >/dev/null 2>&1
extract_gate_spec "$WORK/m7-spec.bash"
"$T7/scripts/row2/row2-packet.sh" build "$EV7" "$WORK/m7-spec.bash" >/dev/null 2>&1
(cd "$EV7" && bash "$T7/scripts/row2/row2-runner.sh" supervise .) >/dev/null 2>&1
e7=$(cat "$EV7/meta/004-runner-parse.exit.txt" 2>/dev/null || echo missing)
if [ "$e7" != "0" ] && [ "$e7" != "missing" ]; then
  report ok "M8 interpreter arm load-bearing (removed → unit 004 exit=$e7)"
else
  report fail "M8 interpreter arm load-bearing" "unit 004 exit=$e7 without the interpreter branch"
fi

# ---------------------------------------------------------------------------
# M9/R8: frozen timing literals (gpt55 R1 re-review F4: 10→999 passed —
# presence was checked, value was not; contract §3.1 pins all three).
# ---------------------------------------------------------------------------
T9="$WORK/m9-tree"
copy_payload_tree "$T9"
# disable ONLY the timing-literal arm (the check is a || guard — make its
# test vacuously true so the failure arm can never fire)
sed 's/\[\[ $pkt == \*"$timing_pat"\* \]\]/true/' "$PACKET" > "$T9/row2-packet.sh"
EV9="$WORK/m9-ev"
if build_green_session "$EV9"; then
  sed 's/"terminalReadMaxDelaySeconds":10/"terminalReadMaxDelaySeconds":999/' "$EV9/meta/execution-packet.json" > "$WORK/mut9.json"
  mkdir -p "$WORK/m9-mut/meta"
  cp "$WORK/mut9.json" "$WORK/m9-mut/meta/execution-packet.json"
  if "$PACKET" validate "$WORK/m9-mut" >/dev/null 2>&1; then
    report fail "R8 timing-value mutant rejected" "999 ACCEPTED (contract timing not enforced)"
  else
    report ok "R8 timing-value mutant rejected with frozen-timing finding"
  fi
  if "$T9/row2-packet.sh" validate "$WORK/m9-mut" >/dev/null 2>&1; then
    report ok "M9 timing-literal arm load-bearing (disabled → 999 passes)"
  else
    report fail "M9 timing-literal arm load-bearing" "disabled arm still rejected 999"
  fi
else
  report fail "M9 setup" "green session failed"
fi

# ---------------------------------------------------------------------------
# finally: the PRODUCTION gate must be green right now (selftest does not
# leave the tree dirty and the guards hold on the real payloads)
# ---------------------------------------------------------------------------
if "$GATE" >/dev/null 2>&1; then
  report ok "production gate green after mutation matrix"
else
  report fail "production gate green after mutation matrix" "gate went red — selftest leaked state or a guard regressed"
fi

echo
echo "selftest-row2-exec: pass=$pass fail=$fail"
[ "$fail" -eq 0 ]
