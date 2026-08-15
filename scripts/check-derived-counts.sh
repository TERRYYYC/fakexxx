#!/usr/bin/env bash
# check-derived-counts.sh -- the §10.1 ledger is the only source of derived counts.
#
# WHY THIS EXISTS.
# Every count that appears in prose (§15 lane selectors, §16 task scopes, the
# aggregation table, the class-responsibility table) is a CACHE of a number that
# is actually derived from the §10.1 ledger rows. Prose-to-prose synchronisation
# of those caches has now failed four separate times:
#
#   v1.38  appended M-AD-01..11, synced the ledger, left the lane selector table
#   v1.39  fixed the lane selector table, left the aggregation and class tables
#          20 lines away -- while its own note claimed all four were repaired
#   v1.46  recomputed 29 cache points by hand and still missed one
#   v1.46  the miss was a SECOND stale number later in a line already edited in
#          the same pass, which is the exact failure mode that commit's own
#          message called out by name
#
# Four rounds is not carelessness, it is a missing guard. v1.46 froze the rule
# "derived counts may only be computed from the §10.1 ledger" and shipped it
# with nothing enforcing it -- a documented decision with no guard is a former
# decision. This is the guard.
#
# WHY THIS WAS REWRITTEN (the guard's own false-green round).
# The first guard measured PASS in the units of its own blind spot -- the same
# disease §7b recorded at v1.53. Its line selector required a BACKTICKED
# `owner-red`, and its number extractor recognised exactly two spellings
# ("<n> 行" and "<n> 个 `owner-red`"). Four notations the spec actually uses
# never entered the scan at all:
#
#   1. bare digit CELLS in the class/lane/aggregation tables
#      (| `owner-red` | 86 |) -- v1.58 moved the ledger 86->88 and the cell
#      stayed at 86 behind a green PASS;
#   2. BOLD-wrapped digits (**110**) in table cells, where the digit never
#      touches 行 directly;
#   3. PLAIN-TEXT owner-red (no backticks) -- the §16 verify-command comments
#      ("# 36 行 owner-red") are exactly the lane-scope caches this guard
#      exists for, and they were invisible;
#   4. CHINESE NUMERALS (五行) -- KB-6's live inventory claim.
#
# While all four sat unseen, the guard printed "PASS (ledger is the single
# source; 114 rows)". A count of what a guard can see reads exactly like
# coverage; the §7b lesson applies verbatim: ENUMERATE, never count. The
# guard now prints every cache site it can see, by notation arm and line,
# and the verdict names the offending lines.
#
# WHAT THIS DOES NOT DO.
# It cannot tell whether a count is semantically the right thing to state. It
# recomputes the ledger truth and fails when an ACTIVE prose cache disagrees.
# Historical revision records are excluded by design; see the exemption notes
# in section 3 for how "historical" is decided per match, not per line.
set -uo pipefail

SPEC="${1:-feature-specs/2026-08-09-cellrebel-qianwangyou-a-plus.md}"
FAILURES=0

pass() { printf '  PASS  %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

if [ ! -f "$SPEC" ]; then
  printf 'check-derived-counts: spec not found: %s\n' "$SPEC" >&2
  exit 2
fi

# -------------------------------------------------------------- input identity
# WHY: a verdict that does not name its input is a verdict about nothing.
# The spec is selected by a RELATIVE default path, so the same command run
# from a different checkout scans that checkout's copy without a word of
# warning. That is not hypothetical: a copy ~30 revisions stale (its ledger
# still partitioned owner-red=64 / pr-3=33 / pr-4=0, the era before the lane
# re-projection, when the pr-4 owner column read Opus5) was scanned and
# reported five real cache sites valued 31 -- and the reader then verified
# those LINE NUMBERS against the current spec, where L2908 is a directory
# tree line and L3018 is blank. Every individual fact was true; nothing in
# the output said WHICH FILE produced it, so a stale copy's FAIL was read as
# a current target's regression and blocked a merge gate on a green line.
# The banner names the file actually scanned: absolute path, line count, a
# sha256 prefix, the file's own latest revision marker (the §0.1 changelog
# is the highest-numbered `| vN.NN |` table row by construction), and the
# git coordinates when the spec lives in a repository. Informational only:
# no verdict logic reads it, and a marker-less spec prints `none`, never a
# guess.
SPEC_DIR="$(cd -- "$(dirname -- "$SPEC")" && pwd)"
SPEC_ABS="$SPEC_DIR/$(basename -- "$SPEC")"
SPEC_LINES=$(wc -l < "$SPEC" | tr -d ' ')
if command -v sha256sum >/dev/null 2>&1; then
  SPEC_SHA=$(sha256sum "$SPEC" | cut -c1-12)
elif command -v shasum >/dev/null 2>&1; then
  SPEC_SHA=$(shasum -a 256 "$SPEC" | cut -c1-12)
else
  SPEC_SHA=unavailable
fi
SPEC_VER=$(grep -oE '^\| \*{0,2}v1\.[0-9]+' "$SPEC" \
  | grep -oE 'v1\.[0-9]+' | sort -V | tail -1)
[ -n "$SPEC_VER" ] || SPEC_VER=none
if GIT_HEAD=$(git -C "$SPEC_DIR" rev-parse --short=12 HEAD 2>/dev/null); then
  GIT_BRANCH=$(git -C "$SPEC_DIR" rev-parse --abbrev-ref HEAD 2>/dev/null || echo detached)
  GIT_WHERE="git HEAD $GIT_HEAD ($GIT_BRANCH)"
else
  GIT_WHERE="outside a git repository"
fi
printf '\n== input provenance ==\n'
printf '  spec: %s (%s lines, sha256 %s, revision marker %s)\n' \
  "$SPEC_ABS" "$SPEC_LINES" "$SPEC_SHA" "$SPEC_VER"
printf '  %s\n' "$GIT_WHERE"

# ---------------------------------------------------------------- ledger truth
# A §10.1 ledger row is: | `M-XX-NN` | category | `class` | owner | `entry` |
# A §10 matrix row is the same ID with NO evidence class -- that is what
# distinguishes the two tables, and it is why an ID-prefix match alone is not
# enough to address a row (a lesson from v1.46: the first attempt at inserting
# these rows matched both tables and was caught only by an assertion).
ledger_rows() {
  grep -E '^\| `M-[A-Z]{2}-[0-9]+` \| [a-z-]+ \| `(owner-red|sol-blackbox|static-guard|device)` \|' "$SPEC"
}
matrix_rows() {
  grep -E '^\| `M-[A-Z]{2}-[0-9]+` \| [a-z-]+ \|' "$SPEC" \
    | grep -vE '`(owner-red|sol-blackbox|static-guard|device)`'
}

TOTAL=$(ledger_rows | wc -l | tr -d ' ')
MATRIX=$(matrix_rows | wc -l | tr -d ' ')
RED=$(ledger_rows | grep -cE '`owner-red`')
BLACKBOX=$(ledger_rows | grep -cE '`sol-blackbox`')
STATIC=$(ledger_rows | grep -cE '`static-guard`')
DEVICE=$(ledger_rows | grep -cE '`device`')
PR3=$(ledger_rows | grep -E '`owner-red`' | grep -E '\| Fable5 \|' | grep -c 'apps/qianwangyou/')
PR4=$(ledger_rows | grep -E '`owner-red`' | grep -E '\| GLM \|' | grep -c 'apps/cellrebel-auto/')

printf '\n== derived from the §10.1 ledger ==\n'
printf '  ledger=%s matrix=%s owner-red=%s (pr-3=%s pr-4=%s) blackbox=%s static=%s device=%s\n' \
  "$TOTAL" "$MATRIX" "$RED" "$PR3" "$PR4" "$BLACKBOX" "$STATIC" "$DEVICE"

printf '\n== 1. the two tables describe the same row set ==\n'
if [ "$TOTAL" -eq "$MATRIX" ]; then
  pass "§10 and §10.1 agree on row count ($TOTAL)"
else
  fail "§10 has $MATRIX rows but §10.1 has $TOTAL -- one table was edited alone"
fi

DUPES=$(ledger_rows | sed -E 's/^\| `(M-[A-Z]{2}-[0-9]+)`.*/\1/' | sort | uniq -d)
if [ -z "$DUPES" ]; then
  pass "no duplicate ledger row IDs"
else
  fail "duplicate ledger row IDs: $(echo "$DUPES" | tr '\n' ' ')"
fi

printf '\n== 2. class partition is exhaustive ==\n'
SUM=$((RED + BLACKBOX + STATIC + DEVICE))
if [ "$SUM" -eq "$TOTAL" ]; then
  pass "owner-red $RED + blackbox $BLACKBOX + static $STATIC + device $DEVICE = $TOTAL"
else
  fail "class counts sum to $SUM but the ledger has $TOTAL rows -- a row carries an unknown class"
fi

if [ "$((PR3 + PR4))" -eq "$RED" ]; then
  pass "lane selectors partition owner-red: pr-3 $PR3 + pr-4 $PR4 = $RED"
else
  fail "pr-3 $PR3 + pr-4 $PR4 = $((PR3 + PR4)) but owner-red is $RED -- a lane selector lost rows"
fi

# ------------------------------------------------- active prose cache scanning
# §0.1's revision records are frozen history. Everything at or after §7 is
# active normative text whose counts must agree with the ledger.
ACTIVE_FROM=$(grep -n '^## 7\.' "$SPEC" | head -1 | cut -d: -f1)
[ -z "$ACTIVE_FROM" ] && ACTIVE_FROM=2000

printf '\n== 3. active prose caches agree with the ledger ==\n'

# The scan itself is python: five notation arms, each a NAMED knob, because a
# mutation test (selftest-derived-counts.sh) must be able to disable exactly
# one arm and prove which one is load-bearing -- the same reason check-contract
# -v1.sh keeps its BOLD tolerance as a named variable. Exit status = number of
# stale cache sites (0 = green). Everything it can see is enumerated above the
# verdict: a blind spot is only visible if the inventory is.
python3 - "$SPEC" "$ACTIVE_FROM" "$TOTAL" "$RED" "$PR3" "$PR4" "$BLACKBOX" "$STATIC" "$DEVICE" <<'PY'
import re, sys

spec_path, active_from = sys.argv[1], int(sys.argv[2])
TOTAL, RED, PR3, PR4, BLACKBOX, STATIC, DEVICE = (int(x) for x in sys.argv[3:10])
NONRED = BLACKBOX + STATIC + DEVICE
BASE_LEGAL = {TOTAL, RED, PR3, PR4, BLACKBOX, STATIC, DEVICE, NONRED}

lines = open(spec_path, encoding="utf-8").read().splitlines()

# ---- knobs: each arm is a named, individually disableable matcher ----------
# SCOPE_PLAIN_OWNER_RED is the class-3 widening on its own line: plain-text
# owner-red (no backticks) must pull a line into scope. Old selector required
# backticks, so every "# 36 行 owner-red" verify-command comment was invisible.
SCOPE_PLAIN_OWNER_RED = r'owner-red'
# 矩阵行, never bare 矩阵: the spec has other matrices ("§6.4.1 矛盾 tuple
# 矩阵（8 行独立负例）") whose rows are not caches of THIS ledger -- the bare
# word made one of those an instant false stale site.
#
# Built by join, NEVER by '|' + string-concat of a variable tail: a disabled
# last branch leaves a trailing '|' whose empty alternative matches EVERY
# line -- the mutation test caught exactly that, scope silently becoming the
# whole document (52 -> 73 sites, both false positives resurrected).
# SCOPE_LANE_DISCOURSE / SCOPE_ROW_COUNT are the v1.61 widening, and they exist
# because the v1.60 recompute was reported as complete while a SECOND group of 15
# active stale values sat outside this scope. Topic tokens (`owner-red`, 台账,
# 矩阵行) only reach lines that NAME the ledger; they never reached the lines that
# merely COUNT it -- `--lane pr-6  # 全 112 行`, "lane selector ... PR-3 的 38 行",
# "这 38 行自审". Those are caches by every definition this guard uses.
#
# Scoped by phrase shape, not by bare 行: the guard's own note records that a bare
# 矩阵 pulled in "§6.4.1 矛盾 tuple 矩阵（8 行独立负例）", whose rows are not caches
# of THIS ledger. 全/全部 + N + 行 is a count OF A KNOWN WHOLE.
#
# 这 N 行 was in this scope until v1.63 and is NOT, because it does not carry that
# meaning: "这 8 行是 §6.4.1 的独立负例" is the same counterexample family, one
# demonstrative away. The reasoning that grouped 这 with 全 was tested and false,
# so it is corrected here rather than deleted -- a disproved rationale left in a
# comment is an instruction to re-add the defect. Lines that legitimately count
# ledger rows with 这 are handled by naming the class ("这 39 行 `owner-red`"),
# which is more precise prose and reaches scope through a token that already
# exists.
SCOPE_LANE_DISCOURSE = r'--lane|lane selector|exactHead|evidenceOwner'
SCOPE_ROW_COUNT = r'全\s*[0-9]+\s*行|全部\s*[0-9]+\s*行'
SCOPE_PARTS = [r'`owner-red`', r'`sol-blackbox`', r'`static-guard`',
               r'`device`', r'台账', r'矩阵行']
if SCOPE_PLAIN_OWNER_RED:
    SCOPE_PARTS.append(SCOPE_PLAIN_OWNER_RED)
if SCOPE_LANE_DISCOURSE:
    SCOPE_PARTS.append(SCOPE_LANE_DISCOURSE)
if SCOPE_ROW_COUNT:
    SCOPE_PARTS.append(SCOPE_ROW_COUNT)
SCOPE_RE = re.compile('|'.join(SCOPE_PARTS))
if SCOPE_RE.search(''):
    # A pattern that matches the empty string matches every line. Refuse to
    # scan rather than report aPASS measured over the entire document.
    print('  FAIL  scope pattern matches the empty string (dangling alternation) -- refusing to scan')
    sys.exit(99)

# An explicit statement that the line QUOTES superseded text. NARROW ON PURPOSE
# (v1.47: a version CITATION is not a historical quote). See exemption() for
# how the marker is applied per match, not per line.
HISTORY_MARKER = re.compile(r'更正|旧文|逐字引用|上一版|此前写作|此前只列')

# The lookbehind excludes identifier fragments: "见上表 PR-5 行" is the pr-5
# LANE, not five rows -- a digit glued to an identifier by - . _ or * is a
# name, never a count.
ARM_BARE = re.compile(r'(?<![\d.\-*_])([0-9]+)(?![\d*])\s*行')
ARM_BOLD = re.compile(r'\*\*([0-9]+)\*\*\s*行')
ARM_REDCT = re.compile(r'(?<![\d])([0-9]+)\s*个\s*`?owner-red`?')
ARM_CN = re.compile(r'(?<!第)([零一两二三四五六七八九十百]+)\s*行')
# ARM_OWNER_PAIR: "GLM 48 / Fable5 38" -- a per-owner split of owner-red that
# never touches 行, so every 行-anchored arm above walked straight past it while
# the line itself was already in scope. Being in scope is not being readable.
# The trailing class blocks version-like tails (GLM 5.2) and percentages.
ARM_OWNER_PAIR = re.compile(r'(?<![\w-])(?:GLM|Fable5|Opus5|DeepSeek Flash)\s*[：:／/]?\s*([0-9]+)(?![\d.*%])')
# ARM_CURVAL: "现行为 **84**" -- a claim whose entire purpose is to state the
# CURRENT ledger value, and the one notation where being wrong is most direct.
# Deliberately narrow: dropping the 行 requirement from ARM_BOLD instead would
# swallow every bold wire code (`STALE_LEASE`(**8**)) on an in-scope line.
ARM_CURVAL = re.compile(r'现行为\s*\*{0,2}([0-9]+)\*{0,2}')
# ARM_BOLD_PHRASE: "**92 行 `owner-red`**", "**118 行 / 18 类**" -- the number and
# 行 BOTH inside ONE bold span. This shape fell between two arms and neither could
# read it: ARM_BARE's lookbehind rejects the leading '*', and ARM_BOLD requires the
# number bolded ALONE ("**92** 行"). Two LIVE sites sat in this form while the guard
# printed "0 stale cache site(s)" -- owner-red written as 88 (true 92) and §10
# written as "114 行 / 18 类" (true 118). Sol found the first; the notation census
# that produced this arm found the second, which is the whole argument for doing a
# census: fixing the reported instance would have left the guard equally blind.
#
# This is the sixth time in this document that a matcher was narrower than the
# notation it claimed to cover, and the standing rule it violates is written in
# this repo's own gotchas: ENUMERATE the document's actual spellings BEFORE
# writing the pattern, never afterwards. [^*]*? on both sides keeps the span
# inside a single ** ... ** run so an unrelated later bold cannot be spliced in.
ARM_BOLD_PHRASE = re.compile(r'\*\*[^*]*?(?<![\d.\-_])([0-9]+)(?![\d])\s*行[^*]*?\*\*')

# SINGLE source for the arm list: the scan loop and the enumeration below both
# read it. They used to carry separate copies, so an arm could be scanned and
# never enumerated -- invisible in the one output that exists to expose blind
# spots. 'cell' is appended by the enumeration because it is applied separately
# (keyed rows), not by a regex in this table.
ARMS = (('bare', ARM_BARE), ('bold', ARM_BOLD), ('redct', ARM_REDCT), ('cn', ARM_CN), ('pair', ARM_OWNER_PAIR), ('curval', ARM_CURVAL), ('bold-phrase', ARM_BOLD_PHRASE))
# ENUM_ARMS is a SEPARATE knob from ARMS on purpose. Collapsing ARMS disables the
# scanner, so the finding disappears and a mutation proves only that the scanner
# reads ARMS -- it says nothing about whether the enumeration reads the same
# table, which is the property that actually failed before. Mutating this knob
# alone leaves the finding alive and removes only the inventory line, so the two
# paths can be measured as two dimensions instead of one.
ENUM_ARMS = [a for a, _ in ARMS] + ['cell']

# "现行为 N" asserts the PRESENT value, so a loose bracket span must not exempt
# it -- L2801 quotes Issue #6's superseded 64 and states the current value in one
# sentence, inside one （…） span that would hide both. But it is NOT immune to
# exemption outright: a correction line may legitimately quote the old sentence
# verbatim ("上一版逐字写作「现行为 **84**」"), and a guard that reports that as
# stale is a false red -- which gets guards disabled. So curval exempts only on
# the explicit forms: a whole correction blockquote, or a 「…」 quotation.
QUOTE_ONLY_ARMS = {'curval'}

# CELL arm: keyed table rows (first cell names a lane or class). pr-3.5 / pr-5
# are frozen empty-set policy lanes, included so their expected 0 stays pinned
# (allowed per-line only where the row itself says 空集 -- the same per-line
# scoping the appid-cutover 5 uses).
# Single line, like every other knob: `s/^KNOB = .*/KNOB = .../` is the mutation
# contract, and a knob wrapped onto a second line turns that contract into an
# orphaned continuation -- a SyntaxError, whose silence the mutation helper used
# to read as "the arm is load-bearing". M-CELL passed that way from 6cfea07 until
# the run-health check exposed it.
CELL_KEYS = ('pr-3', 'pr-3.5', 'pr-4', 'pr-5', 'pr-6', 'owner-red', 'sol-blackbox', 'static-guard', 'device')

# Frozen: the appid-cutover rows moved to Issue #13 and are not ledger rows.
APPID_CUTOVER_ROWS = 5
EMPTY_SET_ROWS = 0

CN_DIGITS = {'零': 0, '一': 1, '两': 2, '二': 2, '三': 3, '四': 4,
             '五': 5, '六': 6, '七': 7, '八': 8, '九': 9}

def cn2int(s):
    total = section = val = 0
    for ch in s:
        if ch in CN_DIGITS:
            val = val * 10 + CN_DIGITS[ch]
        elif ch == '十':
            section += (val or 1) * 10
            val = 0
        elif ch == '百':
            section += (val or 1) * 100
            total += section
            section = 0
            val = 0
    return total + section + val

# 第 N 行 is a row ADDRESS, not a row COUNT -- an address is not a cache of a
# total, and treating it as one made KB-6's own "本表第 128 行" a fake stale
# site. Stripped (digit and Chinese-numeral forms) before any arm runs.
ORDINAL = re.compile(r'第\s*[0-9零一两二三四五六七八九十百]+\s*行')

def keyed_row(s):
    s = s.strip()
    if not (s.startswith('|') and s.endswith('|')):
        return False
    # Ledger/matrix ID rows are the SOURCE of truth, never a cache of it.
    if re.match(r'^\|\s*`?M-[A-Z]{2}-\d+', s):
        return False
    cells = s.split('|')[1:-1]
    if not cells:
        return False
    first = cells[0]
    for k in CELL_KEYS:
        if re.search(r'(?i)(?<![\w.])' + re.escape(k) + r'(?![\w.])', first):
            return True
    return False


def quoted_spans(s, corner_only=False):
    # 「…」 and （…） spans; an unclosed opener extends to end of line.
    #
    # corner_only drops （…）. A full-width paren span routinely wraps a whole
    # explanatory clause -- L2801's opens 210 chars before the live claim it then
    # swallowed -- whereas 「…」 is an explicit quotation of superseded text. For
    # a present-tense claim only the explicit form can mean "this is a quote".
    openers = '「' if corner_only else '「（'
    closers = '」' if corner_only else '」）'
    spans, depth_open = [], None
    for i, ch in enumerate(s):
        if ch in openers:
            if depth_open is None:
                depth_open = i
        elif ch in closers and depth_open is not None:
            spans.append((depth_open, i))
            depth_open = None
    if depth_open is not None:
        spans.append((depth_open, len(s)))
    return spans

def exempt(start, stripped_line, work_line, quote_only=False):
    # Per-match history exemption -- the line-level exemption this guard shipped
    # with let KB-6's LIVE claim (现为五行) hide behind its own 此前写作.
    #   - a correction BLOCKQUOTE ("> ... v1.NN 更正 ...") is wholesale
    #     history: exempt the whole line, as before;
    #   - an in-flow marker word exempts only what it actually quotes: matches
    #     inside 「」/（） spans. Matches outside the brackets are live claims.
    if not HISTORY_MARKER.search(stripped_line):
        return False
    if stripped_line.startswith('>'):
        return True
    return any(a <= start <= b for a, b in quoted_spans(work_line, corner_only=quote_only))

sites = []     # (absno, arm, value)  -- every number the guard can see
findings = []  # (absno, arm, value)  -- the ones the ledger cannot produce
skipped_cn_one = 0

for idx in range(active_from - 1, len(lines)):
    line = lines[idx]
    absno = idx + 1
    stripped = line.strip()
    keyed = keyed_row(stripped)
    if not (SCOPE_RE.search(line) or keyed):
        continue
    # Same-length mask so match positions stay valid for span exemption.
    work = ORDINAL.sub(lambda m: '第' + '⋯' * (len(m.group(0)) - 1), line)
    legal = set(BASE_LEGAL)
    if 'appid-cutover' in line:
        legal.add(APPID_CUTOVER_ROWS)
    if '空集' in line:
        legal.add(EMPTY_SET_ROWS)

    def consider(arm, value, pos):
        sites.append((absno, arm, value))
        if value not in legal and not exempt(pos, stripped, work,
                                            quote_only=(arm in QUOTE_ONLY_ARMS)):
            findings.append((absno, arm, value))

    for arm, rx in ARMS:
        # `if rx` keeps a mutation test able to disable exactly one arm
        # (ARM_X = None) and have the scan SKIP it cleanly instead of dying
        # mid-gate -- a crash also removes findings, for the wrong reason.
        if not rx:
            continue
        for m in rx.finditer(work):
            if arm == 'cn':
                v = cn2int(m.group(1))
                if v == 1:
                    # 一行 is grammar ("同一行/每一行"), not a count of ledger
                    # rows; no ledger value is 1, so checking it would only
                    # drown the signal. Visible in the skip counter below.
                    skipped_cn_one += 1
                    continue
            else:
                v = int(m.group(1))
            consider(arm, v, m.start(1))
    if keyed:
        off = 0
        for seg in work.split('|')[1:]:
            if re.fullmatch(r'\*{0,2}\s*[0-9]+\s*\*{0,2}', seg.strip()):
                consider('cell', int(seg.strip().strip('*').strip()), off)
            off += len(seg) + 1

# ---- enumeration: every site the guard can see, by arm ----------------------
legal_repr = ' '.join(str(v) for v in sorted(BASE_LEGAL, reverse=True))
print('  ledger can produce: %s (plus %d on appid-cutover lines, %d on 空集 lanes)'
      % (legal_repr, APPID_CUTOVER_ROWS, EMPTY_SET_ROWS))
stale = {(a, ar, v) for (a, ar, v) in findings}
for arm in ENUM_ARMS:
    entries = ['L%d=%s%s' % (a, v, '*' if (a, arm, v) in stale else '')
               for (a, ar, v) in sites if ar == arm]
    if entries:
        print('    %-5s: %s' % (arm, ' '.join(entries)))
    else:
        print('    %-5s: (no site in this notation -- a widening that loses one shows here)' % arm)
if skipped_cn_one:
    print('    cn-skip: %d "一行" match(es) treated as grammar, not counts' % skipped_cn_one)

seen = set()
for (a, ar, v) in findings:
    if (a, ar, v) in seen:
        continue
    seen.add((a, ar, v))
    print('  FAIL  L%d %s %s -- the ledger cannot produce %s on this line (it can: %s%s)'
          % (a, ar, v, v, legal_repr,
             ', 5' if 'appid-cutover' in lines[a - 1] else ''))

print('  => section 3: %d stale cache site(s) of %d enumerated WITHIN SCOPE' % (len(seen), len(sites)))
print('  ----  scope is a filter, not a census: a cache on a line no arm and no\n        scope token reaches is not counted above and never appears as a gap.')
sys.exit(min(len(seen), 100))
PY
STALE=$?
if [ "$STALE" -eq 0 ]; then
  pass "every IN-SCOPE cache site is a value the ledger can produce"
else
  fail "active prose states counts the ledger cannot produce (see enumeration)"
fi

printf '\n'
if [ "$FAILURES" -eq 0 ]; then
  printf 'check-derived-counts: PASS (ledger is the single source; %s rows; every IN-SCOPE cache site enumerated above)\n' "$TOTAL"
  exit 0
fi
printf 'check-derived-counts: FAIL (%d check(s) failed; %s stale cache site(s), enumerated above by arm and line)\n' \
  "$FAILURES" "$STALE"
exit 1
