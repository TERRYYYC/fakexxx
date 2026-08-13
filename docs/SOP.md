---
topics: [sop, workflow]
doc_kind: note
created: 2026-08-09
---

# Standard Operating Procedure

## Workflow (6 steps)

| Step | What | Skill |
|------|------|-------|
| 1 | Create worktree | `worktree` |
| 2 | Self-check (spec compliance) | `quality-gate` |
| 3 | Peer review | `request-review` / `receive-review` |
| 4 | Merge gate | `merge-gate` |
| 5 | PR + cloud review | (merge-gate handles) |
| 6 | Merge + cleanup | (SOP steps) |

## Code Quality

- CI: `.github/workflows/android-a-plus.yml` runs provenance plus independent Gradle unit-test and assemble gates for each app.
- Lint: the same workflow runs a per-app lint debt ratchet via `scripts/check-inherited-lint-debt.sh`.
- File limits: no repository file-size limit is configured.
