---
feature_ids: []
topics: [readback-diagnostics, config-isolation, independent-review]
doc_kind: review
created: 2026-09-03
---

# Independent review — readback/config isolation

Review-Target-ID: `fakexxx-readback-isolation-20260903`

Reviewer: Codex subagent `/root/readback_isolation_review`, non-author of reviewed changes. Local peer route, iterative engagement. This is not a merge or product acceptance decision.

Base: `4192f411b2cf741990041bdf206ce3101be8582f`

Exact reviewed code HEAD: `6691bcb00305615676ef8d6856561ba3c23d61ab`

Reviewer detached worktree: `/tmp/fakexxx-review.lA1Umk/checkout` (clean at conclusion).

## Verdict

**APPROVE for the scoped readback-diagnostics/config-isolation change. No open P1/P2 findings.** One P2 found during the review was fixed and independently rechecked in code, tests, and the actual codexBench UI. This verdict does not establish Moto readiness, #66 FULL, framework-delivered GPS positive evidence, or LSPosed cross-UID configuration transport success. No merge, issue closure, production fingerprint admission, or device-scope expansion is authorized by this review.

## What / Why / Tradeoff / Open Questions / Next Action

- What: reviewed the complete base-to-HEAD diff, including Lane A, Lane B, root CI changes, and final presentation fix. Checked real production consumers, actual generated build variants, compiled dispatch guard, source isolation, one-snapshot trust evaluation, diagnostic formatter/sink failure behavior, and Android negative receipts.
- Why: the user wants a usable physical-device app with an isolated app identity. This slice improves trustworthy diagnosis and prevents configuration self-hook contamination; it does not substitute narrow tests for the user's end-to-end goal.
- Tradeoff: external ContractV1, ConfigPrefsSync production behavior, and trust semantics remain unchanged. codexBench/release isolate ordinary own processes; debug retains its legacy self-hook route; `:hook_verify` and external-package routes remain eligible. Coordinate-free diagnostics do not certify trusted delivery.
- Open Questions: no unresolved P1/P2 within this diff. Separate product blockers remain framework configuration publication and real trusted GPS/network delivery on the authorized target. The clean AVD has no LSPosed positive proof. Subsequent evidence-only commits require a scoped documentation/provenance check, not an assumption that a later code tree is covered.
- Next Action: publish the tested code and bounded evidence through the root-owned draft/review workflow; preserve fail-closed product claims. Do not claim merged or Moto-verified. Root can reference the reviewer captures below.

## P2 closed: process baseline was mislabeled as physical truth

At `623dac4`, Verify and Editor still described a non-self-hooked process reading as “本机真实值”, and the probe description assumed a release build. This was misleading because system mock location and other modules can affect these readings.

The final shared `ObservationScopePresentation` now supplies all three scope explanations, Verify observed labels, and both Editor reference labels. The sibling passthrough/ambiguous-field copy was swept as well. No value selection or trust predicate changed. Original author commit `25d4338` and final cherry-pick `6691bcb` have identical stable patch ID `0c5a0056aa50650badb2fbfbb53b88e6c11868a0`.

Author RED: `/tmp/fakexxx-scope-copy.RL38JT/red-copy.log`, 9 tests / 5 failures. Final independent targeted rerun: 20 tests / 0 failures, `/tmp/fakexxx-review.lA1Umk/final-copy.log` plus XML under the reviewer worktree's `testCodexBenchUnitTest` results.

Actual UI validation used only `emulator-5584`, verified AVD `codex_readback_clean_api35`, package `name.caiyao.fakegps.codexbench`. Viewed Verify ScopeCard, Editor text reference rows (operator), and Boolean reference row (roaming), with readable wrapping. No profile was saved, no text or selections changed, and no permissions/AppOps/mock state were changed by the reviewer. Returned to Map and explicitly handed the exclusive window back to root.

- `/tmp/fakexxx-review.lA1Umk/verify-final.png`
- `/tmp/fakexxx-review.lA1Umk/editor-text-final.png`
- `/tmp/fakexxx-review.lA1Umk/editor-boolean-final.png`
- `/tmp/fakexxx-review.lA1Umk/returned-map-final.png`

Raw capture metadata, screenshots, and UI hierarchy receipts are in `/tmp/fakexxx-readback-clean.kTIjCq/reviewer_*`. Relevant hierarchy reads: 03, 13, 15, 16. The unconfigured Verify page had no field rows; their wiring is code/test evidence, not a claim of an observed configured field row. The old Verify screenshot `/tmp/fakexxx-readback-clean.kTIjCq/verify-red.png` was independently viewed as the UI RED counterpart.

## Verification provenance

Reviewer-run checks:

- Lane A `ebb531e`: targeted unit 26/26 and actual adapter host 6/6, exit 0 (`lane-a-unit.log`, `lane-a-adapter.log` in reviewer raw directory).
- Initial combined `623dac4`: debug 74/74, codexBench 29/29, release 29/29, exit 0 (`combined-debug.log`, `combined-variants.log`).
- Final `6691bcb`: presentation/Verify/current-variant/self-hook targeted codexBench 20/20, exit 0 (`final-copy.log`).
- Exact final base-to-HEAD `git diff --check` passed; reviewer worktree remained clean.

Independently read author/root raw evidence:

- `/tmp/fakexxx-readback.yazTyt/final-full-gate.log` and `.meta`: exact final HEAD, 12/12 repository gates, exit 0. Its product/device result explicitly remains BLOCKED.
- `final-variants-apks.log/.meta` and `final-junit-counts.log/.meta`: exact final HEAD, full variants 1046/1046/1010, no failures/errors/skips, APK builds successful. The timestamped full-debug count precedes the later host gate, which legitimately overwrites the live debug XML directory with a 53-test targeted subset. Live codexBench/release XML independently recounted at 1046/1010. Do not cite the current live debug directory as a preserved 1046-case report.
- Clean AVD `new-four-final` and `old-nine-final`: explicit serial, AVD checks, exit 0, OK (4 tests) and OK (9 tests). Final logcat PID 4951 shows real permission-denied cache reads classified SECURITY, and real publisher fresh/cached private transport both false; PID 5124/remote 5148 shows different UIDs and production-controller NO_SAMPLE / verification=3. Older runs coexist in the same final logcat buffer; final run claims were matched by time and PID.
- Installed APK hashes match independently recomputed final local APKs: debug `e8ae87dfd729471c253ddac4d1eb1c3f68dda0b7f1559b1cb5b57bf5f98b4b53`; codexBench `208898ce2bad69f735dab0883f0e591413b9038b30d5d98d3ec1e88ca9e55075`; test `acd75ea35830d0e7a7aea969daa1c509c07cafcecf676df21f18ba3e7d066c0e`.

## Cleanup witness

After handing control back, the reviewer performed no further adb. Independently read root's `cleanup-*` records in the clean AVD raw directory:

- Three task packages' `pm clear` returned Success; mock AppOp returned default; coarse/fine permissions false.
- Target services `(nothing)`; process snapshot contains no fakegps process.
- GPS/network last location null with native identities restored.
- Passive/fused still contained a synthetic mock fixture cache. **This is not an all-location-cache-cleared claim.** The owned AVD was stopped as the final isolation boundary.
- Stop receipt verified the exact AVD and returned exit 0 / “killing emulator”; emulator log ended with graceful shutdown/removeAll. Read-only host process check found owned PID 47575 absent.

The earlier 5582 userdata anomaly was not used as a clean baseline and was not attributed to another process without proof. Review approval depends on the separately created clean 5584 evidence, not that disputed baseline.
