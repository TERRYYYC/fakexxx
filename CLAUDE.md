<!-- CAT-CAFE-GOVERNANCE-START -->
> Pack version: 1.4.1 | Provider: claude

## Clowder AI Governance Rules (Auto-managed)

### Hard Constraints (immutable)
- **Clowder AI runtime ports**: frontend 3003 and API 3004 are reserved by Clowder AI. Avoid using these ports for this project's dev servers.
- **Redis port 6399** is Clowder AI's production Redis. Never connect to it from external projects. Use 6398 for dev/test.
- **No self-review**: The same individual cannot review their own code. Cross-family review preferred.
- **Identity is constant**: Never impersonate another cat. Identity is a hard constraint.

### Collaboration Standards
- A2A handoff uses five-tuple: What / Why / Tradeoff / Open Questions / Next Action
- Vision Guardian: Read original requirements before starting. AC completion ≠ feature complete.
- Review flow: quality-gate → [fresh-context-review] → request-review → receive-review → merge-gate
- Skills are available via symlinked cat-cafe-skills/ — load the relevant skill before each workflow step
- Shared rules: See cat-cafe-skills/refs/shared-rules.md for full collaboration contract

### Quality Discipline (overrides "try simplest approach first")
- **Bug: find root cause before fixing**. No guess-and-patch. Steps: reproduce → logs → call chain → confirm root cause → fix
- **Uncertain direction: stop → search → ask → confirm → then act**. Never "just try it first"
- **"Done" requires evidence** (tests pass / screenshot / logs). Bug fix = red test first, then green

### Knowledge Engineering
- Documents use YAML frontmatter (feature_ids, topics, doc_kind, created)
- Three-layer info architecture: CLAUDE.md (≤100 lines) → Skills (on-demand) → refs/
- Backlog: BACKLOG.md (hot) → Feature files (warm) → raw docs (cold)
- Feature lifecycle: kickoff → discussion → implementation → review → completion
- SOP: See docs/SOP.md for the 6-step workflow
<!-- CAT-CAFE-GOVERNANCE-END -->

<!-- PROJECT-OWNED (not managed by the governance pack) -->

## 本项目判据（进入本仓前必读）

`docs/lessons/2026-08-30-false-green-taxonomy-and-judgment-rules.md`

真机验收线用实证换来的可复用判据，**不是叙事，是判据**：

- **假绿十形状**——含「替身绿 ≠ 真身绿」（验收判据照着 fake provider 写，从未对真 provider 跑过）
- **双向判据**——新缺陷问「哪个具名 gate 会红」；旧阻塞问「它现在还红吗」。判据不对称会造成路径漂移
- **「能启动 ≠ 能用」**——当天三次同形。装包/环境变更块的离场检查必须问「用户还能不能用」，不得以「我们没留脏」抵账
- **「非 canonical」不豁免**角色分离与命令托管
- **thread 停用判据**——静默时长已被实证证伪，看的是「是否仍持有别处没有的活状态」
- **平台缺陷清单**——typed settlement 422/409、双通道恒 0/0、freshness gate 自相矛盾 HELD：别当猫的问题

> ⚠️ `docs/decisions/` 被 `.git/info/exclude` 排除，是**本地草稿区，不随仓库传递**。
> 需要跨会话/跨调度线存活的结论，写 `docs/lessons/` 或其它被跟踪目录。
