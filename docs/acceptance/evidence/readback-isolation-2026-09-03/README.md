---
feature_ids: [G2-66, G2-71]
topics: [android, evidence, readback, codex-bench]
doc_kind: evidence-index
created: 2026-09-03
implementation_commit: 6691bcb00305615676ef8d6856561ba3c23d61ab
---

# Selected final receipts

These files are unmodified copies of generated evidence, not reconstructed transcripts.
See the [combined report](../../readback-isolation-combined-2026-09-03.md) for claims,
limitations, commands, ownership, independent review and the next action.
The local `.gitattributes` exempts only these raw receipt types from whitespace warnings
so adb CRLF, command trailing spaces and instrumentation blank lines stay byte-identical.
Source and prose whitespace checks are unchanged.

- `host/`: complete final repository-gate/build output, JUnit XML count summary, APK
  identity/hash output and capture metadata. Each metadata file records actual HEAD,
  command, UTC interval and shell exit. The host aggregate deliberately retains the
  separate product/device gate as BLOCKED.
- `android/`: the four new tests and nine existing tests ran under separate permission
  setups; stdout contains JUnit's `OK`, not merely a shell exit 0. Installed hashes and
  final bounded cleanup receipts are included. Each `.avd` confirms the expected
  `codex_readback_clean_api35`; each `.meta` identifies explicit serial `emulator-5584`.
- `ui/`: actual final codexBench screenshots captured and viewed by the independent
  non-author, then also viewed by the coordinator. No profile was saved for this review.
- `review-final.md`: the independent non-author's report, copied without changes.

The complete temporary evidence directories are listed in the combined report. They
contain additional RED runs, setup, runtime logs, permissions/location snapshots and
discarded-environment investigation; they are not durable remote storage. This selected
bundle is not a claim that all original evidence has been uploaded. No Moto or
framework-positive result is represented here.
