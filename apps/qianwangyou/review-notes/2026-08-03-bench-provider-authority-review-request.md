---
feature_ids:
  - P1-profile-import-csv-excel
topics:
  - review-request
  - android-content-provider
  - active-profile-publication
doc_kind: review-request
created: 2026-08-03
---

# Review Request: bench 保存档案无法发布/激活

Review-Target-ID: fix-bench-provider-authority
Branch: fix/bench-provider-authority
Superseded Review SHA: `8850c4243b1465e2ac4b350ab47559167ed53174`
Exact Review SHA: the PR #9 remote HEAD supplied in the re-review handoff; verify before testing

## What

- Provider authority now derives from the installed variant application id, so `.bench` routes
  reach its own `AppInfoProvider`.
- Compose repository saves and the legacy editor pass the actual saved row id into
  `ConfigPrefsSync` instead of silently publishing Room's oldest row.
- The resolved active row id is stored in the same synchronous commit as the Hook payload, so
  startup/settings publication and process restarts preserve the explicit selection.
- A transient startup query that cannot resolve the durable active row now preserves the last-good
  payload/id and reports publication failure; only an explicit delete may clear a missing row.
- Added Red→Green unit contracts, variant-route instrumentation, a selected-row repository test and
  an integration test for the transient-missing-row path.

## Why

The co-creator reproduced a serious acceptance bug: after importing a profile, tapping save did not
make it effective. The first blocker was a variant authority mismatch; repairing it exposed the
second and user-visible root cause—publication always queried `ORDER BY id ASC`. This hotfix closes
both ownership gaps without changing import's safe “never auto-publish” behavior. The first review
then reproduced a one-shot empty startup query: nullable publication semantics treated that
temporary miss like a user deletion and erased the durable selection. The P2 follow-up gives
explicit deletion its own typed policy.

## Original Requirements

> “这里导入档案后点击保存是不生效的，这里有严重bug，检查好让sol给我解决。”
> “记住merge让我来确认后再执行。”
> Import only adds favorites; a later explicit editor save establishes the new publication fact.

- Sources: co-creator message `0001785712656776-001412-be90784a`,
  `review-notes/2026-08-02-profile-import-csv-excel-review-request.md:54`, and
  `docs/bug-report/bench-provider-authority/bug-report.md`.
- **Please judge the candidate against the operator experience above, not merely against the first
  authority finding.**

## Tradeoff

Rejected dual production/bench authority matching because it would blur the P0 package/data
boundary. Rejected primary-key reordering or copying the chosen row because active identity is
routing state, not row order. Rejected retries, arbitrary delay and oldest-row fallback for a
temporarily missing durable id. Kept only one compatibility behavior: installations with no
recorded active id publish the legacy oldest row once and atomically record the resolved id.

## Architecture Ownership

Architecture cell: profile config → `ConfigPrefsSync` published payload → Hook reader
Map delta: none
Why: the existing publisher remains the sole owner; this change makes its variant and selected-row
inputs explicit and adds no Store, Queue, Router, Adapter, Dispatcher or Binding.

Please check that the diff matches `Map delta: none` and does not introduce a parallel state owner.

## Open Questions

### Technical OQ

1. Does committing `active_profile_id` beside the payload correctly prevent post-save restart
   rollback while ensuring a MODE_PRIVATE fallback never advances the selected row?
2. Does a missing persisted id preserve the last-good payload/id for ordinary startup publication,
   while an explicit repository deletion still clears it without activating an arbitrary row?
3. Do both UI write paths and all parameterless startup/settings call sites now preserve the exact
   intended profile?
4. Please independently verify on an isolated AVD that the final behavior is not an oldest-row
   false positive. Do not run `connectedDebugAndroidTest` on the physical device: AGP uninstalls the
   `.bench` package and clears its isolated data after the suite.

### Value OQ

None. Merge authority remains explicitly with the co-creator.

## Next Action

DeepSeek Flash should re-review and independently test exact remote HEAD, post an APPROVE or
REQUEST-CHANGES verdict with P1/P2 findings as a PR #9 comment, and stop before merge. The previous
`8850c42` verdict is evidence for that old SHA only. If approved, return the ball to Sol/co-creator;
do not merge and do not modify code.

## Review Sandbox

- Path: `/tmp/cat-cafe-review/fix-bench-provider-authority/deepseek-flash`
- Start command: create a detached checkout of `origin/fix/bench-provider-authority`; this Android
  repository has no web server or `pnpm review:start` entry.
- Ports: `web=N/A`, `api=N/A`.
- SDK bootstrap: copy/create `local.properties` with the local Android SDK path; use Android Studio
  JBR 17+ as `JAVA_HOME`.

## Self-check evidence

### Spec compliance

`review-notes/2026-08-03-bench-provider-authority-quality-gate.md` records the original journey,
root-cause/failure-mode audit, production isolation, final device acceptance and all gates. Author
evidence is review-ready but deliberately not self-approved because this is a P0 hotfix.

### Test results

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app:testDebugUnitTest :app:assembleDebug \
  :app:compileDebugAndroidTestKotlin --rerun-tasks --console=plain
# BUILD SUCCESSFUL; JVM 357/357, 0 failures/errors/skips

ANDROID_SERIAL=emulator-5554 JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app:connectedDebugAndroidTest --rerun-tasks --console=plain
# BUILD SUCCESSFUL; 7/7 instrumentation tests

JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app:assembleRelease :app:lintVitalRelease --rerun-tasks --console=plain
# BUILD SUCCESSFUL; R8 + shipping lint green

python3 -m unittest discover -s scripts -p 'test*.py'
# 50/50 passed

bash -n scripts/test-hook.sh
bash -n scripts/mock_provider_acceptance.sh
git diff --check
# passed
```

Debug lint remains the exact pristine-base result (20 errors / 158 warnings; first failure
`HookProbe.kt:117`); no candidate delta. Root media gates are empty, and the repository has no
`designs/` tree.

### Related docs

- Bug report: `docs/bug-report/bench-provider-authority/bug-report.md`
- Quality gate: `review-notes/2026-08-03-bench-provider-authority-quality-gate.md`
- Feature review contract: `review-notes/2026-08-02-profile-import-csv-excel-review-request.md`

---

*[砚砚/GPT-5.6-Sol🐾]*
