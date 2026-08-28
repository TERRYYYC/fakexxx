---
feature_ids: [1, 7]
topics: [decision, acceptance, g2, version-skew, task-9, device-gate]
doc_kind: acceptance-disposition
created: 2026-08-29
status: accepted
decision_id: G2-SKEW-POST-V1-DISPOSITION
operator_decision_id: G2-SKEW-DISPOSITION
operator_direction_ref: 0001787952746397-000121-df185e79
operator_acceptance_ref: 0001787953746336-000156-46dbd2e1
accepted_at: 2026-08-28T21:49Z
acceptance: accepted
---

# Canonical disposition — M-VS-01 / Task 9 version skew is post-v1

## Status and authority

This is an **accepted** canonical scope/ledger disposition. It is not execution
evidence, a device lease, or a G2 release.

The operator directly replied `b` in
`0001787952746397-000121-df185e79` after being presented with the two scope
choices. That reply selected the **post-v1** direction. The separate operator
acceptance below then accepted this exact document path.

### Operator acceptance record

Message `0001787953746336-000156-46dbd2e1` at `2026-08-28 21:49 UTC` reads
verbatim:

```text
ACCEPT G2-SKEW-DISPOSITION; DOCUMENT=docs/acceptance/issue7-m-vs-01-post-v1-disposition.md; SCOPE=POST_V1; V2_GATE=REQUIRED
```

The operator's `G2-SKEW-DISPOSITION` is retained in
`operator_decision_id`; this document's canonical `decision_id` remains
`G2-SKEW-POST-V1-DISPOSITION`. They are not silently normalized. The exact
`DOCUMENT=` path in the acceptance binds the operator's shorter identifier to
this document.

## Accepted disposition

Remove `M-VS-01` and its Task 9 cross-version journey from the **current G2
gate only**. Keep the canonical `M-VS-01` definition and the
device-matrix registration intact: this is a scope deferral, not a deletion,
PASS result, or claim that version skew is unimportant.

The resulting G2 scope is a single real protocol-v1 world. It makes no claim
that mixed protocol versions are compatible. The G2 package §7 binds its
`canonical disposition 已接受` term to this exact accepted document:

```text
SKEW=POST_V1 ∧ canonical disposition accepted
```

No `M-VS-01` row may be added to
`docs/acceptance/matrix-evidence-device.json` as `passed`, `deferred`, or any
other substitute for real device evidence. The matrix's existing empty-ledger
rule continues to apply.

## Why the current case is not decidable by a device run

`M-VS-01` requires two real cross-version situations:

- new Auto + old qwy; and
- old Auto + new qwy.

For a compatible pair, the required behavior is the full
apply → observe → release/advance lifecycle. For an incompatible pair, the
required behavior is a typed preflight failure before any lease or partial
state exists.

Today both sides expose only `protocolVersion=1`; there is no second real
protocol artifact pair and no frozen compatibility oracle. A same-version
pair, or the G1 probe's `PROTOCOL SKEW` diagnostic line, cannot prove either
required behavior. This is an absence of a testable comparison, not a
downgrade of the underlying safety property.

## Non-bypass v2 release gate

Before any artifact that implements a second real protocol version is promoted
to a release candidate or released, the owner must first satisfy `M-VS-01`.
This gate applies before a v2 mixed-version claim or rollout; it cannot be
replaced by a unit test, a wire diagnostic, or this disposition.

The future gate must freeze and bind, for **both** directions:

1. old and new Auto/qwy APK SHA-256 values, exact build HEADs, signer
   identities, protocol versions, and stated supported-version sets;
2. an explicit expected outcome for each pair: compatible full lifecycle, or
   incompatible typed preflight rejection with no lease and no partial state;
3. the actual device evidence and raw report digests for the selected outcome;
4. continued green `M-VS-02` unknown-wire machine evidence, which is
   necessary but not a substitute for the real old/new pairs.

These are the concrete requirements that G2 package §3.3-4 would require if
`SKEW=IN_G2` were selected. This disposition carries them forward as the v2
release gate rather than discarding them.

## Boundaries

- This disposition changes neither the canonical spec nor the device matrix.
- It does not merge or undraft any PR, authorize a device command, consume a
  device lease, or change Issue #1's volatile G2 state.
- It does not satisfy any other G2 prerequisite, including the Hook block,
  exact-build evidence, DUAL verdict, or operator release authority.

## Acceptance follow-through

The G2 package §7 now links this accepted disposition as the decidable
`SKEW=POST_V1` branch. That link preserves the non-bypass v2 gate above and
does not authorize a fabricated or deferred device-ledger row.
