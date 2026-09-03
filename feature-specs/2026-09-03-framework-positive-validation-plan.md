---
feature_ids: [G2-66, G2-71]
topics: [android, magisk, vector, xsharedpreferences, raw-readback, device-evidence]
doc_kind: plan
created: 2026-09-03
base_commit: e1a00eab462b2d5e9355a51f5fdd4da0f77b7709
status: execution-ready
---

# Framework-positive configuration and raw-readback validation plan

## Goal and bounded finish line

Run the existing production QWY and Auto codexBench applications on one newly created,
owned Android 15 AVD with actual Magisk v30.7 and Vector v2.2 loaded. Establish two
independent facts without touching Moto `ZY22JHW9M4`:

1. QWY production UI publishes configuration A (5 seconds) and B (10 seconds), and a
   genuine different-UID Auto process reads A, hot-reloads B in the same PID, then reads
   B again after a cold process restart.
2. QWY's production System Mock service publishes through the Android GPS and network
   test providers, and QWY's production reader reports a `SAMPLE` from both sources while
   ordinary codexBench self-spoof hooks remain disabled.

This run does **not** establish #66 FULL, continuity, production-fingerprint trust, a public
target API value, Moto behavior, or business completion. The current reader uses a zero
publish anchor in `MockProviderService`; therefore a two-source raw positive may honestly
claim `SAMPLE/enabled/mock/source_elapsed`, but not post-anchor freshness or exact coordinate
equality. If those stronger claims become required, stop and add a separately reviewed
production diagnostic seam rather than substituting a fixture or `dumpsys`.

## Frozen inputs and collision boundary

- Application HEAD: `e1a00eab462b2d5e9355a51f5fdd4da0f77b7709`.
- QWY package: `name.caiyao.fakegps.codexbench`, label `千网游 · codex-bench`.
- Auto package: `com.example.cellrebelauto.codexbench`, label
  `CellRebel Auto · codex-bench`.
- Magisk source: tag v30.7, commit
  `e8a58776f1d7bdf852072ad0baa6eceb9a1e4aac`.
- Magisk APK: official `Magisk-v30.7.apk`, expected SHA-256
  `e0d32d2123532860f97123d927b1bb86c4e08e6fd8a48bfc6b5bee0afae9ebd5`.
- Vector module: official `Vector-v2.2-3080-Debug.zip`, expected SHA-256
  `d0c1bf2ca0f1b32e80eb1979e86316656137ac74dff9fe7d25ab624c83715ad7`.
- New AVD only: `codex_framework_api35`, API 35 Google APIs arm64-v8a userdebug,
  explicit serial `emulator-5580`, no snapshot load or save.
- Preferred ADB server: owned port 5040. If emulator registration on the private server
  cannot be proved, retain the stronger per-command target guard below and use the normal
  server; do not improvise a global `adb kill-server`.

Every state-changing command must first establish all of these facts in its receipt:

- requested serial is exactly `emulator-5580`;
- `emu avd name` is exactly `codex_framework_api35`;
- `ro.boot.qemu=1`, SDK 35, `arm64-v8a`, and `ro.debuggable=1`;
- boot ID is recorded and has not unexpectedly changed;
- `ANDROID_SERIAL=emulator-5580` is exported for Magisk's unqualified internal ADB calls;
- the command contains no Moto serial and does not rely on first-device selection.

Never use `connectedAndroidTest`, `adb kill-server`, `scripts/avd.sh`, `avd_patch.sh`,
`build.py emulator -b`, `build.py ndk`, `sdkmanager`, a Play Store user image, an existing
AVD, or any unqualified installation/removal command. No package in this run shares the
production/debug application IDs already in use on the phone.

## Stateful-object census and transitions

| Object / owner | Initial state | Allowed transitions | End state |
| --- | --- | --- | --- |
| Temporary AVD home / coordinator | absent | create one named AVD; start once on 5580 | stopped; userdata retained only as owned evidence |
| ADB server / coordinator | no owned server | start 5040 if isolated registration is provable | stop owned server only; never stop global server |
| Live Magisk tmpfs / guest | absent | official live setup; reinstall live setup after Vector | gone when AVD stops |
| Magisk database / guest | fresh | set only `zygisk=1` | retained inside owned AVD |
| Vector module / guest | absent | official CLI install; load; exact scope; optional disable | disabled or AVD stopped |
| Vector scope / guest | absent | QWY codexBench self + Auto codexBench only | captured, then disabled/stopped |
| QWY profile/config / app | fresh | production UI creates profile; A=5; B=10; System Mock on/off | System Mock off; app stopped/cleared if safe |
| Auto process / app | absent | cold start A; same-PID hot B; force-stop; new-PID cold B | stopped |
| GPS/network test providers / system | native/null baseline | QWY production service adds/emits/removes | native/null boundary rechecked |
| Logs/receipts / host | absent | append phase-bound records with PID/UID/boot/time | preserved outside AVD with hashes |

## Execution phases

### 0. Source and artifact gate

Record release URLs, Git commit IDs, asset sizes and SHA-256 values. Inspect the pinned
Magisk `build.py` and `scripts/live_setup.sh`, plus Vector `module.prop`, installer,
service script and CLI behavior. Abort before AVD mutation if a hash, commit, ABI, package,
signer or source expectation differs.

Inspect both codexBench APKs with `aapt2`/`apksigner`; require the frozen IDs and labels,
matching expected signer, and hashes from the combined evidence report. If a rebuild is
needed, rebuild both from the frozen HEAD and update the artifact table before installation.

### 1. Create and bind the owned AVD

Create a new temporary AVD home from the already installed API 35 Google APIs arm64-v8a
image. Assert ports 5580/5581 and 5040 are unused. Start headless with an explicit port,
no audio and no snapshots. Capture AVD name, serial, build fingerprint, boot ID, uptime,
package baseline, location providers and all connected-device inventories before mutation.

The default ADB inventory may still show Moto. That is acceptable only when every mutating
call is explicit and Magisk receives the frozen `ANDROID_SERIAL`; any ambiguous target,
unexpected emulator, or changed AVD name is an immediate stop.

### 2. Establish live Magisk and Vector

Use only pinned Magisk `build.py emulator <official APK>`. It extracts BusyBox from the APK,
pushes the official live script and APK, stops/restarts zygote, mounts live Magisk, applies
live SELinux policy and runs Magisk's startup stages. This is guest-global disruption and is
permitted only because the AVD is new and owned.

Prove Magisk version 30.7. Set only the Magisk `zygisk` setting, install the verified Vector
zip with `magisk --install-module`, then run the same live setup again. A normal guest reboot
is forbidden here because live Magisk is not in the boot image and would disappear.

Framework readiness requires all of the following, not merely module files:

- Magisk CLI version/code match v30.7;
- Vector `module.prop` is `zygisk_vector`, v2.2/3080 and commit marker `88f8e1f`;
- neither `disable` nor `remove` marker exists;
- Vector CLI connects to its daemon and reports status/modules;
- runtime logs show Zygisk module load and daemon startup in the current boot.

### 3. Install the isolated apps and set exact scope

Install only the two frozen codexBench APKs. Record installed paths, package labels, UID,
signer and installed-byte hashes. Require distinct UIDs and require both to differ from any
debug/release application ID.

Enable module `name.caiyao.fakegps.codexbench`. Set its scope exactly to:

- `name.caiyao.fakegps.codexbench/0` so Vector supplies the module writer's New-XSP path;
- `com.example.cellrebelauto.codexbench/0` as the genuine different-UID reader.

Do not add `system` in this narrow run. Verify scope through the supported Vector CLI, then
force-stop both apps so the next process births consume the final scope. Do not edit Vector's
private database directly.

### 4. Production A-to-B cross-UID proof

Use QWY's real launcher and production Compose UI. Create/save one non-sensitive test profile
through the UI. Treat this setup publication as neither A nor B; clear current-boot logs and
add a phase marker only after it succeeds.

For A, choose refresh interval 5 seconds in the production Settings UI. Require one QWY PID's
writer receipt with `published=true`, `readable=true`, `transportAccepted=true`, `commit=true`,
`outcomeDurable=true` and fingerprint A. Then first-launch Auto and freeze its PID/UID/cmdline.
In that exact different-UID PID require accepted fingerprint A, loaded configuration for the
exact Auto package and `scheduler_owned ... intervalMs=5000`.

For hot B, keep the Auto PID alive and choose 10 seconds in the QWY UI. Require a different
writer fingerprint B and the same Auto PID's accepted B plus
`interval_changed ... fromMs=5000 toMs=10000`. Observer-arm failure is not itself a failure
if the old 5-second scheduler genuinely reads B within its heartbeat.

For cold B, prohibit any new QWY publish, force-stop only Auto, prove the old PID is gone,
then relaunch Auto from its real launcher. Require a new PID with the same Auto UID, still
different from QWY, accepting B and owning a 10-second scheduler.

Bind every phase to host UTC, guest uptime, boot ID, PID, UID and process name. If Vector's
aggregate log cannot attribute target lines to the required PID, the cross-UID gate fails.

### 5. Production raw-location proof

After the B cold proof, stop Auto to avoid mixed evidence. Select QWY codexBench as the Android
mock-location app and grant only required runtime permissions. Through QWY's production UI,
enable System Mock for the saved profile. This naturally creates configuration C; do not mix
it with the A/B transport proof.

Require the production mock service to start and emit. Then require one current QWY process,
in one adjacent evaluation, to report both:

- `origin=SERVICE source=gps status=SAMPLE enabled=true mock=true ... failure=none`;
- `origin=SERVICE source=network status=SAMPLE enabled=true mock=true ... failure=none`.

Prefer matching non-null source elapsed times. `dumpsys location` and the Verify screen may be
retained as cross-checks, but neither substitutes for both production-reader diagnostic lines.
The result remains `freshness=UNASSESSED` because the production service passes anchor zero.

### 6. Cleanup and evidence freeze

Use QWY UI to turn System Mock off. Confirm its foreground service is gone and GPS/network
providers return to the native/null boundary; passive/fused cache may remain and must be
reported rather than silently called clean. Stop Auto and QWY. Reset the mock-location AppOp.

Disable Vector with its single verified module marker and rerun live setup only if a live
post-cleanup proof is needed; otherwise stop the owned AVD, which removes the live Magisk
tmpfs boundary. Stop an owned private ADB server only if one was created. Preserve receipts,
hashes and AVD userdata long enough for independent review; never recursively delete shared
SDK, AVD, `/data/adb`, app workspace or global ADB state.

## False-green rejection table

Reject a run that offers only module enabled/scope, module directory presence, writer success,
writer-side reads, file mode/path, same-UID `:hook_verify`, Binder handshake, or an unbound
`transport accepted` line. Also reject A/B if Auto's PID changes during the hot phase, the cold
phase reuses the old PID, UIDs are not recorded, or B is republished before the cold read.

For raw location, reject fixtures/instrumentation, controller `NO_SAMPLE`, service `Running`,
`dumpsys` alone, Verify's single-source fallback, only one provider, stale/cross-boot logs, or
claims of fresh/trusted/exact coordinates unsupported by the diagnostic contract. No result in
this plan may be promoted to #66 FULL or Moto acceptance.

## Review and delivery

The coordinator owns all emulator mutations. Read-only agents may inspect source and evidence,
but no second actor may install, scope, reboot, stop or clean the AVD concurrently. At the end,
one non-author reviewer receives the exact application HEAD, framework/source/artifact hashes,
phase receipts and cleanup records, and returns an explicit scoped verdict. Publish the report
to draft PR #74; do not merge it or close #66/#71 from this run.
