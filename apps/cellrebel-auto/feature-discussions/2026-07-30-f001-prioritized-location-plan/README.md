---
feature_ids: [F001]
topics: [requirements, scheduling, cellrebel, fake-gps]
doc_kind: discussion
created: 2026-07-30
---

# F001 Prioritized Location Plan — Operator Requirements

## Operator experience

The following statements are the authoritative product input from the current thread:

> “我会给你一份清单，里面有经度 纬度 优先级 次数。这些信息贯穿我们app的业务。”

> “经度 和 纬度 是我们在fake gps 中修改地址的清单。”

> “后面的次数为这个循环，需要在这个点的cellrebel成功上传的次数。”

> “优先级是数字越小越高。”

> “相同优先级按清单顺序。”

> “缓冲时间是全局统一。”

The operator provided two CellRebel screenshots and defined the first as completed and the second as still testing.

## Screen-state evidence

### Completed

- Both score cards are fully rendered.
- Each card shows a rating and numeric score.
- No processing overlay text is visible.
- The Start button appears active.

### Running

- `Processing results...` is visible over the web score card.
- `Measuring video streaming quality...` is visible over the video score card.
- Progress bars are visible.
- The Start button appears disabled.
- Crucially, the prior rating and numeric scores remain faintly visible behind the running overlay.

## Derived product rules

1. Score presence is not completion evidence.
2. Every counted success must observe a fresh transition from ready/completed to running and back to completed.
3. Numerical score change is not required because two valid runs may have identical results.
4. A task's `required_successes` is a success quota, not a retry limit.
5. Failed attempts remain visible and do not consume quota.
6. The scheduler completes the current row's quota before advancing.
7. Priority sorts ascending; source order breaks ties.
8. One global buffer separates terminal attempts from the next Start action.

## Proposed input contract

Until the real sample arrives, the reversible dependency-free contract is CSV:

```csv
longitude,latitude,priority,required_successes
116.397000,39.908000,1,3
121.474000,31.230000,1,5
```

The implementation must isolate parsing from scheduling so a later source adapter does not change domain semantics.

## Design Gate status

- Core journey and scheduling semantics: confirmed by operator.
- CellRebel completed/running visual semantics: confirmed by operator.
- Input surface and progress UI: proposed in the F001 spec; implementation owner must return an in-context wireframe before modifying Compose.
- Target-device accessibility anchors: technical discovery required from tree dumps.

## Priority

P0. This replaces the random bounding-box loop as the product's central business workflow.
