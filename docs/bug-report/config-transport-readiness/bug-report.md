---
feature_ids: [G2-66, G2-71]
topics: [android, config-transport, shared-preferences, codex-bench, self-hook-isolation]
doc_kind: bug-report
created: 2026-09-03
base_commit: 4192f411b2cf741990041bdf206ce3101be8582f
status: host-verified-runtime-positive-pending
---

# Config transport readiness and codexBench readback isolation

## Diagnosis capsule

| Field | Evidence / decision |
| --- | --- |
| Symptom | The authorized Moto window committed a local payload but returned `published=false readable=false`. A later non-throwing WORLD_READABLE call did not establish framework loading. |
| Evidence | Historical 2026-09-02 Moto logs and LSPosed DB snapshot, #71 emulator evidence, main `a635f459`, integration `4192f41`, official AOSP/LSPosed source below. No new device run in this lane. |
| Root cause | Historical writer fell back to private prefs while the new module was disabled and had zero scope. Separately, DEBUG permits the configuration process to install spoof hooks, which could contaminate subsequent raw readback when the module is loaded. |
| Strategy | Trace the real file and ContextImpl cache; keep correct fail-closed writer semantics; prove build-specific hook exclusion before enabling a framework validation path. |
| Time bound | Initial read-only investigation bounded to 20 minutes; runtime-positive proof is a separate supervised environment gate, not repeated permissions experiments. |
| Warning | A local commit, mode call, file mode or self-process read is not another UID's read receipt. No private-directory chmod, exported provider, disabled security check or weakened FULL requirement. |
| User effect | codexBench configuration/ordinary self processes become unhooked by this module; observation scope follows that same policy. Ordinary debug's controlled self-hook and the exact private `:hook_verify` process remain available. |
| Acceptance | Actual debug/codexBench/release tests, production wiring guard, mutation checks, real Android cache negative and a separately pending framework/cross-UID positive recipe. |

## 1. Reporter and scope

Codex found this during the operator-authorized Moto `ZY22JHW9M4` test window and
re-grounded it on 2026-09-03. The original phone goal remains a working application,
not a green diagnostic alone. This lane implements a safe verification prerequisite;
it does **not** establish actual cross-UID publication, raw-location success or #66 FULL.

The repository root is `fakexxx`, not the outer `codex-fakexxx` directory. Main and
the #72 integration base were compared. `ConfigPrefsSync` differs only by the 38-line
oracle semantic writer/identity additions, which this work does not modify.

## 2. Reproduction and historical evidence

The original two codexBench APKs and recorded device process identities are in
[Moto acceptance](../../acceptance/codex-bench-runtime-2026-09-02-ZY22.md).
These are historical observations; no statement here asserts a current phone process
is stale or that a recent APK is installed. This lane does not query a live device.

Local original evidence: `/tmp/fakexxx-moto-codex-run.WVPYZG/`.

- `new-qwy-firstlog.stdout:26-30`: process 20535 first WORLD_READABLE request throws
  `SecurityException: MODE_WORLD_READABLE no longer supported`.
- Same file line 58: `transportAccepted=false commit=true published=false`.
- `new-qwy-seed-log.stdout:111-122`: same process later reports `transportAccepted=true`
  but resolves `/data/user/0/name.caiyao.fakegps.codexbench/shared_prefs/spoof_config.xml`;
  `published=false readable=false` remains correct.
- `new-stop-final-log.stdout:146-152`: process 21035 also builds the actual profile fields,
  commits locally, rejects the app-private backing and reports Hook publication failure.
- `new-qwy-firstlog.meta` binds the capture to serial `ZY22JHW9M4`, UTC start/end,
  `logcat --pid 20535`, and exit 0. APK hashes/source are linked in the acceptance report.

Read-only re-query of the historical derived LSPosed snapshot (using immutable SQLite,
not the running phone database) produced:

| Module | enabled | scope rows |
| --- | ---: | ---: |
| `name.caiyao.fakegps` | 0 | 7 |
| `name.caiyao.fakegps.bench` | 1 | 9 |
| `name.caiyao.fakegps.codexbench` | 0 | 0 |

After the independent Binder identity fix, the stock emulator also rejected
WORLD_READABLE and retained private backing. That is an unmet framework prerequisite,
not evidence that the identity repair failed. See
[#71 emulator acceptance](../../acceptance/issue71-binder-identity-emulator.md).

Temporary raw evidence is local and may expire. Do not upload phone databases,
personal location payloads or arbitrary logs when handing this report to another team.

## 3. Confirmed call chain and exclusions

Writer: `QwyEnvironmentController -> ConfigPrefsSync.sync -> acquireTransport ->
Context.getSharedPreferences -> private fallback -> commit -> backing-path/mode check`.

Reader: `MainHook.loadSnapshot -> XSharedPreferences(APPLICATION_ID, spoof_config) ->
reload -> json -> fingerprint/schema/snapshot acceptance`.

Android 15 caches both a preference name's path and the SharedPreferences instance.
`checkMode(mode)` runs only while constructing an uncached instance. After a private
fallback is cached, a subsequent WORLD_READABLE call can return it without checking
that mode again. The actual app-private-path guard is therefore necessary, and is retained.
[AOSP Android 15 ContextImpl source](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/app/ContextImpl.java#L545)

The manifest already declares `xposedsharedprefs=true`. LSPosed documents a framework
hook that redirects the preference directory and permits WORLD_READABLE; the reader
uses the package/name constructor because the framework path is not a fixed directory.
Metadata alone does not establish that those hooks loaded in the executing processes.
[LSPosed New XSharedPreferences](https://github.com/LSPosed/LSPosed/wiki/New-XSharedPreferences)

The inspected official LSPosed commit is `df74d83eb03a44cc6ad268841ac2ada28d077c77`.
Its `hookNewXSP` runs before calling module load-package callbacks. Thus allowing the
framework's configuration transport need not imply installing this module's own spoof
hooks in its configuration process. The exact LSPosed/Vector build installed on Moto
was **not identified** in this investigation; source inspection is not a claim of that
device's binary behavior or automatic self-scope selection.
[Official hook dispatch](https://github.com/LSPosed/LSPosed/blob/df74d83eb03a44cc6ad268841ac2ada28d077c77/core/src/main/java/org/lsposed/lspd/hooker/LoadedApkCreateCLHooker.java)

No new ConfigPrefsSync write/read algorithm fault is established. Its private-path and
durable-outcome failure checks prevent false success. The independent safety defect is
using DEBUG as the self-hook policy: codexBench inherits DEBUG, so its own load-package
callback would install spoof hooks before the intended raw LocationManager readback.
Scope UI appearance cannot substitute for proving the absence of those hooks.

## 4. Selected implementation and tradeoffs

Use an immutable per-build `ALLOW_NON_PROBE_SELF_HOOK` setting: ordinary debug true,
codexBench/release false. Production hook dispatch and configuration observation scope
consume the same build-specific policy. The exact private `:hook_verify` process and
scoped external targets retain their intended eligibility. No runtime switch or store
is added. The existing system-server oracle early branch is unchanged.

Do not change ConfigPrefsSync, export its provider, widen private directories, guess
framework paths, relax publication success, alter frozen ContractV1, add production
system fingerprints, or treat an intentional hooked probe as independent raw evidence.

The new Android negative test uses real SharedPreferences and real ContextImpl caches,
with unique fixture preference names. Its publisher case calls the actual ConfigPrefsSync
and private Provider. Only preference names are remapped to keep unrelated test stores
untouched; no cache field, fake preference implementation or synthetic framework transport
is injected. It verifies local payload A -> B while both publishes remain false and the
failure outcome stays durable. It is not a positive cross-UID test.

## 5. Validation status

Implementation/test evidence is recorded in the
[lane acceptance report](../../acceptance/config-transport-isolation-2026-09-03.md). New
Android instrumentation is compiled here; execution, if performed by the coordinating
thread, must have its own explicit emulator serial and independently checked receipts.
No device test should auto-select attached devices with `connectedAndroidTest`.

Enabling the previously absent release unit-test task exposed four existing tests in
the shared `src/test` tree that depend on `src/debug` classes. The coordinator approved
moving those four files, byte-for-byte unchanged, into `src/testDebug`, and reusing that
source directory in codexBench tests. This is source-set correction, not deleting failing
release behavior assertions. Both debug-backed variants execute all the same 1,035 cases;
release executes its 999 applicable cases. The initial release compilation error is not
counted as behavioral RED. See the acceptance report for names, counts and mutations.

## Pending positive framework recipe

1. Freeze exact APK hashes/signers/package IDs, framework build/source compatibility,
   user ID and scope. Use an owned isolated emulator when possible; a Moto run needs
   the separately coordinated device window and original-app recovery checks.
2. Load the framework preference support in a fresh writer process and confirm the
   actual backing file is the framework-provisioned path. Do not hard-code/random-guess
   paths and do not use chmod on an app-private directory.
3. Prove codexBench main/ordinary self processes do not install this module's generic
   spoof hooks. Verify the exact `:hook_verify` exception separately. Keep independent
   raw readers outside all effective spoof-hook sources, including other active modules.
4. Have a genuine **different UID**, scope-bound XSharedPreferences consumer read a
   fresh unique A payload/fingerprint. Change A -> B and require that consumer to read B;
   cold-restart the consumer and require B again. Capture actual package/UID/process,
   source time and matched fingerprint. Writer-side reads and file mode are not receipts.
5. The private `:hook_verify` process has the writer APK's UID; alone it cannot establish
   different-UID transport. Stock emulator negative tests likewise cannot establish a positive.
6. Only then evaluate independent GPS/network readback with the new diagnostic lane.
   Transport success still does not satisfy #66 continuity/FULL or real business completion.
7. Release leases, remove owned mock state, restore exact scope/settings, verify original
   application usability, stop owned processes and retain sanitized evidence. Do not
   claim all cached location samples cleared without an actual observation.
