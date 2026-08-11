---
feature_ids:
  - F001
topics:
  - android
  - mock-location
  - acceptance
  - evidence
doc_kind: quality-gate-report
created: 2026-08-03
---

# Mock Location v2 — Evidence Manifest and Quality Gate

> **Historical Lab evidence.** This manifest proves the experiment behind PR #8; it is not evidence that the feature was integrated into 千网游 or that the old restore harness removed the system provider. Current product evidence is in `mock-location-main-integration-evidence.md`.

## Provenance

- Repository: `https://github.com/TERRYYYC/FakeGps-test.git`
- Branch: `feat/mock-location-v2`
- Initial development base: `c34bf666983da3634dfba5f2b4c3ce0147cde019`
- Latest-master review base: `5dab712ff4119b421076b5034c3fea859ad2b29a`
- Latest-master implementation and acceptance-script HEAD: `2145122`
- Device: moto g54 5G, serial `ZY22JHW9M4`, Android 15
- Lab APK: `app/build/outputs/apk/mockProvider/app-mockProvider.apk`
- Lab APK SHA-256（**构建 JDK 未记录**）:
  `a98341b9b87420851eba9b32d27ed1f746b0d0cde031a37acd590c83936a2542`

> 本条 hash 早于 `docs/bug-report/debug-apk-hash-jdk-drift/bug-report.md` 记录的教训，
> 采集时未绑定 Gradle runtime JDK，且已无法事后确定当时的 JDK——所以这里只标注"未记录"，
> 不倒推、不补填一个看似合理的版本号。它只在原构建环境内有意义，**不可**作为跨环境
> artifact identity 使用；任何比对若得到不同 hash，都不构成"源码不一致"的证据。
> 新证据一律由 `scripts/apk_provenance.py` 产出，该工具在结构上无法输出不带 JDK 的 hash。
- Installed-app backup:
  `/Users/terry/Desktop/coding/backup/mock-location-v2-2026-08-03/README.md`
- Private device screenshot:
  `/Users/terry/Desktop/coding/backup/mock-location-v2-2026-08-03/evidence/maps-2145122.png`
- Screenshot SHA-256:
  `b7e3e6e87118f928cf1933ffa52c282cbb2e20888c9a8e5aa2354737925f8cef`

The screenshot is intentionally outside Git because it came from the
operator's device. It shows the Google Maps blue dot at City Hall, Lower
Manhattan, matching `40.7128,-74.0060`.

## Vision coverage

| # | Operator requirement | Plan/AC | Result |
|---|---|---|---|
| 1 | Analyze whether the installed Fake GPS Location app uses System Mock Provider | Evidence established / finish-line boundary | APK inspection found `addTestProvider("gps")`, enable/publish/remove calls, plus a separate optional GMS mock path. No source was copied. |
| 2 | Back up the current apps before testing | AC 1 | Complete base + split APK backup with SHA-256; no app data copied. |
| 3 | Derive the lab from the original product while preserving the installed original | AC 2–3 | Lab is a build variant of latest FakeGPS master, with a distinct package/label/data/authority; reference, release, debug bench, and Lab all coexist. |
| 4 | Test Google Maps through方案 C, not GMS/Xposed internals | AC 4–6 | System `gps` test provider moved both GPS and fused last locations and the Maps blue dot. |
| 5 | Restore original operation after every test | AC 5 | EXIT trap stops/removes the Lab provider, restores the original app-op, verifies Lab PID absence, and launches the reference app. |
| 6 | Kimi independently reviews/tests; Opus joins only at merge | Task 6 | Author gate prepares exact-HEAD handoff to Kimi; no merge is attempted here. |

## Functional acceptance

| Requirement / invariant | Evidence |
|---|---|
| INV-1 isolated identity | APK package `name.caiyao.fakegps.mockprovider`, label `FakeGPS Mock Provider Lab`, authority `name.caiyao.fakegps.mockprovider.data.AppInfoProvider`. |
| INV-2 original compatibility | Latest master intentionally keeps release as `name.caiyao.fakegps` and installs debug as `name.caiyao.fakegps.bench`; both build alongside the Lab. |
| INV-3 no second Xposed module | Lab manifest has no `xposedmodule` metadata; original debug manifest retains it. |
| INV-4 complete fresh samples | Unit tests cover sample completeness; device emitted one sample per second with time, elapsed realtime, altitude, accuracy, speed and bearing fields. |
| INV-5 idempotent cleanup | Controller tests cover repeated stop and failed-start cleanup; device script restores on every EXIT. |
| INV-6 no crash resurrection | Service is `START_NOT_STICKY`; null restart intent decodes to stop; restored Lab PID is absent. |
| INV-7 explicit mock marker | `dumpsys location` reported both `Location[gps ... mock]` and `Location[fused ... mock]`. |
| INV-8 original restored | Final app-op output was only `com.hopefactory2021.fakegpslocation`; resumed activity was `com.hopefactory2021.fakegpslocation/com.adevinta.leku.LocationPickerActivity`. |

## Device dogfood

Scope verdict: required and complete.

End-to-end path:

```text
install coexisting Lab APK
→ grant Lab location permission
→ temporarily select Lab as android:mock_location app
→ launch Lab Activity and tap Start
→ publish system gps test locations at 1 s cadence
→ launch Google Maps
→ observe blue dot at City Hall / Lower Manhattan
→ tap Stop, force-stop Lab, deny Lab app-op
→ restore original app-op and launch Fake GPS Location
```

Key runtime evidence from the successful latest-master `2145122` run:

```text
ACTIVE package=name.caiyao.fakegps.mockprovider pid=5501
MOCK_APP name.caiyao.fakegps.mockprovider
isForeground=true
last location=Location[gps 40.712800,-74.006000 ... hAcc=3.0 ... mock]
last location=Location[fused 40.712800,-74.006000 ... hAcc=3.0 ... mock]
gps provider [mock]
identity=10363/name.caiyao.fakegps.mockprovider
MAPS_FOREGROUND ... com.google.android.apps.maps/com.google.android.maps.MapsActivity
ACCEPTANCE_ACTIVE_PHASE_COMPLETE
RESTORE lab=deny reference=allow status=0
REFERENCE_APP_FOREGROUND ... com.hopefactory2021.fakegpslocation/com.adevinta.leku.LocationPickerActivity
ACCEPTANCE_RESTORE_PHASE_COMPLETE
```

Dogfood defects found and fixed before review:

- Background ADB foreground-service start rejected by Android 12+ → drive the
  real Activity Start/Stop controls (`f9420f3`).
- `head` caused SIGPIPE under `pipefail` during evidence truncation → use
  full-reading filters (`160cb8e`).
- String probing of Double Intent extras emitted framework type warnings →
  decode the declared type (`f41cac5`).
- Modern constant annotations on the legacy overload produced two new lint
  errors → use inlined `ProviderProperties` constants with a targeted API
  annotation (`be58a68`).
- Restore proved app-op state but not original usability → launch and verify the
  restored reference app (`8a5255d`).
- Master advanced during development with an independent `.bench` debug package
  → merge `5dab712`, verify all four identities, and rerun the full/device gate
  (`2145122`).

## Fresh verification

| Command | Result |
|---|---|
| `python3 scripts/test_mock_provider_variant.py` | 6/6 pass |
| `./gradlew testDebugUnitTest --rerun-tasks` | 322 tests, 0 skipped/failures/errors |
| `./gradlew testMockProviderUnitTest --rerun-tasks` | 337 tests, 0 skipped/failures/errors |
| `./gradlew assembleDebug assembleRelease assembleMockProvider --rerun-tasks` | build success |
| `./scripts/mock_provider_acceptance.sh ZY22JHW9M4` | active + Maps + restore phases complete, exit 0 |
| `lintDebug --rerun-tasks` | inherited baseline: 20 errors / 158 warnings |
| `lintMockProvider --rerun-tasks` | same inherited baseline: 20 errors / 158 warnings; 0 error/warning under `app/src/mockProvider` |

The repository already fails lint on unchanged main sources (API guards,
cursor ranges, and translations). The branch initially added two
`WrongConstant` errors; both were fixed before this report. No blanket lint
baseline or unrelated source changes were added.

## Quality-gate auxiliary checks

- Close gate: not applicable; this is review readiness, not feature closure or merge.
- Follow-up-tail scan: no unmet AC deferred by this branch. The explicit
  non-goals are safety/product boundaries, not postponed required work.
- Hotfix/fallback/architecture scripts: not present in this Android repository.
- Manual fallback-layer audit: controller cleanup is a single lifecycle failure
  boundary; best-effort provider removal prevents stale spoofing, and the two
  nullable-message fallbacks only preserve diagnostics. UI `?: return` clauses
  are independent input guards, not layered runtime fallback behavior.
- Architecture ownership: Android application; map delta `none`, because the
  Lab stays inside the existing `:app` boundary with a variant-owned Activity,
  Service, controller and gateway.
- Capability tips: not applicable; this repository has no Cat Café capability-tip surface.
- Pencil/design check: no `.pen` files exist; the small Lab UI is covered by
  device dogfood and is an isolated test surface.
- Artifact hygiene: no media/design artifact was added to the repository root;
  device imagery remains in the local backup directory.

## Independent-review focus

Kimi should rebuild and test exact HEAD, inspect every changed file, then cover:

1. package/authority/Xposed isolation across debug, release and Lab APKs;
2. API 24–30 legacy registration correctness and API 31+ provider properties;
3. repeated start/stop, invalid input, missing mock app-op, process death, and
   provider cleanup;
4. moto g54 Maps behavior and fail-safe restoration without reading or copying
   any app's data;
5. backup completeness and the fact that neither installed original is
   uninstalled, upgraded, or repackaged.
