---
topics: [backlog]
doc_kind: note
created: 2026-08-09
---

# Feature Roadmap

> **Rules**: Only active Features (idea/spec/in-progress/review). Move to done after completion.
> Details in `docs/features/Fxxx-*.md`.

| ID | Name | Status | Owner | Link |
|----|------|--------|-------|------|
| F-10 | 千网游遗留 user_version=0 非 Room 库致升级首访 DB 必崩（不塞 #7，待 operator 裁定是否 release-blocking） | idea | 待 operator 分派 | [docs/features/2026-08-24-f10-qwy-legacy-db-upgrade-crash.md](docs/features/2026-08-24-f10-qwy-legacy-db-upgrade-crash.md) |
| F-19 | Auto 启动闪退：Room v5 schema 漂移无迁移路径（operator 裁 B：v6 + 漂移隔离区 + destructive fallback，INV-24 范围内豁免） | merged·apply-pending (PR #51) | fable-5（修）· glm52（审 APPROVE）· codex-terra（监督） | [docs/features/2026-08-27-f19-auto-room-v5-drift-quarantine-rebuild.md](docs/features/2026-08-27-f19-auto-room-v5-drift-quarantine-rebuild.md) |
