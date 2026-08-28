#!/usr/bin/env bash
#
# row2-envelope.sh — shared frozen library for the G2 Row 2 execution plane.
#
# Contract: docs/acceptance/g2-p10-row2-evidence-contract.md (PR #55, blob
# c072c83fa979cf9d222a544faf8366e6fa691d21) §3.1/§3.1.1/§3.2.
#
# SOURCED by row2-classifier-v2.sh, row2-runner.sh and row2-packet.sh.
# It is never executed directly and adds no subcommands.
#
# HARD CONSTRAINT (contract HOST-CLASSIFIER / HOST-RUNNER-MODE rows):
# every payload in this plane is a "只用 shell builtins，不得调用
# launcher／外部进程／eval" helper. This library therefore uses ONLY bash
# builtins — no external processes, no eval, no subshell spawns. File I/O
# uses read/redirection builtins. Everything here runs under LC_ALL=C (the
# payloads force it): bash string indexing is then byte-exact, which the
# byte-level SHA-256 below depends on.
#
# Contents:
#   1. pure-bash SHA-256 (FIPS 180-4) — the contract requires the classifier
#      to "逐字输出" the HOST-TEXT pattern's SHA-256 (§3.1.1) and PRE-04-style
#      audits to emit envelope/argv digests, all under the builtins-only
#      constraint. NUL-free inputs only: every consumer hashes canonical JSON
#      text or printable-ASCII patterns, which are NUL-free by construction.
#   2. canonical JSON string escape/unescape (escape minimal set + \u00xx
#      for controls; unescape accepts ONLY what the emitter produces plus
#      \uXXXX for printable ASCII — byte-exactness over generosity).
#   3. canonical execution-envelope parse/emit with round-trip canonicality
#      check (contract §3.2-1: command.txt is "canonical UTF-8 JSON object
#      加一个换行" — a parse that accepted non-canonical bytes would let a
#      second serialization of the same envelope exist).
#   4. logical-root relative path grammar (§3.1.1: `[A-Za-z0-9]
#      [A-Za-z0-9._/-]*`, segments non-empty, not `.`/`..`, no leading `-`,
#      no backslash/control/percent-encoded separator, resolved path stays
#      inside the logical root).

# ---------------------------------------------------------------------------
# 0. locale
# ---------------------------------------------------------------------------
# Force C locale: byte-exact string indexing for the SHA-256 byte loop, and
# consistent with ROW2-CLEAN-ENV-V1 (LC_ALL=C is one of its three keys).
LC_ALL=C
LANG=C
TZ=UTC
export LC_ALL LANG TZ

# ---------------------------------------------------------------------------
# 1. pure-bash SHA-256
# ---------------------------------------------------------------------------
__SHA256_K=(
  0x428a2f98 0x71374491 0xb5c0fbcf 0xe9b5dba5 0x3956c25b 0x59f111f1 0x923f82a4 0xab1c5ed5
  0xd807aa98 0x12835b01 0x243185be 0x550c7dc3 0x72be5d74 0x80deb1fe 0x9bdc06a7 0xc19bf174
  0xe49b69c1 0xefbe4786 0x0fc19dc6 0x240ca1cc 0x2de92c6f 0x4a7484aa 0x5cb0a9dc 0x76f988da
  0x983e5152 0xa831c66d 0xb00327c8 0xbf597fc7 0xc6e00bf3 0xd5a79147 0x06ca6351 0x14292967
  0x27b70a85 0x2e1b2138 0x4d2c6dfc 0x53380d13 0x650a7354 0x766a0abb 0x81c2c92e 0x92722c85
  0xa2bfe8a1 0xa81a664b 0xc24b8b70 0xc76c51a3 0xd192e819 0xd6990624 0xf40e3585 0x106aa070
  0x19a4c116 0x1e376c08 0x2748774c 0x34b0bcb5 0x391c0cb3 0x4ed8aa4a 0x5b9cca4f 0x682e6ff3
  0x748f82ee 0x78a5636f 0x84c87814 0x8cc70208 0x90befffa 0xa4506ceb 0xbef9a3f7 0xc67178f2
)

__hexdigit() { # value 0..15 -> one hex char on stdout
  case "$1" in
    0|1|2|3|4|5|6|7|8|9) printf '%s' "$1" ;;
    10) printf 'a' ;; 11) printf 'b' ;; 12) printf 'c' ;;
    13) printf 'd' ;; 14) printf 'e' ;; 15) printf 'f' ;;
  esac
}

__u32_hex() { # $1 u32 -> 8 lowercase hex chars on stdout
  local v=$1 i shift nib
  for i in 7 6 5 4 3 2 1 0; do
    shift=$(( i * 4 ))
    nib=$(( (v >> shift) & 15 ))
    __hexdigit "$nib"
  done
}

# sha256_hex <string> — prints the 64-char lowercase hex digest of the
# NUL-free string's bytes. Shell arrays make this allocation-heavy; payloads
# only hash envelopes/patterns (≤ a few KB), never build artifacts.
sha256_hex() {
  local msg=$1
  local -a bytes=()
  local i n=${#msg} b
  for ((i = 0; i < n; i++)); do
    printf -v b '%d' "'${msg:i:1}"
    (( b &= 255 ))   # bash 3.2 sign-extends bytes >= 0x80 via 'x
    bytes+=("$b")
  done
  local bitlen=$(( n * 8 ))
  # padding: 0x80, zeros to 56 mod 64, 64-bit big-endian bit length
  bytes+=(128)
  while (( ${#bytes[@]} % 64 != 56 )); do bytes+=(0); done
  bytes+=( $(( (bitlen >> 56) & 255 )) $(( (bitlen >> 48) & 255 )) \
           $(( (bitlen >> 40) & 255 )) $(( (bitlen >> 32) & 255 )) \
           $(( (bitlen >> 24) & 255 )) $(( (bitlen >> 16) & 255 )) \
           $(( (bitlen >> 8) & 255 ))  $(( bitlen & 255 )) )

  local -a W=()
  local -i h0=0x6a09e667 h1=0xbb67ae85 h2=0x3c6ef372 h3=0xa54ff53a
  local -i h4=0x510e527f h5=0x9b05688c h6=0x1f83d9ab h7=0x5be0cd19
  local -i a b2 c d e f g h t s0 s1 T1 T2 maj ch
  local blocks=$(( ${#bytes[@]} / 64 )) blk r
  for ((blk = 0; blk < blocks; blk++)); do
    for ((r = 0; r < 16; r++)); do
      W[r]=$(( (bytes[blk*64 + r*4] << 24) | (bytes[blk*64 + r*4 + 1] << 16) |
               (bytes[blk*64 + r*4 + 2] << 8) | bytes[blk*64 + r*4 + 3] ))
    done
    for ((r = 16; r < 64; r++)); do
      s0=$(( ((W[r-15] >> 7) | (W[r-15] << 25)) ^ ((W[r-15] >> 18) | (W[r-15] << 14)) ^ (W[r-15] >> 3) ))
      s0=$(( s0 & 0xFFFFFFFF ))
      s1=$(( ((W[r-2] >> 17) | (W[r-2] << 15)) ^ ((W[r-2] >> 19) | (W[r-2] << 13)) ^ (W[r-2] >> 10) ))
      s1=$(( s1 & 0xFFFFFFFF ))
      W[r]=$(( (W[r-16] + s0 + W[r-7] + s1) & 0xFFFFFFFF ))
    done
    a=$h0; b2=$h1; c=$h2; d=$h3; e=$h4; f=$h5; g=$h6; h=$h7
    for ((r = 0; r < 64; r++)); do
      s1=$(( ((e >> 6) | (e << 26)) ^ ((e >> 11) | (e << 21)) ^ ((e >> 25) | (e << 7)) ))
      s1=$(( s1 & 0xFFFFFFFF ))
      ch=$(( (e & f) ^ ((~e & 0xFFFFFFFF) & g) ))
      T1=$(( (h + s1 + ch + __SHA256_K[r] + W[r]) & 0xFFFFFFFF ))
      s0=$(( ((a >> 2) | (a << 30)) ^ ((a >> 13) | (a << 19)) ^ ((a >> 22) | (a << 10)) ))
      s0=$(( s0 & 0xFFFFFFFF ))
      maj=$(( (a & b2) ^ (a & c) ^ (b2 & c) ))
      T2=$(( (s0 + maj) & 0xFFFFFFFF ))
      h=$g; g=$f; f=$e
      e=$(( (d + T1) & 0xFFFFFFFF ))
      d=$c; c=$b2; b2=$a
      a=$(( (T1 + T2) & 0xFFFFFFFF ))
    done
    h0=$(( (h0 + a) & 0xFFFFFFFF )); h1=$(( (h1 + b2) & 0xFFFFFFFF ))
    h2=$(( (h2 + c) & 0xFFFFFFFF )); h3=$(( (h3 + d) & 0xFFFFFFFF ))
    h4=$(( (h4 + e) & 0xFFFFFFFF )); h5=$(( (h5 + f) & 0xFFFFFFFF ))
    h6=$(( (h6 + g) & 0xFFFFFFFF )); h7=$(( (h7 + h) & 0xFFFFFFFF ))
  done
  __u32_hex "$h0"; __u32_hex "$h1"; __u32_hex "$h2"; __u32_hex "$h3"
  __u32_hex "$h4"; __u32_hex "$h5"; __u32_hex "$h6"; __u32_hex "$h7"
}

# ---------------------------------------------------------------------------
# 2. canonical JSON string escape/unescape
# ---------------------------------------------------------------------------
# json_escape <raw> — prints the escaped string CONTENT (no surrounding
# quotes). Escapes `"` `\` and the two-char named controls; other C0 controls
# as \u00xx. Everything else (printable ASCII, high bytes) passes through.
json_escape() {
  local raw=$1 n=${#1} i c out=""
  for ((i = 0; i < n; i++)); do
    c=${raw:i:1}
    if [[ $c == '"' ]]; then out+='\"'
    elif [[ $c == \\ ]]; then out+='\\'
    elif [[ $c == $'\n' ]]; then out+='\n'
    elif [[ $c == $'\r' ]]; then out+='\r'
    elif [[ $c == $'\t' ]]; then out+='\t'
    elif [[ $c == $'\b' ]]; then out+='\b'
    elif [[ $c == $'\f' ]]; then out+='\f'
    else
      printf -v b '%d' "'$c"
      (( b &= 255 ))   # bash 3.2 sign-extends bytes >= 0x80 via 'x
      if (( b < 0x20 )); then
        printf -v b '%04x' "$b"
        out+="\\u$b"
      else
        out+=$c
      fi
    fi
  done
  printf '%s' "$out"
}

# json_unescape <content> — reverse of json_escape, into REPLY. Accepts ONLY
# the escape forms the emitter can produce: \\" \\\\ \\n \\r \\t \\b \\f and
# \\uXXXX that decodes to printable ASCII (0x20..0x7e). Anything else (bad
# escape, lone surrogate, non-ASCII \\u) returns 1: the parser stays
# byte-exact instead of generously re-encoding (a re-encoded string would be
# a second serialization of the same envelope, which canonicality forbids).
json_unescape() {
  local in=$1 out="" i=0 n=${#1} c hex v
  REPLY=""
  while (( i < n )); do
    c=${in:i:1}
    if [[ $c == \\ ]]; then
      c=${in:i+1:1}
      if   [[ $c == '"' ]];  then out+='"'  ; ((i+=2))
      elif [[ $c == \\ ]];  then out+='\'  ; ((i+=2))
      elif [[ $c == 'n' ]];  then out+=$'\n' ; ((i+=2))
      elif [[ $c == 'r' ]];  then out+=$'\r' ; ((i+=2))
      elif [[ $c == 't' ]];  then out+=$'\t' ; ((i+=2))
      elif [[ $c == 'b' ]];  then out+=$'\b' ; ((i+=2))
      elif [[ $c == 'f' ]];  then out+=$'\f' ; ((i+=2))
      elif [[ $c == 'u' ]]; then
        hex=${in:i+2:4}
        [[ $hex =~ ^[0-9a-fA-F]{4}$ ]] || return 1
        printf -v v '%d' "0x$hex"
        # accept \u00xx for the full C0 range (the exact set the emitter
        # produces for controls) plus printable ASCII; anything else is not a
        # form this emitter can emit, so it is not byte-exact to decode it.
        if (( v > 0x7e )); then return 1; fi
        printf -v c '%b' "\\$(printf '%03o' "$v")"
        out+=$c
        ((i+=6))
      else
        return 1
      fi
    else
      out+=$c
      ((i+=1))
    fi
  done
  REPLY=$out
  return 0
}

# ---------------------------------------------------------------------------
# 3. canonical execution envelope (contract §3.2-1)
# ---------------------------------------------------------------------------
# Layout, in this exact key order, no spaces, single line:
#   {"executableId":S,"executableSha256":S,"cwdRef":S,"envPolicyId":S,
#    "stdinPolicyId":S,"argv":[S,...]}
# envelope_emit <execId> <sha> <cwd> <env> <stdin> <argv...>
envelope_emit() {
  local execid=$1 sha=$2 cwd=$3 envp=$4 stdinp=$5; shift 5
  local out
  printf -v out '{"executableId":"%s","executableSha256":"%s","cwdRef":"%s","envPolicyId":"%s","stdinPolicyId":"%s","argv":[' \
    "$(json_escape "$execid")" "$(json_escape "$sha")" "$(json_escape "$cwd")" \
    "$(json_escape "$envp")" "$(json_escape "$stdinp")"
  local first=1 a
  for a in "$@"; do
    if (( first )); then first=0; else out+=','; fi
    out+="\"$(json_escape "$a")\""
  done
  out+=']}'
  printf '%s' "$out"
}

# envelope_parse <line-without-trailing-newline>
# -> E_EXECID E_SHA E_CWD E_ENVP E_STDP E_ARGV[] ; rc 0 = canonical.
# Strict cursor walk over the exact key order, then callers re-emit and
# byte-compare for full canonicality (envelope_parse_canonical does both).
envelope_parse() {
  local s=$1
  E_ARGV=()
  local key
  [[ $s == '{'* && $s == *'}' ]] || return 1
  s=${s:1:${#s}-2}
  for key in executableId executableSha256 cwdRef envPolicyId stdinPolicyId; do
    [[ $s == \"$key\":* ]] || return 1
    s=${s:$((${#key} + 3))}
    if ! __take_json_string "$s"; then return 1; fi
    json_unescape "$__STR_CONTENT" || return 1
    case "$key" in
      executableId)     E_EXECID=$REPLY ;;
      executableSha256) E_SHA=$REPLY ;;
      cwdRef)           E_CWD=$REPLY ;;
      envPolicyId)      E_ENVP=$REPLY ;;
      stdinPolicyId)    E_STDP=$REPLY ;;
    esac
    s=$__STR_REST
    [[ $s == ,* ]] || return 1
    s=${s:1}
  done
  [[ $s == '"argv":['* ]] || return 1
  s=${s:8}
  if [[ $s == ']' ]]; then return 1; fi   # argv must be non-empty
  while :; do
    if ! __take_json_string "$s"; then return 1; fi
    json_unescape "$__STR_CONTENT" || return 1
    E_ARGV+=("$REPLY")
    s=$__STR_REST
    if [[ $s == ,* ]]; then s=${s:1}; continue; fi
    [[ $s == ']' ]] || return 1
    return 0
  done
}

# __take_json_string <s starting at the opening quote>
# -> __STR_CONTENT (escaped content), __STR_REST (after closing quote)
__take_json_string() {
  local s=$1 n=${#1} i
  [[ $s == \"* ]] || { __STR_CONTENT=""; __STR_REST=""; return 1; }
  for ((i = 1; i < n; i++)); do
    if [[ ${s:i:1} == \\ ]]; then
      ((i+=1))
      continue
    fi
    if [[ ${s:i:1} == '"' ]]; then
      __STR_CONTENT=${s:1:i-1}
      __STR_REST=${s:i+1}
      return 0
    fi
  done
  __STR_CONTENT=""; __STR_REST=""
  return 1
}

# envelope_parse_canonical <line> — parse AND require the re-emission to be
# byte-identical to the input (contract canonicality: one serialization).
envelope_parse_canonical() {
  local line=$1
  envelope_parse "$line" || return 1
  local re
  re=$(envelope_emit "$E_EXECID" "$E_SHA" "$E_CWD" "$E_ENVP" "$E_STDP" "${E_ARGV[@]}")
  [[ $re == "$line" ]]
}

# ---------------------------------------------------------------------------
# 4. logical-root relative path grammar (contract §3.1.1)
# ---------------------------------------------------------------------------
# valid_rel_path <path>
#   charset `[A-Za-z0-9][A-Za-z0-9._/-]*` (no backslash, no %, no spaces,
#   no control bytes by construction of the charset), every '/'-segment
#   non-empty and not `.`/`..` and not starting with `-`. No leading `/` and
#   no `..` segments ⇒ the path cannot escape its logical root.
valid_rel_path() {
  local p=$1
  [[ -n $p ]] || return 1
  [[ $p =~ ^[A-Za-z0-9][A-Za-z0-9._/-]*$ ]] || return 1
  # IFS word-splitting collapses adjacent '/', so empty segments need their
  # own check (an empty segment means "//" — structurally inside nothing).
  [[ $p == *//* ]] && return 1
  local seg
  local IFS=/
  for seg in $p; do
    [[ -n $seg ]] || return 1
    if [[ $seg == . || $seg == .. ]]; then return 1; fi
    [[ $seg == -* ]] && return 1
  done
  return 0
}
