---
feature_ids: [F-17]
related_features: [F-14]
topics: [qianwangyou, preflight, verification-level, honesty, audit, provider]
doc_kind: spec
created: 2026-08-25
---

# F-17: preflight 硬编码 VERIFIED —— F-14 的第二张面孔 + verification-level 轴全轴穷举 audit

> Status: fixed（红→绿证据齐；review 进行中） | Line: 调度线 G1 闸并行子线 | Dev: @glm52

## 为什么先 audit 再修

F-14 修复时做过 `earnedScheduleRef` 轴的 failure-mode audit（12 处 surface，结论"无第四处"），
**但从没做过 verification-level 轴的**。于是 `EnvironmentControlHandler.kt:113` 躲过了三轮
review + 一次 audit + CI 6/6。这条线已经三次靠 review 一处处抓
（`Handler:247` → `IntegrationTypes` → `LeaseStore`），第四次不能靠运气——所以本次交付的
主体是"扫过了"，修复是扫描的副产品。

## 缺陷本体

```
apps/qianwangyou/.../integration/v1/EnvironmentControlHandler.kt:113
    achievableVerificationLevelWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire
```

preflight **无条件**宣称"系统 mock 已独立验证"可达，而真实 apply 会因 gateway 不可用 /
无 current schedule item / item 无 qwy 坐标而根本走不到发布，或发布后实测 NONE。
与 F-14 原始缺陷（`:247`，apply receipt 常量）**同文件、同常量、同形状**——那是它的第一张面孔。

## 穷举方法（可复现命令）

对 `83477aa`（= main，即 #41 分支 base）执行四刀：

1. `rg -n "SYSTEM_MOCK_INDEPENDENTLY_VERIFIED"` 全仓（生产/测试/codec/docs/contracts/AIDL 产物）
2. `rg -n "VerificationLevelV1\."` 生产 main source set（apps + contracts + acceptance）
3. **魔法数字刀**：`(verificationLevelWire|achievableVerificationLevelWire|supportedVerificationLevelWires|verificationWire)\s*=\s*[0-9]`（枚举名都不写的直写 int——第二张面孔的变体家族）。范围 = 生产 main source set：`apps contracts acceptance` 且排除 `**/src/test/**`、`**/src/matrixTest/**`、`*.md`、`*.yaml` → 该范围内**零命中**
4. **字符串刀**：`"SYSTEM_MOCK_INDEPENDENTLY_VERIFIED"` 字面（字符串形式绕过枚举），同上生产范围（`apps` main source set）→ 该范围内**仅 `TrustPolicy.kt:137`**（消费侧比较常量，合法——是直写但不属 claim 型）

AIDL：仓库无手写 .aidl 源（仅 build 中间产物，字段走共享 Parcelable 库），无第二真相源。

## Surface 清单与逐个判定

**claim 型**（宣称可达/已达成——字面即谎言风险）：

| # | 位置 | 字段 | 形状 | 判定 |
|---|------|------|------|------|
| 1 | `EnvironmentControlHandler.kt:77` | discover `supportedVerificationLevelWires` | 字面 | **合法**：机制能力广告（"实现了哪种验证机制"），per-call 上限是 preflight 的职责；语义分界见"遗留观察" |
| 2 | `EnvironmentControlHandler.kt:113` | preflight `achievableVerificationLevelWire` | 字面 | **缺陷（本线）** → 已修：消费 `environment.achievableVerificationLevelWire()` |
| 3 | `EnvironmentControlHandler.kt:247` | apply receipt `verificationLevelWire` | 字面 | **缺陷 = F-14**，#41 已修待合（`applyOutcome.verificationLevelWire`，真实 publish 结果计算） |
| 4 | `QwyEnvironmentController.kt:198-202` | apply `verificationLevelWire` | **计算**（`published ? VERIFIED : NONE`） | 真相源 ✓ |
| 5 | `QwyEnvironmentController.kt:310-314` | observe `verificationLevelWire` | **计算**（`isMock ? VERIFIED : NONE`） | ✓ |
| 6 | `FakeQwyProvider.kt:261`（discover）/`:312`（preflight）/`:386`（apply） | 同名字段 | 字面 | **合法（验收假件）**：它建模的理想 provider 里 apply 恒成功，VERIFIED 在其世界内为真。观察：若验收将来要"preflight 不诚实"负例，需加注入钩子 |
| 7 | `FakeQwyProvider.kt:810-812`（observe） | verificationLevelWire | **计算**（`coordinatesVerified` 分支） | ✓ |
| 8 | 测试树 `Fakes.kt` FakeQwyEnvironment（apply/observe）+ `WiringProbeEnvironment` | 同名字段 | 字面 | **合法（fixture/probe）**；本次随接口扩展改为诚实实现（见下） |

**requirement / comparison 型**（写合法——是需求输入或消费侧校验，不是能力宣称）：

- `APlusOperationIdentity.kt:57` intent `requiredVerificationWire = VERIFIED`（Auto 的信任需求输入）
- `TrustPolicy.kt:82,137`（exact-match 比较，README 规则 3 的实现）
- `ContractResponseValidator.kt:90` / `ObservationWireAdapter.kt:25`（fromWire 判空/命名映射）
- 契约枚举定义 `ContractEnumsV1.kt:40`、`compatibility.yaml:36`、README/spec 文本
- 全部矩阵/引擎/契约测试 fixture（构造期望状态）

## 结论

**verification-level 轴上，生产代码的 claim 型字面赋值共 3 处：`:113`（本线，已修）、`:247`
（F-14，#41 待合）、`FakeQwyProvider` 2 处（验收假件，其模型内为真）。除 `:113` 外无第四处
生产缺陷。已穷举。** requirement/comparison 型字面全部合法。绕过形式（第 3/4 刀）**在生产
claim 型扫描范围内（apps/contracts/acceptance 的 main source set）：魔法数字直写为零；字符串
直写仅 `TrustPolicy.kt:137` 一处（消费侧比较常量，合法）**。该"零/仅一"声明不覆盖测试与
matrixTest fixture 树——那里存在**有意的**字面直写与字符串直写（构造期望状态，见清单 #8 及
requirement 型末行），属 fixture 豁免，不构成生产绕过。

## 修复（红→绿）

语义冻结（含 KDoc）：`achievableVerificationLevelWire` = **apply 前可知的能力上限**——镜像
`applyEnvironment()` 自身的门槛（gateway 可构造、有 current item、该 item 有 qwy 坐标），
任一不满足 → `NONE`。它**不是** apply 结果的预测：publish 结果只在 apply 时可测，receipt
（F-14 修法）与 observe() 仍是 achieved level 的唯一可信来源。

- 红：`PreflightProviderRedTest`（新增 3 例）在未修 main 上 **2 RED**（无坐标/无 item 时
  preflight 仍报 VERIFIED：`expected:<3(NONE)> but was:<1(VERIFIED)>`）
- 修：`QwyEnvironment` 接口 +`achievableVerificationLevelWire()`；controller 实现按上述三门槛
  计算；`Handler:113` 消费之；`FakeQwyEnvironment` 诚实镜像（坐标缺失→NONE）；
  `WiringProbeEnvironment`（reachability probe，无任何能力建模）→ `NONE` 下限
- 绿：目标测试 3/3；qianwangyou 全量 **699/699**；cellrebel-auto `compileDebugKotlin` PASS
  （该侧仅注释）

CI 6/6 在缺陷存在时也是绿的——**别把 CI 绿当证据**，红测试才是。

## Auto 侧消费判定（判据 3）：暂不消费，理由已记录在消费点

记录位置：`AutomationEngine.kt` preflight 消费点注释（F-17 标记）。理由：
preflight achievable 是 **apply 前能力上限**，不是 apply 结果预测；信任判定已全部运行在
**实测**级别上（apply receipt `verificationLevelWire` 经 `ContractResponseValidator`、
observe 级别经 `TrustPolicy`），在 preflight 再 gate 一道只会增加第二条更弱的拒绝路径，
不增加任何安全性。将来若想软记录 achievable=NONE 与 ALLOWED_NOW 并存的分歧，那是
observability 变更，不是 trust 变更。

## 遗留观察（不阻塞本线，供 spec owner / 后续裁定）

1. `discover.supportedVerificationLevelWires` 在 gateway 构造失败时仍广告 VERIFIED
   （机制"支持"与"当前可达"的语义分界）。本次不改：属契约语义层，且 #41 正在做
   KDoc 契约裁定，避免撞车。
2. 验收 `FakeQwyProvider` 的 preflight achievable 是常量（理想 provider 模型内为真）；
   若验收矩阵将来需要 preflight 诚实性负例，需加注入钩子。
3. 与 #41 并行关系：不同文件区域（本线：preflight `:109-124` + controller 接口/新方法 +
   Fakes 尾部 + reachability probe；#41：apply `:214-270` + Fakes `:245-270` + 契约错误码）。
   Fakes.kt 两处编辑区不相交，可干净并存；合入次序由调度线定。
