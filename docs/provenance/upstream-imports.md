---
feature_ids: []
topics:
  - provenance
  - upstream-import
  - cellrebel
  - qianwangyou
doc_kind: provenance_record
created: 2026-08-09
status: active
---

# Upstream imports — provenance record

Single source of truth for where the two vendored Android baselines in this
repository came from. Machine-checked by `scripts/check-provenance.sh`; the
exact SHAs below are duplicated inside that script so the document and the gate
cannot drift apart without the gate failing.

Spec: [`feature-specs/2026-08-09-cellrebel-qianwangyou-a-plus.md`](../../feature-specs/2026-08-09-cellrebel-qianwangyou-a-plus.md) §1.2, §1.3, §13 Task 1.

## 1. Imports

| Prefix | Upstream URL | Branch | Exact upstream SHA | Upstream root tree | fakexxx import commit |
|---|---|---|---|---|---|
| `apps/cellrebel-auto` | `https://github.com/TERRYYYC/Faketest.git` | `main` | `48d8ec93adb84cdb9c4282c376ec97476648683e` | `0553fcb46f02e7211f4496e4a98b846ec70ef9a2` | import commit `301da0f2925373dfe40cfd2a51d53ddaca4bba93` |
| `apps/qianwangyou` | `https://github.com/TERRYYYC/FakeGps-test.git` | `master` | `285e4cae438ab6feea1f70f984f433c7a424b944` | `f4bdce23c65e6227cf43dab5fe0416120b95134e` | import commit `5687e319f978dcd9b76e413c06b2b0da91627518` |

Upstream commit detail at import time:

| Prefix | Upstream commit date (UTC) | Upstream subject | Position on branch |
|---|---|---|---|
| `apps/cellrebel-auto` | 2026-08-02T11:19:23Z | `docs(F002): implementation plan — L1 gate + v5 audit columns + L2 spike path` | tip of `main` at import time |
| `apps/qianwangyou` | 2026-08-08T01:29:44Z | `fix(config): verify real cross-process readability + fail-closed publish outcome (#23)` | tip of `master` at import time |

## 2. Import method

Both imports were produced by `git subtree add --squash` reading **directly from
the upstream remote at the exact SHA**. No local working tree was used as a copy
source, as required by spec §13 Task 1 GREEN.

```bash
git subtree add --prefix=apps/cellrebel-auto --squash \
  https://github.com/TERRYYYC/Faketest.git 48d8ec93adb84cdb9c4282c376ec97476648683e

git subtree add --prefix=apps/qianwangyou --squash \
  https://github.com/TERRYYYC/FakeGps-test.git 285e4cae438ab6feea1f70f984f433c7a424b944
```

`--squash` keeps the fakexxx DAG from becoming a fork of two unrelated
histories, while the generated squash commits carry machine-readable
`git-subtree-dir:` / `git-subtree-split:` trailers naming the exact upstream
SHA. Those trailers are a second, independent record of the same fact.

## 3. How the import is verified

`scripts/check-provenance.sh` asserts, per prefix:

0. the frozen record set still carries every prefix the gate is responsible for,
   and is unchanged between the first and last section (a shortened set would
   make the gate skip an app while still exiting 0);
1. this document records the URL, branch, exact SHA and the fakexxx import commit;
2. **the anchor** — this document's recorded upstream **root tree** equals the
   tree of the upstream object actually fetched from the upstream URL. This is
   checked at **every** stage and depends on no local history, so it survives a
   squash or rebase merge intact;
3. *additionally, and only while it is still reachable*, the recorded import
   commit carries that upstream tree at the prefix. This is DAG evidence: a
   squash/rebase merge legitimately discards it, so it is a bonus, never the
   anchor;
4. at `--stage import` only, the committed tree of the prefix still equals the
   upstream root tree (pristine). At `contract`/`full` divergence is expected and
   **this gate does not bound which paths diverged** — that is an ownership
   question for the boundary gate, not a provenance question;
5. the working tree has not drifted from the verified commit;
6. the entry files exist (`gradlew`, `app/build.gradle*`, `app/src/main/AndroidManifest.xml`).

**What step 2 replaced.** An earlier version made the reachable import commit the
load-bearing proof, with section 2 of the script deferring to section 1 and
section 1 deferring back to section 2. Once the DAG was gone both statements
pointed at each other: a fresh single-commit clone passed `--stage contract` with
arbitrary divergence in the vendored trees. Provenance is a claim about content,
so it is now carried by content.

Step 3 fetches explicitly because CI checkouts are shallow and do not contain
upstream objects. An unobtainable object is a **hard failure**, never a skip — a
skip would silently reduce the gate to a no-op.

### Why not `rev-parse --is-inside-work-tree`

The frozen spec at `00a5e58` proposed
`git -C apps/cellrebel-auto rev-parse --is-inside-work-tree` as the Task 1
verification. Measured in this worktree, on directories created with `mkdir`
and containing **no imported files at all**:

```
git -C apps/cellrebel-auto rev-parse --is-inside-work-tree -> true
git -C apps/qianwangyou  rev-parse --is-inside-work-tree -> true
```

The subtree directories are inside the fakexxx worktree, so the assertion is
true for any directory under the repository root. It cannot fail here, and it
proves neither that the import happened nor that the SHA is the right one. The
tree-digest comparison replaces it. (This matches the correction raised in the
open spec PR `TERRYYYC/fakexxx#9`; the replacement is strictly stronger than
both spec revisions, so it is compatible with either outcome of that PR.)

## 4. Truth-source rules after this import (spec §1.3)

- `fakexxx` is now the source of truth for this integration's code, contract,
  tests, issues and release evidence.
- `TERRYYYC/Faketest` and `TERRYYYC/FakeGps-test` remain history and upstream
  provenance only. The same feature must not be maintained in three repos.
- Any fix that must be absorbed from an upstream repo enters `fakexxx` through
  an explicit sync PR that records the source SHA, and updates the table in §1
  plus the SHA constants in `scripts/check-provenance.sh` in the same PR.

## 5. Baseline facts carried in, verified against the imported trees

Read directly from the imported files at this HEAD, not from secondary summaries:

| Fact | `apps/cellrebel-auto` | `apps/qianwangyou` |
|---|---|---|
| `applicationId` | `com.example.cellrebelauto` | `name.caiyao.fakegps` (+ `applicationIdSuffix ".bench"` on the bench build) |
| `minSdk` / `targetSdk` / `compileSdk` | 26 / 35 / 35 | 24 / 35 / 35 |
| `versionCode` / `versionName` | 1 / `1.0` | 8 / `3.0.0` |
| Gradle wrapper | 9.3.1 | 9.3.1 |
| AGP / Kotlin | 9.1.0 / 2.2.10 | 9.1.0 / 2.2.10 |
| KSP | 2.3.2 | 2.2.10-2.0.2 |
| App build file | `app/build.gradle.kts` | `app/build.gradle` |
| Room database | `AppDatabase` `version = 4`, `exportSchema = false` | has `room.schemaLocation` configured |

Two consequences that later PRs must not rediscover the hard way:

- The shared contract library must use `minSdk = 24`. Qianwangyou is `minSdk 24`
  and cannot depend on a library that declares 26.
- `apps/cellrebel-auto/app/src/main/AndroidManifest.xml` declares
  `<queries>` for `com.cellrebel.mobile` and `com.hopefactory2021.fakegpslocation`
  only — **neither Qianwangyou applicationId is listed**. On `targetSdk 35`,
  an explicit bind to Qianwangyou's service is subject to package visibility,
  so this file must be amended when the contract is wired in.

## 6. Risks imported with the baselines (not closed by this import)

Per spec §1.2, these stay open and must not be silently reclassified:

| Upstream issue | State at import time | Rule |
|---|---|---|
| `TERRYYYC/FakeGps-test#14` — System Mock: official Google Maps blue dot still flickers between mock and real location | OPEN, P0 | Not closed by this feature. A+ release evidence must not rewrite "unverified" into "stable". |
| `TERRYYYC/FakeGps-test#15` — Hook: Google Maps reverts to real location 1–2s after save | OPEN, P0 | Same. New exact-build acceptance must produce independent evidence. |

Assets that must survive the move and must not be lost to the re-vendoring:
Auto's MIUI/HyperOS cross-app switching knowledge, the CellRebel
`PRE_EXISTING_RUN` determination, and the semantics that an external execution
may re-run.

### 6.1 Inherited Android lint debt (discovered by this import)

**Neither upstream repository has any GitHub Actions workflow** (`.github/workflows`
returns 404 in both), so `lintDebug` had never been enforced as a gate. Running
it at the frozen SHAs for the first time, on AGP 9.1.0 / compileSdk 35 / JDK 17:

| App | lint errors | Breakdown | Warnings |
|---|---|---|---|
| `apps/cellrebel-auto` | **0** | — | 20 |
| `apps/qianwangyou` | **23** | `NewApi`=9, `MissingTranslation`=6, `Range`=5, `MissingPermission`=3 | 161 |

Consequence: `(cd apps/qianwangyou && ./gradlew lintDebug assembleDebug)` — a
command listed in spec §14 with an expected exit 0 — **exits 1 at the frozen
baseline**, before any feature code is written.

This PR does not fix and does not silence them:

- fixing them means editing `apps/qianwangyou/**`, which belongs to the
  Qianwangyou provider lane (Kimi / PR-3), not to this import;
- silencing them with `lint { baseline = … }` or `abortOnError false` would hide
  23 real errors, three of which (`MissingPermission`) are in the mock-provider
  gateway and nine of which (`NewApi`) call `Location#isMock`, API 31, from a
  `minSdk 24` module;
- marking the CI step `continue-on-error` would print green over a red gate.

Instead `scripts/check-inherited-lint-debt.sh` freezes the exact per-issue
inventory above and fails on **any increase or any new issue type**, per app.
A pass means "the debt did not grow" and says so explicitly; it never claims
`lintDebug` exits 0. Reducing the debt is expected to lower the budget in the
same PR.

**Remediation ownership: still OPEN.** The 23 errors span `mockprovider/**`,
`probe/**`, `ui/**`, `dao/**` and resources. The owner matrix grants the
Qianwangyou lane (Kimi, PR-3) only
`apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/integration/**` plus the
matching Manifest/Gradle lines, so **no lane in the current matrix may legally
edit those files**. That is the problem, not the resolution:

- **There is currently no legal remediation owner.** Assigning one requires
  widening a lane's exclusive write scope, and the owner matrix is what keeps
  three parallel lanes from colliding — so it is an orchestrator/operator
  amendment, not something a lane may take unilaterally.
- **The ratchet is a holding measure, not the terminal gate.**
  `scripts/check-inherited-lint-debt.sh` fails on any increase or any new issue
  type, per app. It bounds the debt and keeps it visible. It does **not**
  satisfy the release gate.
- **The frozen spec §14 still requires `(cd apps/qianwangyou && ./gradlew
  lintDebug assembleDebug)` to exit 0**, and at the inherited baseline it exits
  1. That contradiction is unresolved and is recorded here as unresolved.
- **Deciding otherwise is a separate operator value decision** — "does A+ ship
  with a bounded-debt ratchet instead of raw-green lint?" — and it needs its own
  Decision Packet. An earlier revision of this file declared the remediation
  "out of A+ scope" with the ratchet as terminal gate. That was an overreach:
  the operator's disposition covered the credential question in §6.2 only, and
  one authorization was used to settle two different decisions. Retracted.

### 6.2 Credential-shaped material in the imported baseline — operator disposition

`apps/qianwangyou/app/MobileFromUtil.txt:23` contains a hard-coded 32-character
value passed as an `apikey` HTTP request header.

Exposure facts, verified before disposition (the value itself is deliberately
not reproduced here):

| Fact | Finding |
|---|---|
| Present in upstream `TERRYYYC/FakeGps-test@285e4ca` | Yes — blob `72930df`, 4588 bytes |
| Upstream repository visibility | **PUBLIC** |
| `TERRYYYC/fakexxx` visibility | **PUBLIC** |
| Reachable from any build | **No** — a `.txt` file with no reference from any `.kt`/`.java`/`.gradle*`/`.xml` in either app |
| Other occurrences in the repo | None; this is the only file and the only occurrence |

**This import did not create the exposure.** The value was already committed in a
public upstream repository before `fakexxx` existed. Consequently, deleting it
here would not be remediation: anything that has been in a public repository has
to be treated as disclosed, and the only real remedy is rotation at the service
provider.

**Operator disposition (2026-08-09): option A — no action required; the value is
not a live secret.** Recorded here as the "non-secret evidence" the review asked
for. Two things this record does *not* claim: it is an operator assertion, not an
independent verification against the provider, and it does not certify the value
was never live — only that its current disposition needs no action.

The file therefore stays **byte-identical** to upstream. Redacting it would break
the provenance invariant in §3 (the vendored tree must equal the upstream root
tree) and would require an enumerated exception in
`scripts/check-provenance.sh`, at no security benefit, since the upstream copy
remains public regardless.

If that disposition ever changes, the correct sequence is: rotate at the
provider first, then decide separately whether the repository copy is worth an
explicit provenance exception.

## 7. Change log

| Date | Change | Commit |
|---|---|---|
| 2026-08-09 | Initial import of both baselines at the frozen SHAs | `301da0f`, `5687e31` |
