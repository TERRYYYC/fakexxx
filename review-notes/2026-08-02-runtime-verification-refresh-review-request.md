---
feature_ids:
  - runtime-hook-verification
  - configurable-hook-refresh
topics:
  - review
  - android
  - xposed
doc_kind: review-request
created: 2026-08-02
---

# Review Request: release runtime verification + configurable refresh

Review-Target-ID: runtime-verify-refresh
Branch: feat/runtime-verify-refresh
Implementation SHA: `7cae6588542826ba0b6ad3937c0afd8e90ef51cf`

## What

- A bounded, persisted 5/10/30/60-second hook refresh policy with one scheduler owner per process.
- A private, non-exported release `:hook_verify` process that reads public APIs under the hook and
  returns requestId+fingerprint-correlated observations without spoofing the release main UI.
- Truthful Settings/editor UX: selectable interval and “保存并验证” only after publication succeeds.
- Release-visible, value-free probe/scheduler lifecycle evidence plus a read-only Task 5 host
  verifier for fingerprint, interval, ownership, timeout termination and fresh retry.

## Why

The stable release UI deliberately stays unhooked, so its old verification route could only read a
real baseline and displayed many configured values as unreadable. Separately, refresh timing was
not user-configurable and the release runtime had no evidence with which a host acceptance test
could distinguish a real probe/scheduler lifecycle from a false green.

## Original Requirements

> “验证功能现在都是读不到，之前是正常的。”
> “配置界面，有快捷跳转验证的按钮。”
> “hook 的刷新时间，我希望是可以配置的，当前无法点击配置。”

- 来源：co-creator thread messages on 2026-08-01, codified in
  `feature-specs/2026-08-01-runtime-verification-and-refresh.md`.
- 请对照上述体验判断交付是否真正恢复了稳定 release 的可验证性，而非只让 debug self-hook
  看起来正常。

## Tradeoff

- Kept polling instead of adding a broadcast/push channel: existing schema-v3 transport and target
  process isolation remain unchanged; one already-scheduled old tick is allowed after interval
  changes.
- Kept the probe timeout at 12 seconds: a new probe loads the published payload synchronously, so
  tying its timeout to the 60-second target refresh interval would only hide lifecycle failures.
- Stale callbacks are logged as `ignored` and the active request continues waiting, matching the
  state plan; they are not relabelled as payload fingerprint mismatch or terminal success.
- Runtime evidence logs correlation identifiers, the existing truncated fingerprint, counts and
  intervals only; profile values never enter release logs.

## Architecture Ownership

Architecture cell: profile config → published payload → target/probe process → verification UI
Map delta: new cell required
Why: the production-only, non-exported `:hook_verify` process is a new lifecycle owner for one-shot
verification sessions; `RuntimeEvidence` is its stable host-observation contract, not a parallel
store or transport.

Please verify the diff does not introduce a second config store, scheduler owner or verification
decision engine.

## Open Questions

### Technical OQ

1. Does client-side correlation correctly ignore stale callbacks while leaving the active request
   alive until a matching callback or timeout?
2. Are `scheduler_owned` and `interval_changed` emitted at the only state transitions that can
   prove duplicate-timer absence and 5s/60s handoff without per-tick log spam?
3. Does the host verdict fail closed for out-of-order/mismatched terminal events, timeout without
   probe PID disappearance, requestId reuse, and missing fresh delivery?
4. Does R8 retain the production sentinel/evidence path while release still excludes every debug
   acceptance/recovery class?

### Value OQ

无。设备安装和 Vector/Cellular-Pro matrix 已由 spec 明确排在 review 通过之后。

## Fresh-Context Findings

Agent: DeepSeek V4 Flash (fresh-context finding generator, not final approval authority)
SHA scanned: `9888c6e`
Total findings: 3 (1 P1, 2 P2)

| # | Finding | Author disposition | Status |
|---|---|---|---|
| FC-1 | Task 5 had no release-visible probe/scheduler evidence or host scripts | fixed in `7cae658` | closed |
| FC-2 | stale correlation failure was collapsed into `PAYLOAD_MISMATCH` | fixed in `7cae658`; stale callback is ignored and separately evidenced | closed |
| FC-3 | timeout/retry acceptance did not prove process exit and fresh requestId | fixed in `7cae658` host verdict | closed |

Reviewer should mark findings `[FC:covered]`, `[FC:new]`, or `[FC:N/A]`.

## Review Provenance Boundary

Fable authored Task 3 commits `d6827af` and `dcc5c99`; Fable cannot approve those commits. Sol
independently reviewed Task 3 before integration, including the corrected pending-window semantics,
publication-failure UI, persisted republish seam and exact 5/10/30/60 choices. Fable's approval
authority in this review is limited to Sol-authored commits `e0b6be4`, `5d5ea79`, `9888c6e` and
`7cae658`, plus their integration boundary with the already-reviewed Task 3 slice.

## Next Action

Fable: perform a detached, read-only review of the exact remote HEAD. Give APPROVE or
REQUEST_CHANGES with P1/P2 findings and FC annotations. Do not install an APK or modify the device;
on approval, return the ball to Sol for the authorized stable-release device matrix.

## Review Sandbox

- Path: `/tmp/cat-cafe-review/runtime-verify-refresh/fable5`
- Checkout: detached exact remote HEAD
- Start command: Android/host gates below; no web/api ports

## Quality Gate Evidence

### Spec compliance

- Tasks 1–4 implemented; Task 5 host contract implemented.
- Task 5 device execution intentionally not run: the plan requires review before `adb install -r`.
- Main release remains unhooked; release probe is `exported=false` in `:hook_verify`.
- No root media/design artifacts; no matching `.pen` design; no new fallback stack.
- Architecture delta matches the declared new lifecycle cell.

### Fresh commands and results

```text
:app:testDebugUnitTest --rerun-tasks                 301 / 0 / 0 / 0
python unittest discover scripts                    33 / 0
assembleDebug + compileDebugAndroidTestKotlin
  + assembleRelease(R8) + lintVitalRelease          103/103 tasks, BUILD SUCCESSFUL
lintDebug                                            existing 20 errors / 158 warnings
current 7cae658 delta                                0 errors / 0 warnings
bash -n + git diff --check                          pass
release DEX scan                                    production evidence/sentinel present;
                                                    debug acceptance/recovery symbols absent
release merged manifest                             HookVerificationService exported=false,
                                                    process=:hook_verify
```

### Dogfood

The host verifier consumed a realistic brief-log trace containing requested → ignored stale →
correlated delivered plus scheduler 30s → 5s → 60s transitions and returned
`DOGFOOD True () 6`. Device dogfood is the explicitly sequenced post-review acceptance step.

### Related document

- `feature-specs/2026-08-01-runtime-verification-and-refresh.md`
