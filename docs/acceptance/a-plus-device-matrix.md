---
feature_ids: [1, 6, 7]
topics: [acceptance, device-matrix, a-plus, g2, journey-fixture]
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

Container and per-row schema are frozen by canonical spec §10.1 (evidence
manifest): the file is a bare JSON **array** — no wrapper object, no comments,
directly `JSON.parse`-able — with one record per executed canonical device row:

```json
{
  "rowId": "M-CO-06",
  "exactHead": "<full 40-char git SHA of the execution candidate>",
  "lane": "device",
  "status": "passed",
  "testId": "docs/acceptance/a-plus-device-matrix.md#M-CO-06",
  "reportDigest": "<64 lowercase hex chars — SHA-256 of the raw device evidence file bytes, NO prefix>"
}
```

- **`status` vocabulary is frozen** (canonical §10.1 per-status table):
  `passed | failed | skipped | deferred`. The uppercase `PASS|FAIL` shown by
  earlier drafts of this template is not a parseable ledger value.
- **`reportDigest` in this machine ledger is canonical 64-char lowercase hex
  with no prefix** (canonical §10.1; G2 package §4.3). The `sha256:<hex>` form
  is the human-readable surface for prose reports only; the two surfaces must
  not be copy-mixed.
- **Row vocabulary**: only the canonical `device`-class rows `M-CO-06` and
  `M-VS-01` may appear here (G2 package §4.4). The 10-address journey,
  revocation, crash-recovery and Hook device evidence enters the G2 evidence
  report per Task 9 — it is never minted as new rows in this ledger.

### Initial state (pre-execution, created 2026-08-26 per G2 §3.3-2)

`matrix-evidence-device.json` exists as an **empty array** `[]` and stays empty
until rows actually execute inside an authorized device lease, because no
honest pre-execution row is expressible in the frozen schema:

- a `passed` / `failed` / `skipped` row **requires** a real `testId` +
  `reportDigest` pointing at a byte-identical raw report under
  `docs/acceptance/**`; before execution no such report exists, and §10.1
  forbids fabricating one ("摘要是指向证据的指针；找不到被指向物就等于没有证据");
- a `deferred` row is forbidden here: `M-CO-06` waits on no DP, and for
  `M-VS-01` the accepted `SKEW=POST_V1` choice explicitly must **not** mint a
  deferred ledger row (G2 package §5.D).

Rows are appended only together with their raw evidence files, each bound to
the execution candidate's `exactHead`.

---

## G2 10-Address Journey Fixture — `FX-G2-10A` v1（已冻结 2026-08-26）

> Commissioned by `docs/acceptance/issue7-g2-acceptance-package.md` §3.3
> readiness gap 1，执行谓词见其 §5 block A。这十项是 **Task 9 用户旅程
> case，不是 §10 矩阵行**——按 G2 包 §4.4，它们的 device 证据进入 G2
> evidence report，**不得**为其铸造新 `M-*` 行，也永远不出现在
> `matrix-evidence-device.json` 里。

**Fixture 真相源（机器可读）**：`docs/acceptance/a-plus-10a-fixture.json`
**冻结 digest（对该文件字节的 SHA-256，无前缀）**：
`cab16da8f7776b208a2bcf25acbd22ef9ca8e8ec9a08169d5f5f3ce3e8027852`

验证命令：`shasum -a 256 docs/acceptance/a-plus-10a-fixture.json`

fixture 文件任何字节变化 = `fixtureVersion` 递增 + 在本节重新登记 digest，
且必须发生在**编排之前**；session 开始后的变化使该 session 的旅程证据
stale（G2 包 §1）。下方两张表是 fixture 文件的人读投影——不一致时以
JSON 文件为准。

### 旅程计划（投影）

| # | journey case | expected `scheduleItemId` | `requiredSuccesses` | 公开位置 |
|---|---|---|---|---|
| 1 | `J10A-01` | `profile-1` | 2 | Maidan Nezalezhnosti (Independence Square), Kyiv |
| 2 | `J10A-02` | `profile-2` | 1 | Golden Gate (Zoloti Vorota), Kyiv |
| 3 | `J10A-03` | `profile-3` | 3 | St. Sophia's Cathedral square, Kyiv |
| 4 | `J10A-04` | `profile-4` | 1 | Taras Shevchenko Park, Kyiv |
| 5 | `J10A-05` | `profile-5` | 2 | Kyiv-Pasazhyrskyi railway terminal forecourt, Kyiv |
| 6 | `J10A-06` | `profile-6` | 1 | NSC Olimpiyskiy stadium, Kyiv |
| 7 | `J10A-07` | `profile-7` | 1 | Kontraktova Square (Podil), Kyiv |
| 8 | `J10A-08` | `profile-8` | 3 | Kyiv Zoo main entrance, Kyiv |
| 9 | `J10A-09` | `profile-9` | 1 | Expocenter of Ukraine (VDNH) main gate, Kyiv |
| 10 | `J10A-10` | `profile-10` | 2 | Motherland Monument (National WWII Museum), Kyiv |

合计需要 **17** 次可信成功（lease 预算输入；每次可信成功 = 一个完整的
apply → observe → CellRebel 可信完成周期。不作墙钟承诺，G2 包 §6）。

每项旅程形态统一：apply → observe 三腿 tuple 相符 → CellRebel attempt(s)
直至该项配额 → release-before-advance → advance receipt 重算验证。逐项
承重谓词以 G2 包 §5 block A 为准，本节不复制。

### 环境 tuple（provider 侧种子；投影）

| item | lat | lng | alt | accuracy | tac | wifiSsid |
|---|---|---|---|---|---|---|
| `profile-1` | 50.4501 | 30.5234 | 179.0 | 3.0 | 27101 | `G2-A10-01` |
| `profile-2` | 50.4489 | 30.5133 | 182.0 | 3.0 | 27102 | `G2-A10-02` |
| `profile-3` | 50.4530 | 30.5145 | 183.0 | 3.0 | 27103 | `G2-A10-03` |
| `profile-4` | 50.4413 | 30.5110 | 178.0 | 3.0 | 27104 | `G2-A10-04` |
| `profile-5` | 50.4398 | 30.4890 | 130.0 | 3.0 | 27105 | `G2-A10-05` |
| `profile-6` | 50.4334 | 30.5216 | 145.0 | 3.0 | 27106 | `G2-A10-06` |
| `profile-7` | 50.4636 | 30.5175 | 100.0 | 3.0 | 27107 | `G2-A10-07` |
| `profile-8` | 50.4547 | 30.4622 | 175.0 | 3.0 | 27108 | `G2-A10-08` |
| `profile-9` | 50.3800 | 30.4766 | 155.0 | 3.0 | 27109 | `G2-A10-09` |
| `profile-10` | 50.4266 | 30.5631 | 160.0 | 3.0 | 27110 | `G2-A10-10` |

- **坐标以数字为准**：表中数值就是冻结的 mock 输入，provider 逐字消费；
  地标名只用于证明位置公开（PROD=G3 / G2 包 §3.2：只用公开测试坐标与
  隔离 `.bench` schedule/profile，不指向任何 production 用户数据）。
  真实世界测绘精度不承重。`tac` / `wifiSsid` 为合成 bench 值，不是对
  真实网络的断言。
- item 1 与 C5 run 2 种子基线（Kyiv `50.4501, 30.5234`, alt `179.0`）
  刻意同值——第一项旅程直接续接已验证过的环境连续性。
- 相邻项间距约 0.5–7 km，位置/蜂窝/Wi-Fi 三轴同步变化，保证每次
  release-before-advance 的环境切换在 observe 腿上可判。

### 配额分布为什么长这样

- 取值在 {1, 2, 3} 间变化：跑完即证明配额是 **per-item** 的，不是全局
  常量（全 1 或全 N 的 fixture 证不出这一点）。
- **item 1 = 2**：第一项就证明「配额已提交但未达标 → 不推进」
  （M-AD-14 语义的真机对应），在任何 advance 发生之前。
- **item 3 / item 8 = 3**：run 早期与后期各出现一次连续两次「成功但
  不推进」的保持段。
- **item 10 = 2**：配额门禁作用于**耗尽收口边**——第 1 次成功后不得
  出现 `EXHAUSTED`；第 2 次成功后恰好推进一次进入可独立回读的
  `EXHAUSTED`（`advancedToItemId = null`），不回绕（G2 包 §5.A）。

### 种子契约（绑定执行，缺一即 seed FAIL）

1. **Fresh `.bench` 安装**（C5 run 2 先例）或可证明为空的 profile 表：
   `scheduleItemId` 由 ProfileEntity 插入的 `profile-{dbId}` 派生
   （`QwyScheduleStore`），expected ids `profile-1..profile-10` 只在
   干净表上成立。
2. 按 fixture 顺序（fixtureIndex 1→10）种入十条 profile，再以这些
   profile ids 初始化 schedule（`scheduleId = qwy-default-schedule`）。
3. **readback 验证强制**：种子后 `discover()` 必须回读出
   `profile-1..profile-10` 的完整有序列表且 `currentItemId = profile-1`，
   run log 记录种子后的 `scheduleVersion` 基线。任何不符 = **seed
   FAIL**：清空重种；**禁止**把旅程 case 重映射到意外的 item id 上。
4. Auto 侧 plan 只消费 `{顺序, journeyCaseId, requiredSuccesses}`；
   坐标不跨边界（canonical §6.7.1，KB-8）。

### 同场伴随项

`M-CO-06` 在同一 device session（同 candidate、同设备配置、同 schedule
generation）内按其下方章节独立执行（G2 包 §5.A 末条）；其证据是
canonical ledger 行，与旅程证据分开承载。

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
