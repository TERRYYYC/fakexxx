---
feature_ids: [1, 7]
topics: [decision, acceptance, g2, m-co-06, host-coverage, device-gate]
doc_kind: acceptance-disposition
created: 2026-08-30
status: proposed
decision_id: G2-M-CO-06-HOST-COVERAGE-DISPOSITION
operator_direction_ref: 0001788106695038-000525-eda3e1d7
acceptance: pending
---

# Canonical disposition (proposed) — M-CO-06 host coverage while no controlled current device fixture exists

## Status and authority

This is a **proposed** canonical scope/evidence disposition. It is not an
accepted disposition, device evidence, a device lease, or a G2 release.

The operator's exact reply in `0001788106695038-000525-eda3e1d7` is:

```text
走
```

Its immediately preceding decision packet proposed three coupled actions:
re-cut the candidate at `00cb94e9`, use host-side proof for this M-CO-06
condition, and re-bind the real-device readiness evidence. It explicitly
described the second action as a relaxation of the acceptance criterion. That
reply is therefore retained only as `operator_direction_ref`: it contains no
`DOCUMENT=`, decision identifier, scope/gate fields, or acceptance record for
this document.

Until an operator explicitly accepts this exact path and its gate terms,
`acceptance: pending` means the host-coverage branch is **not satisfied**.
Nothing in this document may be read as “M-CO-06 passed on a real device.”

## Proposed disposition for the current G2 gate

The proposed G2-only predicate is:

```text
MCO06_DEVICE_PASS ∨ MCO06_ACCEPTED_HOST_COVERAGE_DISPOSITION
```

`MCO06_DEVICE_PASS` remains the normal path: execute the unchanged
`M-CO-06` procedure from the device matrix, preserve raw device evidence, and
write the genuine canonical `passed` ledger row. The proposed alternative is available
only after this document has an exact operator acceptance.

While this document is pending, the alternative is false. It does not remove
the device-matrix row, convert a device prerequisite into a `passed`,
`deferred`, or `skipped` ledger row, or make G2 §7 true. The current ledger is
still the original empty array (`[]`, 3 bytes,
`37517e5f3dc66819f61f5a7bb8ace1921282415f10551d2defa5c3eb0985b570`).

## Evidence basis and its limits

### Canonical device prerequisite

`docs/acceptance/a-plus-device-matrix.md#M-CO-06` requires a real-device
scenario in which running-marker text is not emitted in the accessibility
tree. Its setup explicitly requires a marker-less SDK build or a controllable
build flag. That prerequisite is outside this repository's implementation
surface.

### Current fixture availability

A read-only, offline diagnostic of the currently installed third-party
`com.cellrebel.mobile` build recorded:

```text
versionCode/versionName  363 / 1.9.3-full
base.apk SHA-256         24ea13dff94ed9229f0cf68044941c96d78119c4b5e302fad63c3c0fa7659595
resource strings          #1223 Measuring video streaming quality…
                          #1224 Measuring web browsing quality…
                          #1324 Processing results…
```

The diagnostic is preserved at
`~/Desktop/coding/mco06-diag-cellrebel-apk/marker-strings-hit.txt`
(`SHA-256 8253724367bd6d83beecf766e37a9c8e81f1856eae8a34e68f57ea3c401fcebf`),
alongside the pulled APK. Repository inspection found no controlled
marker-less SDK artifact or build flag.

This establishes a bounded fact: the current evidence provides no controlled,
reproducible marker-absent fixture for the matrix procedure. It does **not**
prove that every possible runtime state of that SDK must display a marker, and
it is not a device execution result.

### Host-side fail-closed coverage

At `00cb94e9bf880c3b23f6e00783aeab768da2bc1f` (PR #59),
`CellRebelStateDetector` makes a running marker the only `RUNNING` evidence.
The two M-CO-06-specific classifier tests assert that marker-absent screens
with a disabled Start control resolve to `UNKNOWN`, not `RUNNING` or
`COMPLETED`. The lifecycle's no-running-evidence path is typed
`NO_RUNNING_EVIDENCE`; its failure path does not increment quota, and the Run
screen projects the typed last failure.

That is host-side implementation coverage of the fail-closed path. The direct
M-CO-06 tests are classifier tests; they are not a real-device execution, and
they do not independently prove the full device-session outcome or a captured
UI alert. This distinction is why this proposed disposition cannot be called a
device PASS.

## V2 and future device gate

`V2_GATE=NOT_APPLICABLE` for this disposition. The existing skew disposition's
`V2_GATE=REQUIRED` concerns a future second **protocol** version; M-CO-06 is
not a protocol-version deferral and must not inherit that terminology.

Instead, the non-bypass follow-through is:

```text
MARKERLESS_SDK_DEVICE_GATE=REQUIRED
```

Before any claim that a marker-less or marker-altered SDK configuration is
supported, available, or safe, the owner must obtain a controlled fixture and
run the original M-CO-06 real-device procedure. It must preserve raw evidence,
bind the candidate and installed bytes, and write the resulting genuine device
ledger row. Neither host tests nor this disposition can replace that later
device gate.

## Boundaries and required acceptance

- This proposal changes neither the canonical device matrix nor the original
  facts in its evidence-registration template.
- It authorizes no device command, build, candidate change, device-ledger row,
  G2 release, or volatile gate update.
- It does not satisfy any other G2 predicate, including the journey, recovery,
  revocation, exact-build, Hook, DUAL-verdict, or operator-release predicates.

If the operator chooses to accept the proposed branch after review, the
acceptance must bind this exact document and retain both future-gate values;
for example:

```text
ACCEPT G2-M-CO-06-HOST-COVERAGE-DISPOSITION; DOCUMENT=docs/acceptance/issue7-m-co-06-host-coverage-disposition.md; MCO06_HOST_COVERAGE=ACCEPTED; V2_GATE=NOT_APPLICABLE; MARKERLESS_SDK_DEVICE_GATE=REQUIRED
```

Only that later exact acceptance may change this document from proposed to
accepted and make the corresponding G2 §7 branch decidable as satisfied.
