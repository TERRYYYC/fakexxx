---
feature_ids: [F001]
topics: [android, mock-location, main-app, profiles, lifecycle, review]
doc_kind: review-request
created: 2026-08-03
---

# Review Request — F001 Mock Location 主 App 集成

Review-Target-ID: `f001-mock-location-main-integration`
Repository: `https://github.com/TERRYYYC/FakeGps-test.git`
PR: `https://github.com/TERRYYYC/FakeGps-test/pull/10`
Assigned reviewer: `@fable5`（只读 review + 验证）
Base: `origin/master` = `ff48173f8bd531571d544293f317f999aa601469`
Branch: `feat/mock-provider-main-integration`
Code commit: `653f4c376a62127663de9bd1ce384df99a3ecad0`
Evidence commit: `83fc70a665a289175b919bd246d9d9b8ddd7c814`

Reviewer must resolve and record the exact remote branch/PR HEAD before review; do not review only the code commit or reuse the author worktree.

## Authorization Boundary

- Fable5 may inspect code, build an independent exact-HEAD checkout, run tests, and perform the isolated moto g54 acceptance.
- Fable5 must **not** edit files, commit, push, resolve findings in code, or merge the PR.
- Any finding returns to Sol for implementation and a new exact-HEAD review cycle.
- PR merge is separately guarded: **only after co-creator explicitly confirms merge** may the merge step execute. Review approval alone is not merge authorization.

## What

- Retire the standalone `mockProvider` Lab build type and move the verified Android System Mock Provider into the main App.
- Add a Settings switch selecting Hook or System Mock for **location only**; cellular/Wi-Fi and other profile fields remain hooked.
- Resolve System Mock coordinates from the same `ConfigPrefsSync` effective profile used by Hook; no second coordinate or active-profile store.
- Treat the durable cleanup flag as an incomplete-transition marker, add rollback/retry Stop semantics, and keep the foreground service alive when the launcher task is removed.
- Use Kyiv `50.4501,30.5234` for defaults and isolated device acceptance.

## Why

The merged Lab proved feasibility but did not deliver a 千网游 product feature. It also exposed a system-server lifecycle gap: an App process can disappear while the system GPS test provider remains. The main-App implementation must make the user's Hook/System Mock intent, effective profile, provider lifecycle, and recovery behavior one reviewable product transition.

Original operator corrections:

1. “你这部分功能你打算怎么合入主app呢？”
2. “做成一个开关，数据则是从主app的档案来获取，即开关来决定是使用hook还是mock”
3. “你现在这个xxx lab app 虚拟位置不能停你发现了吗？”
4. “我建议虚拟地址选择 基辅”

## Tradeoff

- System Mock intentionally preserves `Location.isMock() == true`; this is the controlled-lab path, not the unmarked GMS/Xposed high-fidelity path.
- Cross-process Hook mode propagation remains bounded by the existing 5–60 second target refresh. The selected persistent intent is singular, but handover is not claimed to be instantaneous; any short overlap uses the same effective-profile coordinate.
- API 24–30 provider registration is covered by pure contracts/code review; the available device is Android 15/API 35.
- `lintDebug` retains the repository baseline of 20 errors/148 warnings in unchanged files; release `lintVital` passes and this diff adds no lint error.

## Open Questions

1. Does the transaction-marker ordering cover every crash window without clearing recovery state before mode publication and provider cleanup both succeed?
2. Is Failed → “重试停止” the correct UI recovery, with repeated switch interaction disabled during Starting/Stopping?
3. Does removing `stopWithTask` preserve user intent without introducing an unowned provider session, given the explicit switch and notification Stop actions?
4. Are INV-1's bounded handover semantics truthful and consistent with the existing snapshot refresh contract?

## Next Action

Please perform an independent exact-HEAD code review and moto g54 acceptance. Return `APPROVE` or `REQUEST CHANGES` with file/line findings. Do not edit code and do not merge. Findings go back to Sol; approval waits for co-creator's explicit merge confirmation.

## Required Review Gates

```bash
ANDROID_HOME=/Users/terry/Library/Android/sdk \
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20/libexec/openjdk.jdk/Contents/Home \
./gradlew testDebugUnitTest assembleDebug assembleRelease --rerun-tasks

python3 scripts/test_mock_provider_main_integration.py
bash -n scripts/mock_provider_acceptance.sh
OBSERVE_SECONDS=5 scripts/mock_provider_acceptance.sh ZY22JHW9M4
```

The device run must directly prove:

- debug `.bench` is the selected mock app and gps/fused both equal Kyiv;
- `MockProviderService` is foreground;
- swiping the 千网游 task from Recents does not stop the FGS or Kyiv provider;
- Maps is explicitly recentered and its blue dot is at Kyiv Independence Square;
- UI Stop restores `gps provider` to `1000/android[GnssService]`;
- the reference Fake GPS Location App is restored as the sole mock app.

## Author Evidence

- JVM: 382 tests, 0 failure/error/skipped.
- Debug + release/R8: BUILD SUCCESSFUL; release `lintVital` passed.
- Structural contracts: 6/6; debug merged manifest contains non-exported location FGS with no `stopWithTask`.
- Debug APK SHA-256: `c8a14c2e9a02ba793f33b449479d5e85ba19c87d77dafc19401f112a3facf172`.
- Release APK SHA-256: `ab1ab45f99e4edcf0b2b6d520d3d53f265835ce951a3c0f05c51ee939c3a376c`.
- Exact-code device run: active → task removal → Maps recenter → Stop → restore, exit 0.
- Maps screenshot SHA-256: `471ec56abf6d8d6293b8f126264e8a7f159550583806eb107c37eee303665670`.
- Full provenance: `docs/acceptance/mock-location-main-integration-evidence.md`.

## Insight Truth Sync

Docs branch: `docs/f001-mock-provider-main-integration`
Docs HEAD: `356000bd75a6cdf697212fa24034ac054149a0e4`

Please also check that `docs/features/F001-issue-gms-fused-location-gap.md` remains `in-progress` until the Android PR is merged and post-merge acceptance passes; the Lab-only review request remains explicitly superseded.

[砚砚/gpt-5.6-sol🐾]
