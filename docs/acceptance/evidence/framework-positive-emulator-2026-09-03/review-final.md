---
feature_ids: [G2-66, G2-71]
topics: [android, framework-positive, evidence, independent-review]
doc_kind: review
created: 2026-09-03
---

# Independent review — framework-positive emulator validation

Review-Target-ID: `fakexxx-framework-positive-emulator-20260903`

Reviewer: Codex subagent `/root/readback_isolation_review`, non-author of the reviewed
report and evidence. The review was read-only and is not a merge or product-acceptance
decision.

Worktree HEAD at review: `8d0914860e2fcbc4142b99bd602931d8295ac037`

Application source anchor: `e1a00eab462b2d5e9355a51f5fdd4da0f77b7709`

Exact reviewed seven-file aggregate SHA-256:
`719a8fb387295b7b3e08f0368c17813d7727e79677fd264cedee1acd371beab8`

## Verdict

**SCOPED APPROVE. P0: none. P1: none. P2: none.**

The reviewer independently reproduced the aggregate digest and verified all 46 entries in
`raw-receipt-sha256.txt` against the unmodified local receipts. The approval covers the
bounded report and evidence for one owned API 35 emulator run: framework state, installed
artifact identity, production A to hot-B to cold-B configuration transport, adjacent raw
GPS/network samples, bounded self-hook isolation corroboration, and the stated cleanup facts.

## Independently checked boundaries

- All guarded device metadata bound the run to `codex_framework_api35`, `emulator-5580`,
  private ADB port 5040 and boot ID `7c78851c-bd41-4221-9bc4-272e87542dd0`.
- A used Auto UID 10209 / PID 11468. Hot B remained in PID 11468; cold B used new PID
  11919 with the same UID. The complete cold log boundary had zero writer-sync lines, two B
  fingerprint lines and zero A fingerprint lines.
- The production QWY service returned adjacent GPS/network `SAMPLE`, `enabled=true`,
  `mock=true` lines with `publish_anchor_ms=0` and `freshness=UNASSESSED`; Android provider
  ownership was used only as corroboration.
- QWY loaded Vector/MainHook plumbing but emitted zero generic spoof-hook registration lines
  in the observed PID; Auto emitted 16. The report correctly limits this to corroboration of
  the tested self-isolation policy.
- Cleanup evidence proves UI stop, empty QWY service list, GPS/network native-provider
  restoration, AppOp `default` readback and Vector module `disabled` readback. It also
  preserves the passive/fused cached sample, missing terminal readbacks, failed empty-scope
  command, residual scope before AVD destruction and unknown ADB-offline cause.
- The missing `magisk32` warning is retained and the result is not generalized to 32-bit.

## Claim ceiling

This approval does not cover Moto `ZY22JHW9M4`, #66 FULL/continuity, non-zero-anchor
freshness, trusted/exact coordinates, an Auto public-API spoof observation, a 32-bit target,
globally empty location caches, successful scope clearing, independent terminal readback of
permissions/settings/marker/PIDs, the cause of the ADB offline event, or a replayable retained
AVD. It does not authorize a merge or closing #66/#71.

The reviewed seven-file digest intentionally predates this review file and the two mechanical
status/link updates that publish the terminal verdict. No production code or evidence claim
changed after review.
