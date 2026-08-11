---
feature_ids:
  - F001
topics:
  - android
  - mock-location
  - review
doc_kind: review-request
created: 2026-08-03
---

# Review Request: Google Mock Location v2 — System Provider Lab

Review-Target-ID: `mock-location-v2`
Branch: `feat/mock-location-v2`
Author: Codex Sol
Named reviewer/tester: Kimi

## What

Add a rebuildable `mockProvider` build variant derived from FakeGPS master. It
coinstalls as `name.caiyao.fakegps.mockprovider`, removes Xposed metadata from
that APK, owns a pure lifecycle controller plus a non-sticky foreground service,
and replaces the Android system `gps` test provider with fresh complete mock
locations. A fail-safe device harness drives the real UI, observes Google Maps,
then restores and launches the operator's original Fake GPS Location app.
The branch also integrates latest master `5dab712`, where upstream debug is the
independent `name.caiyao.fakegps.bench` package and release remains unchanged.

## Why

The prior GMS hook target depended on absent/abstract/obfuscated internals. The
operator's intended方案 C is Android's supported Mock Provider mechanism. The
experiment must be reproducible without repackaging the third-party reference,
without copying user data, and without replacing either installed original app.

## Original Requirements

> 1. “系统 Mock Provider…当前手机里 Fake GPS Location 的 app 就是使用的这个，Sol 在开发和设计的时候好好分析一下。”
> 2. “必须备份当前的 app 到 coding 里的 backup 文件夹；测试 app 基于原始版本、单独命名，并保留原始 app 正常使用。”
> 3. “Kimi 做 review 和测试；merge 时 Opus 再参与，Opus 不参与代码开发。”

- Source: feature-thread anchor `0001785705102956-001269-e38359d9`
- Normalized plan: `feature-specs/2026-08-03-mock-location-v2.md`
- Please judge the diff against these requirements, not only the unit tests.

## Tradeoff

- Chosen: system `gps` test provider, because Maps actually requests it and it
  remains stable across GMS updates.
- Rejected: GMS/Xposed internal hooks, custom provider names, mock-marker
  hiding, third-party APK repackaging, copied app data, and a second module.
- Cost: only one package can own Android's mock app-op at a time, so acceptance
  temporarily switches authority and must restore it on every exit.

## Architecture Ownership

Architecture cell: Android application / no ownership-cell registry exists in this repository
Map delta: none
Why: the Lab is an isolated build variant inside the existing `:app` boundary;
its Activity, Service, controller and gateway do not create a parallel product
or shared persistence boundary.

Please verify that the diff matches `Map delta: none`, especially the new
`MockProviderStatusStore` and `MockProviderGateway`: they are variant-local
lifecycle/port objects, not shared architecture cells.

## Open Questions

### Technical OQ

1. Is use of inlined `ProviderProperties` constants on the API 24–30 deprecated
   overload correct and verifier-safe across the requested SDK matrix?
2. Are remove-before-add, failed-start cleanup, null restart, repeated start,
   repeated stop, and force-stop recovery complete enough to prevent a stale
   system provider?
3. Does the manifest/source-set arrangement guarantee the Lab cannot become a
   second Xposed module or collide with the original provider authority?
4. Does the acceptance trap preserve the original app in every command-failure
   position, including UI lookup and Maps launch failures?

### Value OQ

None. Product direction and reviewer/merge ownership were explicitly set by the
operator.

## Next Action

Kimi should review and test exact pushed HEAD independently, with no author
checkout reuse. Return an explicit APPROVE or REQUEST-CHANGES verdict that names
the reviewed SHA and includes independent command/device evidence. Do not merge;
Opus joins only after Kimi passes.

## Review Sandbox

- Path: `/tmp/cat-cafe-review/mock-location-v2/kimi`
- Checkout: detached/read-only exact remote SHA
- Start command: Android repo; no web server or ports. Use the commands below.
- Device: explicitly target `adb -s ZY22JHW9M4` because another emulator may be connected.

```bash
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
export ANDROID_HOME='/Users/terry/Library/Android/sdk'
python3 scripts/test_mock_provider_variant.py
./gradlew testDebugUnitTest testMockProviderUnitTest \
  assembleDebug assembleRelease assembleMockProvider --rerun-tasks
./scripts/mock_provider_acceptance.sh ZY22JHW9M4
```

Before the device command, independently confirm the sole allowed mock app is
`com.hopefactory2021.fakegpslocation`. Do not copy/read app data or uninstall
any of the four packages.

## Self-check evidence

### Spec compliance

- Evidence manifest: `docs/acceptance/mock-location-v2-evidence.md`
- Every AC and INV-1 through INV-8 has code/test/device provenance.
- Dogfood moved Maps to `40.7128,-74.0060`, then restored and launched the
  reference app.
- Backup manifest:
  `/Users/terry/Desktop/coding/backup/mock-location-v2-2026-08-03/README.md`

### Tests and builds

```text
python structural contract: 6 tests, 0 failures
testDebugUnitTest: 322 tests, 0 skipped/failures/errors
testMockProviderUnitTest: 337 tests, 0 skipped/failures/errors
assembleDebug + assembleRelease + assembleMockProvider: BUILD SUCCESSFUL
device acceptance: active + Maps + restore phases, exit 0
```

Lint is inherited debt, not hidden: `lintDebug` and `lintMockProvider` both
report the same 20 errors / 158 warnings in unchanged main sources. The Lab
initially added two `WrongConstant` errors; current report has zero issue under
`app/src/mockProvider`.

### Artifact and provenance checks

- Worktree clean before request.
- No media/design artifact in repository root or committed diff.
- Lab APK SHA-256:
  `a98341b9b87420851eba9b32d27ed1f746b0d0cde031a37acd590c83936a2542`.
- Private Maps screenshot and hashes are listed in the evidence manifest; the
  image stays outside Git because it came from the operator's device.

## Related documents

- Plan: `feature-specs/2026-08-03-mock-location-v2.md`
- Evidence: `docs/acceptance/mock-location-v2-evidence.md`
- Debug investigations:
  - `docs/bug-report/mock-provider-harness-foreground-start/bug-report.md`
  - `docs/bug-report/mock-provider-coordinate-extra-type/bug-report.md`
