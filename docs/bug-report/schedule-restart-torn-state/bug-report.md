---
feature_ids:
  - v0.1.0
topics:
  - schedule-restart
  - durability
  - crash-recovery
doc_kind: bug-report
created: 2026-09-05
---

# Schedule restart torn-state recovery

## Report

- Reporter: independent release reviewer, while reviewing PR #93 at `fb15d66`.
- Symptom: an operator restart could update qianwangyou's schedule before the provider revision and audit writes completed. A later provider write failure left a restarted external schedule with stale provider bookkeeping, and retry returned `NOT_EXHAUSTED` instead of repairing the split.
- Reproduction: use separate provider and schedule stores, exhaust the schedule, inject a provider revision or audit write failure, then invoke `restartScheduleForOperator()`.
- Expected: either no restart is committed, or a committed restart is recoverable and converges exactly once.
- Actual before the fix: the external schedule changed first and could not be rolled back or replayed from provider state.

## Root cause

The restart path crossed `FileDurableKv` and `SharedPreferences` without a durable recovery instruction. Its operation order was external schedule commit, provider revision bump, then provider audit append. The existing advance path already demonstrated the required pattern: atomically commit bookkeeping and a replay marker first, then apply an idempotent external mutation and clear the marker only after verified readback.

## Fix

The provider now atomically commits an exact restart target, revision bump, and audit event in its durable transaction. The qianwangyou schedule store applies that exact target idempotently from either the old exhausted state or the already-applied state. Every fenced entry and owner-process startup settles a pending restart before serving other work.

## Verification

`OperatorScheduleRestartTest` covers:

- revision-write rollback before external mutation;
- audit-write rollback before external mutation;
- external-write failure followed by one-time replay convergence;
- marker-clear failure after external commit followed by idempotent re-entry;
- normal restart, blocking lease, and non-exhausted rejection.

The full A+ verification gate must pass on the clean final commit before release review is re-entered.
