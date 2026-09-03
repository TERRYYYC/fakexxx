---
feature_ids: [G2-66, G2-71]
topics: [android, magisk, vector, xsharedpreferences, system-mock, evidence]
doc_kind: evidence-manifest
created: 2026-09-03
status: reviewed-pass
application_code_head: e1a00eab462b2d5e9355a51f5fdd4da0f77b7709
---

# Framework-positive emulator evidence

This directory contains the portable, coordinate-redacted evidence for the bounded
framework-positive run on owned AVD `codex_framework_api35` / `emulator-5580`.
The production application bytes came from code HEAD `e1a00eab`; the run used private
ADB server port 5040 and boot ID `7c78851c-bd41-4221-9bc4-272e87542dd0`.

Files:

- `framework-and-artifacts.txt`: pinned framework inputs, live framework state, app IDs,
  UIDs, installed-byte hashes and the bounded codexBench self-hook observation.
- `config-a-b.txt`: writer A, different-UID reader A, same-PID hot B and new-PID cold B.
- `raw-location.txt`: production System Mock UI state, adjacent GPS/network reader samples
  and Android provider ownership.
- `cleanup.txt`: UI stop, provider restoration, AppOp/permission/module cleanup, the Vector
  empty-scope CLI failure and destruction of the dedicated temporary AVD.
- `raw-receipt-sha256.txt`: SHA-256 manifest for the corresponding unmodified local receipts.
- `review-final.md`: independent non-author review, added after the evidence audit.

The unmodified receipts remain at `/tmp/fakexxx-framework.wofmpO/receipts/` on the execution
host. They include exact command, UTC interval, serial, AVD name, SDK/ABI/debuggable checks,
boot ID and exit status. Selected extracts here replace the chosen test coordinate with
`[redacted]`; their source receipt hashes allow the local originals to be checked.

This evidence establishes only the plan's framework-positive slice. It is not Moto evidence,
not #66 FULL/continuity, and not proof of fresh/trusted/exact target coordinates.
