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

1. this document records the URL, branch, exact SHA and the fakexxx import commit;
2. the recorded import commit exists and carries the upstream root tree at that prefix;
3. the upstream object is **fetched from the upstream URL** and the committed
   tree object of the prefix equals the upstream commit's root tree;
4. the working tree has not drifted from the verified commit;
5. the entry files exist (`gradlew`, `app/build.gradle*`, `app/src/main/AndroidManifest.xml`).

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

Open disposition for the main implementation Thread: whether PR-3 fixes the 23
errors, or the operator accepts them as a released-baseline condition. Until
that is decided, the ratchet keeps them visible and bounded.

## 7. Change log

| Date | Change | Commit |
|---|---|---|
| 2026-08-09 | Initial import of both baselines at the frozen SHAs | `301da0f`, `5687e31` |
