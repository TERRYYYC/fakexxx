---
feature_ids: [G2-66]
topics: [android, moto, device-preflight, quality-gate, evidence]
doc_kind: acceptance-evidence
created: 2026-09-04
---

# Issue #66 Moto collector host quality gate

## Verdict

The Task 2A **host-only slice** passes its author quality gate and is ready for an
independent exact-HEAD review. This is not a feature-completion or device-pass claim:

- issue #66 remains open and AC7 remains `NOT_PASSED`;
- no emulator or physical-device command was run by this branch;
- Task 2B privileged inspection is not implemented;
- both exact-build admission lists remain empty, so device `FULL` remains blocked;
- the live Task 2A collection may run only after an independent review approves the exact
  collector command surface.

The original requirement is [issue #66](https://github.com/TERRYYYC/fakexxx/issues/66): an
authoritative continuity oracle must fail closed and receive exact-build emulator plus authorized
rooted-device evidence before the blocker can close. The operator explicitly authorized staged
work on only Moto `ZY22JHW9M4` and repeatedly asked this plan to continue. This slice extends that
plan; it does not need to be rewritten when the later device and privileged stages are added.

## Vision and delivery coverage

| Requirement | Slice status | Evidence |
| --- | --- | --- |
| Never turn public callbacks or static presence into `FULL` | PASS | Collector manifest, redacted summary, checker JSON and host receipt all freeze device/FULL/AC7 claims as blocked. |
| Bind future evidence to the exact authorized Moto/build | HOST READY | Collector rejects another serial/device/manufacturer/API and binds a transcript's fingerprint, boot, framework/APK bytes and tool hashes. The live transport/source is not yet proven. |
| Detect PRE-to-POST continuity mutations authoritatively | OUTSIDE THIS SLICE | Runtime oracle work is inherited from earlier slices; no Task 2A result claims to exercise it. |
| Exact-build emulator plus authorized rooted-device proof | BLOCKED | Neither environment was run. This remains required for issue #66 AC7. |
| Do not collide with existing app identities | PRESERVED | This slice installs no APK and names only the already-separated `.codexbench` identities as future mutable targets. |
| Keep unauthorized device changes out of the preflight | PASS | The allowlist excludes install/uninstall, AppOps writes, provider toggles, `su`, private paths, lifecycle changes and reboot. |

Delivery completeness: this is an explicitly staged Task 2A host slice, not the complete feature.
It leaves durable expansion points (live collection, Task 2B, evidence-only admission and runtime
proof) rather than placeholders that require rewriting this collector.

## Functional acceptance

| Task 2A requirement | Status | Verification |
| --- | --- | --- |
| Device-free fake-ADB RED/green matrix | PASS | 1423 assertions, including positive-control fixture use, poisoned bare `adb`, first-targeted-command shell identity, source/snapshot replacement detection, exact Android 15 output grammars, archive validation and cleanup of frozen test evidence. |
| Exact serial-qualified operational-read-only allowlist | PASS | Mutating, generic-shell, wrong-serial and broad-query variants are refused before execution. |
| New mode-0700 evidence root and atomic STOP/final manifest | PASS | The parent walk uses no-follow directory descriptors; repository/worktree aliases, macOS firmlinks, inherited ACLs and inode/path replacement are refused. Initial-write, summary-write and final-replacement failures are covered. |
| One typed six-file receipt per ADB command | PASS | Exact stem graph, carrier type, argv, time, exit and undeclared-file checks are covered. |
| Shell identity, boot and monotonic-uptime binding | PASS | `shell id` is the first serial-targeted query and must be uid 2000 before other device observations. Malformed/change/decreasing boot cases fail closed; inventory and the identity preflight are explicitly outside the later evidence bracket. |
| Fixed package/process/AppOps and framework byte capture | PASS | API-35 missing-package semantics, every process row, exact AOSP AppOps/TimeUtils grammar (including UID overrides), safe split-APK paths, binary carriers and a whole-receipt-tree SHA-256 binding are covered. |
| Offline receipt verification | PASS WITH CLAIM CEILING | It opens each authenticated file no-follow and reads it from one inode-checked descriptor, then validates exact internal structure without ADB; it cannot authenticate that copied receipts came from a genuine Moto transport. |
| Static Android 15 services compatibility checker | PASS, PRODUCTION STOP | 93 assertions bind required members, inputs and output FD. The independently approved dexdump digest list is intentionally empty, so a host SDK tool returns `STOP_TOOL_NOT_ATTESTED`; the pinned fake can emit only `SELFTEST_STATIC_MEMBERS_PRESENT`, never a production candidate. |
| Repository-pinned production ADB client | PASS, SERVER UNATTESTED | The production lane accepts only the reviewed platform-tools 37.0.0 universal Mach-O client SHA-256 `9fdf861259dc807937b13afdd5f053c7fda9f3b7726933fe0e0f45130ecb8dc7`; the local ADB server/transport remains explicitly `NOT_ATTESTED`. |
| Stale or concurrent host PASS receipt cannot remain authoritative | PASS | Runner records exclusive-lock ownership, unlinks a prior receipt, and publishes `RUNNING/BLOCKED` before host work. The aggregate validator atomically takes the same sibling lock with a random owner token, rejects any pre-existing lock, holds ownership through receipt parsing/contract validation, and returns PASS only after owner/inode-checked cleanup. Any ownership or cleanup mismatch preserves the lock as a fail-closed fence. |
| Coordinate/private-state minimization | PASS | Summary whitelist excludes coordinates; root and private LSPosed/Vector observations remain `NOT_COLLECTED_PRIVILEGED`. |

## Close and follow-up audit

No CloseGateReport exists because this change does not close issue #66 or Task 2. The only
follow-up-tail keyword in the changed plan is the heading “deferred authorized device sequence.”
It explicitly marks unmet AC7 work as blocked and staged; it does not package that work as done.
Every remaining device, privileged, reboot/provider/lifecycle and attestation step is enumerated
with its owner/trigger boundary in the plan and runbook.

This slice was developed on `codex/issue66-moto-readonly-collector`, based on
`02574d210cc0`. The report deliberately does not predict the hash of the commit that contains it;
the review packet must bind the exact resulting HEAD and pull request. GitHub issue #66 was still
OPEN with P0/release-blocking labels at gate time.

The external repository does not contain `scripts/check-hotfix-pattern.mjs`,
`scripts/check-fallback-layers.mjs`, a root `package.json`, or a pnpm lockfile, so the Cat Cafe
mechanical hotfix/fallback/architecture/tips commands are unavailable here. Manual review found
no fallback stack: the new branches are typed fail-closed stops. The implementation plan records
an explicit internal-harness tips exemption.

## Architecture, design and artifact checks

- Architecture cell: `fakexxx::android-dual-app-contract`
- Map delta: none
- Why: the slice strengthens the external evidence gate without moving runtime ownership or
  changing the frozen Binder contract.
- Diff mismatch scan: no new Store, Queue, Router, Adapter, Dispatcher or Binding owner.
- `designs/**/*.pen`: no matches; there is no UI or frontend change.
- Root-level media/design artifacts in the worktree and committed diff: none.
- Governance/skill/MCP surface: unchanged.

## Dogfood-Your-Slice

Scope verdict: exempt from real-device author dogfood before review because this is an internal,
high-risk device-evidence harness whose live command surface is itself the object that must first
receive independent approval. The available end-to-end path was nevertheless exercised with an
explicit fake ADB: collect a complete bundle, verify it offline, mutate receipts and prove each
case fails closed. The persistent post-review trigger is: exact-HEAD approval plus the sole visible
device being `ZY22JHW9M4`; then run Task 2A once and review its evidence. This exception does not
unblock feature close or AC7.

## Five-axis risk and fresh verification

Risk: behavior=high (new evidence state machine and verifier); data=medium (new private evidence
tree and atomic receipts); security=high (real-device command/path boundary); contract=high
(machine schemas and claim ceiling); irreversible=low for this slice (no mutation command exists).
The high security/contract risk selected the full host gate in addition to targeted tests.

Iterative fresh-context scans found and the author fixed boundary issues across the collector,
checker and host runner: the ADB source identity/hash is checked through creation of a private
snapshot from one no-follow file descriptor, and only that snapshot is rechecked around every
later call; the evidence root and verifier inputs are inode-pinned; all receipt bytes are
tree-hashed; duplicate JSON keys, Unicode/control scalar
joins and noncanonical process/package/AppOps output are rejected; APK/JAR ZIP members and CRCs are
validated; the final summary precedes the final manifest; checker output stays bound to an
exclusive open FD; production dexdump approval is empty; and the host gate owns an exclusive lock
while invalidating stale PASS state. A later documentation-consistency scan then found overly broad
transport, framing and authorization claims plus three behavioral gaps: pathname-reopened verifier
reads, a mode-`0400` stale-PASS failure, and late shell-identity gating. Each received a reproducing
RED test and a fail-closed fix. A subsequent scan found that the aggregate receipt consumer could
accept stale PASS while a concurrent runner lock existed; RED tests reproduced pre-existing-lock,
handoff, owner-token, inode-replacement and cleanup races. The validator now owns the runner's same
lock for its full read/validation lifetime, and structural guards freeze the canonical sibling
receipt/lock assignments. The final finding-generator rescan reported no remaining P1/P2 on this
surface. All fresh-context scans remain finding-generator results, not formal review approval.

Fresh commands run from
`/Users/terry/Desktop/coding/fakexxx-moto-readonly-collector` on 2026-09-04:

| Verification | Result | Claim covered |
| --- | --- | --- |
| Collector selftest | `1423 passed, 0 failed`, exit 0 | allowlist, receipts, stable verifier, first-targeted-command shell identity, topology, exact API-35 parsers, archive structure, per-call ADB snapshot integrity, hashes, cleanup and fail-closed states |
| Services compatibility selftest | `93 passed, 0 failed`, exit 0 | source binding, exact JSON authority, member matrix, exclusive output FD, empty production tool allowlist and input/output TOCTOU |
| Relevant shell syntax checks | 8/8, exit 0 | executable scripts parse, including the aggregate receipt validator |
| `git diff --check` | exit 0, no output | patch hygiene |
| Targeted `HarnessBoundaryGuardTest` | 15 tests, 0 failures/errors | quoted/indirect ADB boundary, descriptor-pinned verifier reads, exclusive runner/validator locking, stale receipt invalidation and deterministic receipt-lifetime race seams |
| Zero-argument full host gate | exit 0 | collector 1423/1423; checker 93/93; Auto 13/13; QWY 69/69; integration harness 37/37; `hostIntegration=PASS`; `physicalDevice=NOT_RUN`; `overall=BLOCKED` |

The resulting receipt is intentionally not a completion receipt:

```json
{"schemaVersion":2,"hostIntegration":"PASS","issue66Ac7":"NOT_PASSED","emulator":"NOT_RUN","physicalDevice":"NOT_RUN","deviceFull":"BLOCKED","overall":"BLOCKED","reason":"HOST_GATE_HAS_NO_DEVICE_EVIDENCE__BOTH_ADMISSION_LISTS_EMPTY__ACTIVATION_CLEANUP_REBOOTS_AND_ADVERSARIAL_MUTATIONS_REQUIRE_ADDITIONAL_AUTHORIZATION"}
```

No command in these verification runs addressed a real ADB binary or physical device.
