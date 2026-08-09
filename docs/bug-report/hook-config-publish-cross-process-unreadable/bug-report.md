---
feature_ids: [config-publish, hook-transport]
topics: [xposed, vector, xshared-preferences, cross-process, silent-failure, terminal-acceptance]
doc_kind: bug-report
created: 2026-08-08
status: fixed
fix_pr: 23
fix_branch: fix/hook-config-publish-readability
device: ZY22JHW9M4
build_red: master@1e4a90b289764bc03660c8f7663c66240c80108a (apk_sha256 7c8c032b…b3ff, JBR21)
build_green: fix/hook-config-publish-readability @791f41e (apk_sha256 b3299577…898a7, JBR21)
green_intermediate: "@35eafb7 (apk_sha256 f756bf2…09f5e) — historical, predates the first-upgrade active-loss and cold-read cache-poison fixes; NOT canonical"
---

# Hook config publish reports cross-process success while writing a target-unreadable (0660) prefs mirror

## Summary (bounded)

On device `ZY22JHW9M4` (Android 15, Vector/LSPosed), a **fresh publish from the exact
merged-master build** writes the Vector prefs mirror `spoof_config.xml` with mode **0660
`u0_a386:u0_a386`**. The hooked target (Google Maps, UID 10253) therefore cannot read the
payload, so `MainHook` silently falls back to `PASSTHROUGH` on first load and never spoofs —
while the product's publish path records the write as a **successful cross-process publish**
(`published_at` stamped). This is a **silent failure**: the UI believes the config is live; the
hook is delivering real device data.

This verdict is **bounded to this device / this Vector runtime / this build**. It does NOT claim
the 0660 mirror mode is universal across Vector versions. The *observability defect* (publish
contract does not verify actual readability) is code-level and build-invariant.

## Environment

- Device `ZY22JHW9M4`, Android 15, root=Magisk, hook module=`zygisk_vector` (LSPosed-Bridge).
- Formal package `name.caiyao.fakegps` v3.0.0/vc8, base.apk SHA-256 `7c8c032b…b3ff` = exact
  `1e4a90b` / JBR21.
- Vector mirror: `/data/misc/<uuid>/prefs/name.caiyao.fakegps/spoof_config.xml`.

## Red evidence (observed, not inferred)

Parent dirs are world-traversable (`drwx--x--x` / `drwxr-xr-x`); only the **file** lacks the
`o+r` bit.

Controlled A/B — the **only** changed variable is the mirror file mode:

| mirror mode | Maps UID 10253 read probe | fresh Maps (cold PID) hook log sequence |
|---|---|---|
| **0660** (product publish state, mtime = fresh publish) | `Permission denied` | `Loaded config … location=false`, **no** `transport accepted` |
| **0644** (manual chmod, diagnostic only) | `MAPS-CAN-READ-NOW` | `transport accepted schema=4 fp=sha256:39daec…` → `Loaded config … location=true \| cellRebuild=true` → all hooks registered |

Mirror restored to 0660 after the A/B (chmod is diagnostic, never the product terminal state).

Read-path corollary: `MainHook.loadSnapshot()` reads via `XSharedPreferences`; when the file is
unreadable, `getString(json, null)` returns null → `jsonStr==null` → `keepLastKnownGoodOr(CURRENT)`
→ first launch has no last-good → `PASSTHROUGH`. `Loaded config … location=false` prints in BOTH
the null and the parsed paths; only a successful parse first prints `transport accepted schema=`.
Absence of `transport accepted` is the true "payload not read" signal.

## Root cause (code)

`ConfigPrefsSync.sync()`:
- calls `getSharedPreferences(PREFS_NAME, MODE_WORLD_READABLE)`; in the Vector runtime this does
  NOT throw (Vector suppresses the N+ `SecurityException`), so `worldReadable = true`.
- Vector's mirror is nonetheless written **0660** (no world-readable bit).

`ConfigPublicationContract.isCrossProcessPublishSuccessful(worldReadable, committed) =
worldReadable && committed` — it treats "`MODE_WORLD_READABLE` did not throw" as proof of
cross-process readability. It **never verifies the resulting file is actually readable by the
target UID** (no mode check, no test-read). So a 0660 commit is reported as a successful
cross-process publish and `published_at` is stamped, masking the failure.

## Bounded scope / what this does NOT claim

- Does not claim the 0660 mirror mode reproduces on other Vector builds/devices.
- Does not settle the second device `ZY22J66NX2`'s historical `0660/location=false` (that device
  was never reachable this cycle).
- Coordinate values deliberately omitted; evidence uses the transport fingerprint (a hash) and the
  boolean `location` flag only.

## Fix (applied in PR #23)

- `ConfigPublicationContract`: the publication gate's first input is now the VERIFIED other-read
  state (`crossProcessReadable`), with `isOtherReadable(stMode)` over a `stat` mode. Added a
  fail-closed `PublishState` state machine (`preCommitFailClosed` / `onVerifiedPublish` /
  `onVerifiedFailure`).
- `ConfigPrefsSync`: after committing the payload it calls `File.setReadable(true, false)` on its own
  file and verifies `S_IROTH` via `Os.stat`; publication counts only when the target UID can really
  read it. The durable outcome (`published_at` / `publish_failed` / active pointer) moved to a
  separate private store, written fail-closed — marked failed before the payload commit, flipped to
  success only after verification — so a process death at any interruption point leaves a failure
  marker (never a live timestamp) and never advances the active pointer past the last verified-good
  profile.

## Green (achieved in PR #23, device-validated — no manual/out-of-band chmod)

- TDD: `CrossProcessReadabilityContractTest` (committed-0660 ⇒ not published), `PublicationStateMachineTest`
  (interruption-before-verify ⇒ failure; verified-failure keeps prior active pointer), and the
  `PublicationPendingSeamTest` interruption case, `TransportCachePoisonContractTest` (app-private
  rejection + full durability gate), and the first-upgrade active-pointer-preservation cases. Full
  JVM suite: 552 tests, 0 failures.
- Device (`ZY22JHW9M4`, JBR21 canonical fixed build **code/HEAD `791f41e` + APK `b3299577…898a7`**):
  a fresh **product** publish keeps the mirror world-readable (0664); Maps UID can read; fresh Maps
  emits `transport accepted schema=4` → `Loaded config … location=true | cellRebuild=true`.
- **Changed-fingerprint propagation proof** (defeats stale-byte equality), on `791f41e`: changing a
  non-sensitive setting via the product UI (refresh interval 5→60, a value never previously on this
  device) produced a unique payload `sha256:ef8b9dc1…` (`refreshIntervalSec=60`) that appeared in the
  Vector mirror (fresh mtime) AND in fresh Maps' `transport accepted` with `location=true`, while the
  app-private copy stayed untouched (refresh=30, mtime 03:29, target-unreadable); the private outcome
  store recorded a fresh `published_at` with `publish_failed=false`, `active=8`.
- **Restored terminal**: setting returned 60→5; mirror republished to `refreshIntervalSec=5`, fp
  `sha256:39daec…`, mode 0664; fresh Maps accepted the same fp (logged interval 60000→5000) with
  continued deliveries.
- Historical intermediate (NOT canonical): an earlier `@35eafb7` / `f756bf2…` build with a 5→10 /
  `sha256:93e630c…` run proved the same propagation, but predates the first-upgrade active-loss and
  cold-read cache-poison fixes below, so it is not the fixed build of record.

Follow-up reviews (PR #23 exact-HEAD, Sol) hardened, in order: the process-death window and active-
pointer invariant (state machine above); a SharedPreferences cache-poisoning where a MODE_PRIVATE
read of the transport name silently downgraded the transport to app-private storage — fixed by
acquiring the world-readable transport first, migrating the legacy pointer from that instance,
rejecting an app-private resolved path, gating on every `commit()`, and serializing the transaction;
then two remaining edges — a first-upgrade failure path that dropped the migrated active pointer
(now resolved once and preserved on every failure branch) and a `readPublished` that opened the
transport name MODE_PRIVATE-first (now routed through the world-first `acquireTransport`).
