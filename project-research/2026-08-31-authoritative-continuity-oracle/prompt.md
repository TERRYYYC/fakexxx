---
feature_ids: [G2-66]
topics: [android, qianwangyou, continuity-oracle, system-server, lsposed]
doc_kind: research-prompt
created: 2026-08-31
---

# Authoritative QWY continuity oracle research prompt

## 1. Problem frame

Design the smallest production-capable oracle that can authoritatively prove that QWY's mock-location owner and GPS/network provider-enabled state did not change across a PRE → raw GPS/network read → POST observation window. The oracle must also cover QWY service generation, mode/profile/schedule changes, and effective-coordinate semantic changes. It must make an away→restore transition observable even when the endpoint state is identical again.

This is not a request for polling, freshness heartbeats, ordinary AppOps callbacks, provider broadcasts, or current-state-only checks. Those remain at most PARTIAL/NONE. No real phone may be used; device acceptance remains out of scope for this implementation pass.

## 2. Current hypotheses

- Public Android callbacks and current-state queries expose neither an authoritative historical sequence nor a drain barrier, so they cannot prove uninterrupted history.
- A system_server/privileged execution plane can wrap every authoritative relevant mutation in an odd/even sequence transition and expose a coherent snapshot over Binder.
- QWY can double-snapshot around the raw GPS/network read and report FULL only when both stable snapshots have the same bootId and sequence, complete coverage, healthy hooks, the QWY owner, and both providers enabled.
- QWY-owned semantic mutations may need a separately durable, single-writer sequence or an oracle transaction endpoint; ordinary same-coordinate refresh ticks must not mutate continuity state.

Evidence gaps: exact AOSP mutation entry points across supported Android versions; whether LSPosed can reliably hook system_server and expose a Binder/system-service endpoint; how missing hooks and process/module restarts are detectable; which Android identity is safe as bootId; and how observation acknowledgement plus environmentRevision bump can be made crash-conservative.

## 3. Disconfirm first

Seek primary evidence that falsifies each hypothesis. In particular:

1. Find any public Android API that actually offers ordered, lossless, drainable history for AppOps mock-location owner and provider-enabled mutations.
2. Find mutations that bypass plausible LocationManagerService/AppOpsService hooks.
3. Find LSPosed/system_server lifecycle or class-loader constraints that make complete hook coverage or stable Binder publication impossible.
4. Find crash windows where a sequence/ACK design could lose a relevant change or incorrectly preserve FULL.

## 4. Source mix quota

Use primary sources only: Android SDK reference, AOSP source/tests/compatibility documents, official LSPosed documentation and repositories, and this repository's exact code at `5002e0e005324c32ca3d36d10510180d1fafbf81`. Secondary blogs, Stack Overflow answers, and vendor marketing are leads only and must not support claims.

## 5. Local constraints

- Android project with QWY and CellRebel Auto apps plus a frozen v1 Binder contract.
- Production public-API monitoring currently fails closed at PARTIAL/NONE.
- Required fields: protocolVersion, bootId, monotonic stable sequence with explicit mutation-in-progress state, owner UID/package, GPS/network enabled state, installed coverage mask, health, and QWY semantic mutation coverage.
- Missing hook, boot change, sequence regression, oracle restart, concurrent mutation, and read/ACK crash must fail closed.
- Sequence acknowledgement and environmentRevision bump must be durable in one transaction; crash before ACK may repeat a bump but may never lose one.
- No real-device operations. Emulator commands, if later authorized, must explicitly target `emulator-5554`.

## 6. Output schema

For every claim provide: claim, exact source URL and symbol/version, support/disconfirm/unknown verdict, confidence, applicable Android versions, and implication. Separate source validity from direct fit to this repository. Include a mutation coverage table, lifecycle/failure table, and explicit unknowns.

## 7. Decision interface

Map evidence to one of:

- adopt: implementation can safely be built now against exact repository seams;
- pilot: contract and fakes can be implemented, but production hook must remain unhealthy/NONE pending exact-build emulator/rooted-device proof;
- defer: no execution plane can meet complete coverage without unacceptable fragility.

Recommend both the minimum safe deliverable and the stronger production architecture. Never promote FULL on an unproved source.

## 8. Risk register

- Silent bypass of a mutation hook mints false trusted quota.
- Hook/API drift across Android releases produces false health.
- Reboot/module/system_server restart aliases old sequence state.
- Torn snapshot or concurrent mutation appears stable.
- QWY semantic mutation commits without entering the oracle sequence.
- ACK crash loses an environmentRevision bump.
- A fake provider demonstrates behavior the real provider cannot reach.
