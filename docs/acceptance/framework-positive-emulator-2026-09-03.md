---
feature_ids: [G2-66, G2-71]
topics: [android, magisk, vector, config-transport, system-mock, acceptance]
doc_kind: acceptance
created: 2026-09-03
status: independent-review-pending
application_code_head: e1a00eab462b2d5e9355a51f5fdd4da0f77b7709
---

# Framework-positive emulator validation — 2026-09-03

## Result

The bounded framework-positive run **passes** on a new owned Android 15 emulator:

1. QWY codexBench published A=5 seconds through its production UI; genuine different-UID
   Auto codexBench read A and owned a 5-second scheduler.
2. QWY published B=10 seconds; the same Auto PID hot-reloaded B and changed its scheduler
   from 5 to 10 seconds.
3. Without another QWY publish, force-stopping Auto and launching it again produced a new
   PID that read B and owned a 10-second scheduler.
4. QWY's production System Mock service started from the production UI. Its production
   reader returned adjacent `SAMPLE/enabled=true/mock=true` results for both GPS and network.
5. Cleanup returned QWY to Hook mode, removed the mock providers, reset authorization,
   disabled Vector and destroyed the dedicated AVD after a residual-scope cleanup fault.

This closes the exact emulator experiment planned in
[the execution plan](../../feature-specs/2026-09-03-framework-positive-validation-plan.md).
It does **not** establish Moto `ZY22JHW9M4`, #66 FULL/continuity, post-anchor freshness,
trusted/exact coordinates, or a spoofed public API observation in Auto.

## Frozen environment

| Item | Frozen value / observed result |
| --- | --- |
| Application code | `e1a00eab462b2d5e9355a51f5fdd4da0f77b7709` |
| AVD | `codex_framework_api35`, API 35 Google APIs arm64-v8a userdebug |
| Device route | `emulator-5580`, private ADB server port 5040 |
| Boot ID for all acceptance phases | `7c78851c-bd41-4221-9bc4-272e87542dd0` |
| Magisk | v30.7 / code 30700, source `e8a58776f1d7bdf852072ad0baa6eceb9a1e4aac` |
| Vector | v2.2 / 3080 / API 102, module commit marker `88f8e1fa` |
| QWY | `name.caiyao.fakegps.codexbench`, UID 10208, label `千网游 · codex-bench` |
| Auto | `com.example.cellrebelauto.codexbench`, UID 10209, label `CellRebel Auto · codex-bench` |

The locally built and guest-installed APK bytes matched exactly:

| APK | SHA-256 |
| --- | --- |
| QWY codexBench | `208898ce2bad69f735dab0883f0e591413b9038b30d5d98d3ec1e88ca9e55075` |
| Auto codexBench | `304efe65e27f246c55cd2d36e5a5ce148acbe0484d7dec2732de65980cd1e9f1` |

Both are the non-colliding codexBench identities requested by the user and use the already
recorded signer SHA-256 `7a598cbe6fb816ba74f01b58e3f43b8ff0f463989157e590ebd86c89b53f7e41`.
Vector's live CLI reported framework success, one enabled module and exactly those two scope
entries. Module files alone were not accepted as framework proof.
The live setup also logged a missing `magisk32` helper. Both target processes followed the
observed arm64 path successfully; this run must not be generalized to a 32-bit target.

## A → hot B → cold B configuration proof

| Phase | Writer | Required target-owned observation | Verdict |
| --- | --- | --- | --- |
| A=5 | QWY PID 10461, fp `a3a533ccaa78289f`, all five durability booleans true | Auto PID 11468 / UID 10209 accepted A, loaded exact package, scheduler 5000 ms, later saw unchanged A | PASS |
| hot B=10 | QWY PID 10461, fp `84132f3abcb368be`, all five durability booleans true | Same Auto PID 11468 accepted B, `interval_changed 5000→10000`, later saw unchanged B | PASS |
| cold B=10 | no QWY publish after log boundary | old PID 11468 gone; new PID 11919 / same UID 10209 accepted B, scheduler 10000 ms, later saw unchanged B | PASS |

The hot reader observed the preferences file missing for 3 ms during atomic replacement,
kept last-known-good, and then accepted B. That is the designed safe transition, not a hidden
failure. The cold receipt contains zero `ConfigPrefsSync` lines, two B fingerprint lines and
zero A fingerprint lines, preventing a republish or stale-log false green.

Turning on System Mock later published a separate configuration fingerprint
`3332aada1694f5df`. That happened only after the cold-B receipt was frozen, so it is deliberately
excluded from the transport table rather than being mistaken for another B write.

This proves the actual New-XSP cross-UID transport and refresh ownership. It does not by itself
prove that an Auto public API call returns a spoofed field value.

## Production dual-source raw readback

Android's development settings selected `千网游 · codex-bench` as the mock-location app.
Required location and notification permissions were granted only for this phase. The QWY
production Settings switch changed from unchecked Hook mode to checked
`System Mock 运行中 · 18 次`.

The decisive adjacent pair came from QWY PID 10461 / reader thread 14206:

```text
origin=SERVICE source=gps status=SAMPLE enabled=true mock=true source_elapsed_ms=1066506 publish_anchor_ms=0 freshness=UNASSESSED failure=none reader_failure=none
origin=SERVICE source=network status=SAMPLE enabled=true mock=true source_elapsed_ms=1066506 publish_anchor_ms=0 freshness=UNASSESSED failure=none reader_failure=none
```

`dumpsys location` independently showed both GPS and network as mock providers owned by
UID 10208 / `name.caiyao.fakegps.codexbench`. It is supporting evidence, not a substitute
for the production-reader pair.

Because `MockProviderService` currently calls the diagnostic reader with publish anchor 0,
the only honest freshness result is `UNASSESSED`. No exact coordinate is included in the
portable evidence and no fresh/trusted/exact claim is made. If that stronger fact is required,
the next code slice should propagate the real publication elapsed-time anchor and add a
separately reviewed assertion; it must not reinterpret this run.

## Self-hook isolation observation

Vector did load the legacy module and `MainHook` into QWY PID 10461 to provide module-writer
plumbing. In the same Vector log, that QWY PID emitted zero generic spoof-hook registration
lines, while Auto PID 11468 emitted 16 and explicitly registered location hooks and its
scheduler. This runtime contrast corroborates the branch's tested codexBench self-isolation
policy. The bounded statement is not “QWY contains no framework hooks.”

## Problems encountered and resolutions

1. The host default Python 3.9 failed to parse Magisk v30.7 `build.py` at a nested f-string,
   before any ADB action. The pinned source was not patched; the official script completed
   with the bundled Python 3.12.13 runtime.
2. QWY's “选择模拟位置 App” standard intent appeared to do nothing because this fresh AVD's
   developer-options master setting was absent and Android immediately finished the page.
   Enabling developer options on the owned AVD allowed the standard Settings search/result
   and system app picker to select the exact codexBench package.
3. Vector v2.2's `scope set MODULE` empty-list form threw
   `NullPointerException ... parameter apps`. The module was already disabled and the mock
   state cleared. When the private ADB transport subsequently stayed offline, the dedicated
   AVD was normally stopped and then deleted, removing the residual inert scope and all guest
   test state. The evidence does not assign an unproved cause to the ADB offline event.

No Moto command, global ADB kill, existing AVD mutation, Play image, shared app ID or system
scope was used.

## Cleanup boundary

- QWY production UI emitted `event=stop-and-use-hook ... state=Idle`; the service list was empty.
- GPS and network mock overrides were removed; their native identities and null last-location
  boundary returned.
- Passive and fused caches still contained the earlier mock sample before AVD destruction;
  this is explicitly not presented as a globally empty location cache.
- Mock-location AppOp independently read back `default`. Fine/coarse/notification revoke
  commands and deletion of the developer-options setting each exited 0; ADB became unavailable
  before a separate terminal permission/settings dump.
- Vector module status independently read back `disabled`. The Zygisk disable-marker command
  and both app force-stop commands exited 0; there was no later guest runtime/PID readback.
- Authenticated emulator console verified the exact owned AVD name/path and shut it down.
  The dedicated AVD directory/INI were deleted; processes and owned ports 5040/5580/5581 were
  absent, and the default ADB inventory was empty at the final host boundary.

Deletion is irreversible, but only the newly created temporary AVD was removed; it can be
recreated from the pinned plan. Full receipts remain outside the AVD.

## Evidence and handoff

Portable evidence: [manifest and extracts](evidence/framework-positive-emulator-2026-09-03/README.md).
Unmodified local receipts: `/tmp/fakexxx-framework.wofmpO/receipts/`.

Independent non-author review is required before this report changes from review-pending.
After approval, publish the report and review to draft PR #74. Keep the PR draft and do not
merge or close #66/#71 from this emulator result.

Recommended next action: add the minimal real publication-anchor propagation needed for a
fresh dual-source assertion, independently review it, then run the separately authorized Moto
`ZY22JHW9M4` #66 acceptance with the same non-colliding APKs. Do not repeat the already-passed
A/B transport run unless code or framework inputs change.
