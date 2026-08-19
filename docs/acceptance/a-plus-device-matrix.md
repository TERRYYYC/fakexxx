---
feature_ids: [6]
topics: [acceptance, device-matrix, a-plus]
doc_kind: evidence-registration
created: 2026-08-16
---

# A+ Device Matrix — Real-Device Evidence Registration

> **This file is a registration template, not execution evidence.**
> Markdown existence does not constitute evidence of execution.
> `reportDigest` in the evidence manifest must point to a real device
> evidence file whose SHA-256 matches the raw report bytes.
>
> Actual device evidence is produced in PR-6's authorized device lease,
> NOT in PR-5. See §10.1 for the lane selector and evidence manifest spec.

## Evidence Manifest

Path: `docs/acceptance/matrix-evidence-device.json`

Format per row:
```json
{
  "rowId": "M-CO-06",
  "status": "PASS|FAIL",
  "lane": "device",
  "testId": "docs/acceptance/a-plus-device-matrix.md#M-CO-06",
  "exactHead": "<git SHA>",
  "reportDigest": "sha256:<hex of raw device evidence file>"
}
```

---

## M-CO-06

**Category**: completion
**Invariant**: INV-11
**Evidence class**: device

### Scenario

> 设备上完全不出现 running marker 文本。
>
> Running marker text does not appear on the device at all.

The CellRebel Auto app relies on detecting a running marker (e.g. "CellRebel
is running") in the accessibility tree to confirm that the CellRebel SDK
measurement is active. When this marker text never appears on a real device,
the system must fail-closed.

### Expected Outcome

> 全部判未验证并显式告警；不得回退到 disabled-Start 弱信号。
>
> All attempts judged unverified with explicit alert. Must NOT fall back
> to the disabled-Start button weak signal.

- **Every attempt** in the run session is marked as `UNVERIFIED` (not `VERIFIED`, not `SKIPPED`)
- The UI surfaces an **explicit alert/warning** to the user that verification could not be completed
- The system must NOT use the "Start" button's disabled state as a weaker substitute for the running marker — that path is explicitly forbidden by INV-11

### Device Evidence Procedure

1. **Setup**: Install Auto APK + CellRebel SDK app on a real device. Ensure qwy/FakeGPS is configured and paired.
2. **Precondition**: The CellRebel SDK app must be modified/configured so that the running marker text is NOT emitted in the accessibility tree (simulate SDK version without marker, or use a build flag).
3. **Execute**: Start a plan with ≥1 scheduled location. Let the automation engine attempt at least one measurement cycle.
4. **Observe**:
   - [ ] Every attempt row shows `UNVERIFIED` status
   - [ ] UI displays an explicit alert/notification about missing running marker
   - [ ] No attempt is counted as verified/trusted
   - [ ] The system does NOT fall back to disabled-Start button detection
5. **Capture**: Screenshot of the attempt history showing UNVERIFIED status + alert message. ADB logcat excerpt showing the fail-closed path.

### Evidence (filled in PR-6)

| Field | Value |
|---|---|
| Device | _model, serial (first 4 chars)_ |
| APK SHA | _sha256 of the installed APK_ |
| Build | _exact HEAD_ |
| Date | _YYYY-MM-DD_ |
| Result | _PASS / FAIL_ |
| Evidence file | _path to screenshot/log file_ |
| Report digest | _sha256 of evidence file_ |

---

## M-VS-01

**Category**: version
**Invariants**: INV-3, INV-19
**Evidence class**: device

### Scenario

> 新 Auto + 旧 qwy / 旧 Auto + 新 qwy。
>
> New Auto + old qwy (version skew forward), and old Auto + new qwy
> (version skew backward).

The v1 contract has a `callerProtocolVersion` field. When Auto and qwy
are at different versions, the system must either operate normally (if
compatible) or stop cleanly at preflight (if incompatible).

### Expected Outcome

> 兼容则运行，不兼容则预检停止。
>
> Compatible: run normally. Incompatible: preflight stops (no partial operation).

- **Compatible versions**: The system operates normally through the full lifecycle (apply → observe → release/advance). The version skew is transparent to the user.
- **Incompatible versions**: The preflight check detects the version mismatch and stops **before** any lease is acquired. The error is surfaced with a typed reason (e.g., `PROTOCOL_VERSION_UNSUPPORTED`). No partial state is left behind.

### Device Evidence Procedure

#### Sub-case A: New Auto + Old qwy (forward skew)

1. **Setup**: Install the latest Auto APK. Install an older qwy APK that implements a previous protocol version.
2. **Execute**: Start a plan. Auto sends preflight with its protocol version.
3. **Observe** (compatible case):
   - [ ] Preflight succeeds
   - [ ] Apply/observe/release work normally
   - [ ] The version difference is logged but does not cause failure
4. **Observe** (incompatible case):
   - [ ] Preflight returns a typed error indicating version mismatch
   - [ ] No lease is created
   - [ ] UI shows a clear message about version incompatibility
5. **Capture**: Logcat showing protocol version negotiation. Screenshot of success or error state.

#### Sub-case B: Old Auto + New qwy (backward skew)

1. **Setup**: Install an older Auto APK. Install the latest qwy APK.
2. **Execute**: Start a plan. Auto sends preflight with its (older) protocol version.
3. **Observe**: Same criteria as Sub-case A.
4. **Capture**: Logcat showing protocol version negotiation. Screenshot of success or error state.

### Evidence (filled in PR-6)

| Field | Value |
|---|---|
| Device | _model, serial (first 4 chars)_ |
| Auto APK SHA | _sha256_ |
| Auto version | _versionCode / versionName_ |
| Qwy APK SHA | _sha256_ |
| Qwy version | _versionCode / versionName_ |
| Build (exact HEAD) | _git SHA_ |
| Date | _YYYY-MM-DD_ |
| Sub-case | _A (forward) / B (backward)_ |
| Compatibility | _compatible / incompatible_ |
| Result | _PASS / FAIL_ |
| Evidence file | _path to screenshot/log file_ |
| Report digest | _sha256 of evidence file_ |
