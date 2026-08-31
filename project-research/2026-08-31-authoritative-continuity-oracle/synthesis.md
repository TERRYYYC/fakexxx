---
feature_ids: [G2-66]
topics: [android, qianwangyou, continuity-oracle, decision]
doc_kind: research-synthesis
created: 2026-08-31
---

# Authoritative continuity oracle synthesis

## Decision

**Pilot.** Build the internal protocol, state machine, durable reconciliation, system-server hook/bridge, central semantic writer coverage, and JVM proof now. Keep the production build attestation allowlist intentionally empty until the exact-build emulator and explicitly authorized rooted-device gates are run. Consequently, this change can demonstrate that the COMPLETE path is reachable through controlled fakes, but no untested production installation can report FULL and this pass records no real-device PASS.

Public AppOps/provider callbacks stay installed for diagnostics and conservative revision bumps. They remain `INCOMPLETE`/`UNAVAILABLE` and are never promoted by polling, callback serialization, or fresh mock samples.

## Minimum safe deliverable

1. Internal oracle protocol v1 with kernel boot ID, separate oracle-instance ID, odd/even sequence, unique owner, GPS/network enabled state, required/installed coverage masks, health, and QWY semantic digest/session state.
2. Deterministic thread-safe state machine: every outer mutation changes stable sequence by two; nested/concurrent mutations keep it odd until the last participant exits.
3. QWY double-snapshot around `observeEffective()` and durable `(environmentRevision, oracle ACK, coverage, continuitySince)` reconciliation in one transaction.
4. Fail-closed handling for absence, Binder death, odd/mismatched snapshots, boot change, same-boot regression, incomplete mask, unhealthy state, ambiguous/non-QWY owner, or disabled provider.
5. A system-server integration shell that resolves exact Android 15 AOSP symbols, reports an installed mask, and refuses health on an unattested build.
6. Tests for every issue #66 acceptance case, including the negative assertion that same-coordinate refresh does not mutate the oracle.
7. One central writer runtime covering settings, profiles, config publication, and handler apply/converge/cleanup mutations; nested writer calls share the outer correlation, while refresh cadence/sample publication stays outside the semantic boundary.

## Stronger production architecture

The system-server module owns the only authoritative sequence. It installs hooks before publishing an oracle Binder. At a mutation boundary, the first entrant changes even→odd; nested/concurrent entrants increase a depth counter; the last exit refreshes endpoint truth and changes odd→the next even value. QWY-owned semantic changes use the same remote transaction and a death token, so process death cannot leave an apparently healthy stable interval.

Advance is the one local ordering constraint that cannot be handled by a blind observer-side bump. QWY commits its receipt before external pointer convergence, while Auto requires immediate POST revision equality. The pilot therefore writes a future revision reservation—without advancing the committed revision—beside the receipt/pending ticket and carries the same mutation ID through normal/crash roll-forward. Normal completion may commit `R→R+1` only for the same boot/oracle instance at exactly `start+2` with that exact ID. Owner recovery may also coalesce to `R+1` only for the same identity at exactly `start+6`, accounting for death, explicit registration, and the reserved mutation; its recovery fence makes the first stable window NONE.

If a healthy reboot/instance change or unrelated interleaving has irreversibly destroyed either exact correlation, the implementation retires the reservation at `R+2`, ACKs the current cursor, quarantines the stale `R+1` mutation ID, clears pending, and forces the first stable window to NONE. Replaying the stale receipt then fails loudly, while a later stable window can recover service. This replaces the earlier pilot rule of leaving such a ticket pending forever: indefinite pending cannot restore proof after identity/interleaving divergence and instead creates a durable outage. Quarantine keeps Auto fail closed because the receipt's `R+1` can never authorize the `R+2` environment. Calls without an authoritative lane retain the existing public-source bump/PARTIAL-or-NONE path. An interleaving after exact finalization becomes a new bump and intentionally trips Auto's fail-closed equality gate.

After system services are ready, the module binds explicitly to QWY's exported registrar service. The registrar accepts only `SYSTEM_UID` and stores the oracle Binder in a process singleton with death handling. The normal Environment Control service reads this proxy twice around raw GPS/network observation. The Auto↔QWY v1 protocol remains frozen: oracle proof is projected into the existing revision/coverage/continuity fields.

## Why alternatives fail

- Polling observes endpoints, not history; away→restore aliases unchanged.
- Public AppOps callbacks have no replay, order, sequence, or drain contract.
- Mock refresh proves sample freshness only; treating a tick as continuity creates false history.
- A QWY-local counter cannot see system mutations that happen while its process is paused or dead.
- Hook-installed-without-attestation confuses “method name resolved” with “all device mutation paths covered.”

## Exit criteria for the pilot label

**Issue #66 AC7 is NOT PASSED and remains quarantined/deferred.** The pilot may become production-healthy only after evidence from the exact emulator build and an explicitly authorized rooted target proves: system scope entry, every mask bit, away→restore, provider disable→enable, concurrent read, process/Binder death recovery, reboot identity change, sequence monotonicity, and zero sequence movement across ordinary refresh ticks. The exact fingerprint allowlist remains empty in this pass; only a separately reviewed evidence change may add one. Until then there is no production FULL and no real-device PASS claim.
