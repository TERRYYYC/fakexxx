---
feature_ids: [F002]
topics: [android, fake-gps, location-precision, verification, plan]
doc_kind: plan
created: 2026-08-02
---

# F002 Precise Location Selection & Spoof Verification — Implementation Plan

**Feature:** F002 — `docs/features/F002-precise-location-verification.md` (v2.1, baseline `cf91235`)
**Goal:** 每次配额相关事件前都有独立位置验证闸门（L1），并交付至少一条可用的精确选点路径（L2）。
**Acceptance Criteria:**（逐条抄自 spec v2.1）

- AC-F2-1: gate 在每次 CellRebel attempt 与每次 `ok_gps_only` 计配额前运行（两个 stage toggle 任意 OFF 也不例外）；7 种 typed failure 均不占配额；fake location provider 单测。
- AC-F2-2: mock 检测用 `Location.isMock()`；不用 satellites / 纯 last-known 启发式；新鲜度 = 单调时间戳 + 显式 budget，且（Location ON）fix 不早于本次激活锚点。
- AC-F2-2b: 永久失败（permission denied/revoked、coarse-only）启动前拦截或运行中暂停 session，绝不进 buffer 重试环；可恢复失败只在全局缓冲后重试。
- AC-F2-3: 审计记录 actual lat/lng、error meters、mock 标志、fix/verified 时间戳、accuracy、使用的 tolerance；History + 导出可见（v5 增量迁移 + 测试）。
- AC-F2-4: 至少一条精确选点路径交付并在设备证明：非可寻址坐标 ≥9/10 在容差内。
- AC-F2-5: moto g54 设备验证：地址吸附点（~1km 误差）被闸门拒绝，L2 精确路径被接受。

**Map delta:** none
**Map delta why:** 本项目无 ownership map（docs/architecture 不存在），沿用 F001/F003 的 engine/handler/db 分层。
**Architecture:** 新增纯 Kotlin 闸门决策层（`LocationGateLogic`，JVM 可测）+ Android 采样接缝（`AndroidLocationGate`，LocationManagerCompat）；引擎在位置阶段后、测试阶段前插入闸门；Room v5 增量列承载审计。
**Tech Stack:** Kotlin, Room 2.7.1 (KSP), DataStore, Robolectric 4.14.1, LocationManagerCompat (androidx.core 1.15.0)
**前端验证:** No（Android 设备验证走 adb + moto g54 实机）

---

## Straight-Line Check

- **终点 B**：所有配额事件先过闸门；审计列落库可见；至少一条精确选点路径在设备上被闸门接受。
- **终态 schema**：`ObservedFix` / `GateAnchor` / `LocationAudit` / `LocationGateResult`（见 Task 1）；`test_attempts` v5 八列（见 Task 2）；`FailureReason` 新增 7 个枚举值。
- **不做什么**：不替换调度/流水线（F001/F003 稳定）；不做 geocoding；不做 real-location bypass；不碰 main 治理文件与 F001 未跟踪 device evidence。

## Stateful Object Gate（普查）

有生命周期对象：

1. **`test_attempts` 行（新增 8 审计列）** — 生命周期 owner 仍是 engine finalize 路径；审计列只经 `recordLocationAudit` 写入，finalize 的 UPDATE 语句不得触碰它们。
   - 转移表：starting → (gate 拒绝: failed w/ typed reason + audit) | (gate 通过: audit 写入 → running → succeeded/failed/interrupted)。
   - 对抗场景：audit 写入后、finalize 前崩溃 → 恢复清扫标 interrupted，审计列保留（诚实记录）→ 测试覆盖。
2. **`run_sessions.status`（新增 "paused" 终态）** — owner 为 engine；唯一写入点：mid-run 永久失败分支。恢复路径：operator 再按 Start（recovery sweep 幂等，INV-9 已有）。
   - 对抗场景：paused 后重启 → sweep 将残留非终态 attempt 标 interrupted，不重复计配额 → 已有 EngineRecoveryTest 语义覆盖 + 新增 paused 用例。
3. **`AutomationState`（新增 `PAUSED`）** — 纯 UI 投影枚举，无持久化。
4. **DataStore `location_tolerance_meters`** — 运行时偏好，与 stage toggles 同生命周期；per-attempt 快照读取（OQ-F2-1 已定：snapshot per attempt）。

旁路 API 禁令：禁止任何代码路径绕过 `LocationGate.verify` 直接 finalize `ok_gps_only` 或启动 CellRebel。

### 不变量（INV-F2-x）

- **INV-F2-1**: 闸门在每次 CellRebel 启动与每次 `ok_gps_only` 收尾前运行，stage toggle 任意组合不例外。可测：引擎集成测试 ×4 种 toggle 组合。
- **INV-F2-2**: 7 种位置 typed failure 均不产生配额自增。可测：`finalizeAttemptFailure` 路径 + task.completedSuccesses 断言。
- **INV-F2-3**: 永久失败（PERMISSION_DENIED / APPROXIMATE_ONLY）→ attempt 终态化 + session "paused" + state PAUSED，不进重试环。可测：mid-run 撤销用例断言无第二次 attempt。
- **INV-F2-4**: 审计列仅由 `recordLocationAudit` 写；finalize success/failure 的 UPDATE 不清空它们。可测：DAO SQL 审查 + 集成断言。
- **INV-F2-5**: `stage_notes` 保持 F003 枚举语义，不承载坐标。可测：现有 AttemptCsvMapperTest 不回归。

---

## Task 1: FailureReason + 纯闸门决策逻辑（TDD）

**Files:**
- Modify: `app/src/main/java/com/example/cellrebelauto/automation/AttemptOutcome.kt`（FailureReason +7）
- Create: `app/src/main/java/com/example/cellrebelauto/automation/LocationGate.kt`
- Test: `app/src/test/java/com/example/cellrebelauto/automation/LocationGateTest.kt`

**终态类型（完整签名）：**

```kotlin
// AttemptOutcome.kt — FailureReason 追加：
LOCATION_PERMISSION_DENIED, LOCATION_APPROXIMATE_ONLY,
LOCATION_NO_FIX, LOCATION_STALE_FIX, LOCATION_VERIFY_TIMEOUT,
LOCATION_NOT_MOCKED, LOCATION_MISMATCH

// LocationGate.kt（纯 Kotlin，无 android.location 依赖）
data class ObservedFix(
    val latitude: Double, val longitude: Double,
    val accuracyMeters: Float?,          // null = !hasAccuracy()
    val isMock: Boolean,
    val elapsedRealtimeNanos: Long,      // 单调 fix 时间戳
    val fixAtMs: Long                    // wall clock（Location.time）
)

sealed interface GateAnchor {
    // Location stage ON：fix 不得早于本次 Fake GPS 激活观察点
    data class ActivationAnchor(val anchorNanos: Long) : GateAnchor
    // Location stage OFF：验证时采样新鲜 fix，age ≤ FRESH_FIX_BUDGET
    data object FreshFix : GateAnchor
}

data class LocationAudit(               // 1:1 映射 Room v5 列
    val actualLatitude: Double?, val actualLongitude: Double?,
    val locationErrorMeters: Double?, val fixIsMock: Boolean?,
    val fixAt: Long?, val verifiedAt: Long,
    val fixAccuracyMeters: Double?, val toleranceMetersUsed: Double
)

sealed interface LocationGateResult {
    data class Verified(val audit: LocationAudit) : LocationGateResult
    data class Rejected(val reason: FailureReason, val audit: LocationAudit?) : LocationGateResult
}

enum class LocationPermissionState { FINE, COARSE_ONLY, DENIED }

interface LocationPermissionChecker { fun current(): LocationPermissionState }

interface LocationFixSampler {
    suspend fun sampleFix(): SampleResult   // 实现内部自带超时
}
sealed interface SampleResult {
    data class Fix(val fix: ObservedFix) : SampleResult
    data object NoFix : SampleResult        // provider 无信号/回调 null
    data object Timeout : SampleResult      // 采样超时
}

object LocationGateLogic {
    const val DEFAULT_TOLERANCE_METERS = 100.0
    const val FRESH_FIX_BUDGET_MS = 10_000L

    fun isPermanentFailure(reason: FailureReason): Boolean =
        reason == FailureReason.LOCATION_PERMISSION_DENIED ||
        reason == FailureReason.LOCATION_APPROXIMATE_ONLY

    fun evaluateFix(
        fix: ObservedFix, targetLat: Double, targetLng: Double,
        toleranceMeters: Double, anchor: GateAnchor,
        nowNanos: Long, verifiedAtMs: Long,
        distanceMeters: (Double, Double, Double, Double) -> Float
    ): LocationGateResult
    // 判定顺序：1) !isMock → NOT_MOCKED（带 audit）
    //           2) ActivationAnchor: fix.elapsedRealtimeNanos < anchor → STALE_FIX
    //              FreshFix: age > budget → STALE_FIX；!hasAccuracy → NO_FIX
    //           3) distance > tolerance → MISMATCH（audit 带 actual+distance）
    //           4) Verified
}

class LocationGate(
    private val permissionChecker: LocationPermissionChecker,
    private val sampler: LocationFixSampler,
    private val nowNanos: () -> Long,
    private val nowMs: () -> Long,
    private val distanceMeters: (Double, Double, Double, Double) -> Float
) {
    fun preflight(): LocationPermissionState = permissionChecker.current()
    suspend fun verify(
        targetLat: Double, targetLng: Double,
        anchor: GateAnchor, toleranceMeters: Double
    ): LocationGateResult {
        when (permissionChecker.current()) {
            LocationPermissionState.DENIED -> return Rejected(LOCATION_PERMISSION_DENIED, null)
            LocationPermissionState.COARSE_ONLY -> return Rejected(LOCATION_APPROXIMATE_ONLY, null)
            LocationPermissionState.FINE -> {}
        }
        return when (val s = sampler.sampleFix()) {
            SampleResult.Timeout -> Rejected(LOCATION_VERIFY_TIMEOUT, null)
            SampleResult.NoFix -> Rejected(LOCATION_NO_FIX, null)
            is SampleResult.Fix -> LocationGateLogic.evaluateFix(...)
        }
    }
}
```

**Test steps（红→绿）：**
1. mock fix 在容差内 + anchor OK → Verified，audit 八字段正确（含 toleranceMetersUsed 回显）
2. `isMock=false` → NOT_MOCKED，audit 带 fix 坐标
3. ActivationAnchor：fix 早于 anchor 1ns → STALE_FIX；等于 anchor → Verified（≥ 语义）
4. FreshFix：age > 10s → STALE_FIX；age ≤ 10s → Verified
5. FreshFix：accuracyMeters=null → NO_FIX（ActivationAnchor 模式不要求 accuracy，仍 Verified）
6. distance = tolerance+0.1 → MISMATCH，audit 带 actual 坐标与 distance
7. sampler Timeout → VERIFY_TIMEOUT；NoFix → NO_FIX
8. permission DENIED → PERMISSION_DENIED 且 sampler 未被调用（mock 计数断言）
9. permission COARSE_ONLY → APPROXIMATE_ONLY 且 sampler 未被调用
10. `isPermanentFailure` 分类表：7 个新 reason 中恰好 2 个永久

Run: `./gradlew :app:testDebugUnitTest --tests "*LocationGateTest*"` → 全绿后 commit。

## Task 2: Room v5 审计列 + 迁移（TDD）

**Files:**
- Modify: `model/plan/Entities.kt`（TestAttempt +8 可空列，注释升 v5）
- Modify: `db/AppDatabase.kt`（version 5，MIGRATION_4_5，addMigrations 注册）
- Modify: `db/TestAttemptDao.kt`（+`recordLocationAudit` UPDATE）
- Modify: `repository/PlanRepository.kt`（+`recordLocationAudit(attemptId, audit)`）
- Test: `db/MigrationTest.kt`（+4→5 用例）、`db/PlanSchemaTest.kt`（列清单更新）

**v5 列（全部 ADD COLUMN，可空，不动存量数据）：**

```sql
ALTER TABLE test_attempts ADD COLUMN actualLatitude REAL
ALTER TABLE test_attempts ADD COLUMN actualLongitude REAL
ALTER TABLE test_attempts ADD COLUMN locationErrorMeters REAL
ALTER TABLE test_attempts ADD COLUMN fixIsMock INTEGER
ALTER TABLE test_attempts ADD COLUMN fixAt INTEGER
ALTER TABLE test_attempts ADD COLUMN verifiedAt INTEGER
ALTER TABLE test_attempts ADD COLUMN fixAccuracyMeters REAL
ALTER TABLE test_attempts ADD COLUMN toleranceMetersUsed REAL
```

Entity 字段（Kotlin 类型）：`actualLatitude: Double? = null` 等同名 8 个；`fixIsMock: Boolean?`、`fixAt/verifiedAt: Long?`。

DAO：
```kotlin
@Query("""UPDATE test_attempts SET actualLatitude=:lat, actualLongitude=:lng,
  locationErrorMeters=:err, fixIsMock=:isMock, fixAt=:fixAt, verifiedAt=:verifiedAt,
  fixAccuracyMeters=:acc, toleranceMetersUsed=:tol WHERE id=:attemptId""")
suspend fun recordLocationAudit(attemptId: Long, lat: Double?, lng: Double?, err: Double?,
  isMock: Boolean?, fixAt: Long?, verifiedAt: Long?, acc: Double?, tol: Double?)
```

**Test steps：**
1. 手工建 v4 文件库（含 stageNotes 数据行）→ Room v5 + 全迁移链打开 → 旧行数据保留、新列为 NULL（红→绿）
2. `recordLocationAudit` 写入后读回八字段一致；随后 `markSucceeded` 再读回审计列未被清空（INV-F2-4）
3. PlanSchemaTest 列清单含 8 新列

## Task 3: 引擎闸门集成（TDD）

**Files:**
- Modify: `automation/AutomationEngine.kt`
- Modify: `model/AutomationState.kt`（+`PAUSED("Paused")`）
- Test: 新增 `automation/EngineLocationGateTest.kt`；更新 `EngineStageToggleTest.kt`、`EngineRecoveryTest.kt`、`EngineForwardingTest.kt` 的引擎构造调用

**引擎构造新增参数（无默认 → 编译器逼所有调用点显式决策）：**

```kotlin
private val locationGate: LocationGate,
private val locationToleranceMeters: suspend () -> Double,
private val elapsedRealtimeNanos: () -> Long,   // 生产 = SystemClock.elapsedRealtimeNanos()
```

**run() 改动点：**
1. **Pre-start preflight**（both-off guard 与 plan 校验之后、`createSession` 之前）：
   `locationGate.preflight() != FINE` → log 明确原因 + `ERROR` + return；**不建 session、不建 attempt**（spec pre-start 类）。
2. **激活锚点捕获**：Location stage ON 且 `GpsOutcome.Active` 的下一行立即 `anchorNanos = elapsedRealtimeNanos()`（在 settle 等待之前）。
3. **闸门调用点**：位置阶段（含 settle）之后、test-stage 分支之前：
   ```kotlin
   val anchor = if (toggles.locationStageEnabled)
       GateAnchor.ActivationAnchor(anchorNanos!!) else GateAnchor.FreshFix
   val gateResult = locationGate.verify(task.latitude, task.longitude, anchor,
       locationToleranceMeters())
   when (gateResult) {
       is Rejected -> {
           gateResult.audit?.let { planRepository.recordLocationAudit(attemptId, it) }
           planRepository.finalizeAttemptFailure(attemptId, reason.name, nowMs())
           if (LocationGateLogic.isPermanentFailure(reason)) {
               // 永久：session 暂停，operator 可见原因，绝不重试（INV-F2-3）
               updateState(PAUSED); finishSession(runSessionId, "paused", ...)
               currentAttemptId = null; return@coroutineScope
           }
           // 可恢复：typed failure 收尾，continue → buffer gate 自然生效（INV-5 血缘）
           updateState(FAILED); _lastFailure...; currentAttemptId = null
           returnToSelf(); tasks = getTasks(planId); continue
       }
       is Verified -> planRepository.recordLocationAudit(attemptId, gateResult.audit)
   }
   ```
4. 闸门通过后才允许进入 `ok_gps_only` 收尾或 CellRebel 启动（INV-F2-1）。

**Test matrix（EngineLocationGateTest，Robolectric + fake gate/sampler）：**
1. 双 stage ON + gate Verified → CellRebel 生命周期完成，attempt 行审计列齐全（AC-F2-1/3）
2. testStage OFF + gate Verified → `ok_gps_only` 计配额（F003 语义不回归）
3. testStage OFF + gate Rejected(MISMATCH) → typed failed，**不计配额**（AC-F2-1 关键回归）
4. locationStage OFF + gate Rejected(NOT_MOCKED) → typed failed，不计配额（gate 不被 toggle 跳过）
5. mid-run DENIED → attempt failed + session "paused" + state PAUSED + 无第二次 attempt（INV-F2-3）
6. pre-start preflight DENIED → 无 session、无 attempt、state ERROR
7. locationStage ON，fake sampler 返回早于 anchor 的 fix → STALE_FIX 失败（AC-F2-2 锚点语义）
8. 可恢复失败后：下一次 attempt 前有 buffer gate 等待（cooldown 投影出现，INV-5 血缘）
9. crash 对抗：audit 写入后中断 → recovery 标 interrupted，审计列保留

存量测试更新：三个已有引擎测试文件的构造调用注入 always-Verified fake gate + 固定 tolerance + 虚拟时钟。

## Task 4: Android 生产实现 + 服务接线

**Files:**
- Create: `automation/AndroidLocationGate.kt`
- Modify: `automation/AutomationService.kt`（组装并注入 engine）

**AndroidLocationGate：**
- permissionChecker：`ContextCompat.checkSelfPermission` FINE→FINE；COARSE only→COARSE_ONLY；else DENIED
- sampler：`LocationManagerCompat.getCurrentLocation`（SDK≥31 用 `LocationManager.FUSED_PROVIDER`，否则 `GPS_PROVIDER`），`suspendCancellableCoroutine` 包 `CancellationToken`；`withTimeoutOrNull(SAMPLE_TIMEOUT_MS=15_000)` null→Timeout；回调 location==null→NoFix
- isMock：SDK≥31 `location.isMock`，否则 `@Suppress("DEPRECATION") isFromMockProvider`（minSdk 26 唯一正确选项；目标设备 API 35 走 isMock，满足 AC-F2-2 合同）
- fix 字段：`elapsedRealtimeNanos`、`time`→fixAtMs、`accuracy`→accuracyMeters
- distance：`Location.distanceBetween` 注入为 lambda

**Service 接线：** `startWithPlan` 内构建 `AndroidLocationGate(applicationContext)`；`locationToleranceMeters = { configStore.config.first().locationToleranceMeters }`；`elapsedRealtimeNanos = { SystemClock.elapsedRealtimeNanos() }`。

验证：`./gradlew :app:assembleDebug` 通过 + 全量单测绿。

## Task 5: 配置 + UI（容差 / 权限请求流 / History）

**Files:**
- Modify: `model/plan/PlanConfig.kt`（+`locationToleranceMeters: Double = 100.0`）
- Modify: `data/PlanConfigStore.kt`（key + setter）
- Modify: `ui/MainViewModel.kt`（setter + Start 前权限状态暴露）
- Modify: `ui/PlanScreen.kt`（Advanced 容差输入；Start 按钮权限请求流）
- Modify: `ui/MainActivity.kt`（如需 launcher wiring）
- Modify: `ui/HistoryScreen.kt`（行内展示 error meters / mock / actual 坐标）
- Test: `data/PlanConfigStoreTest.kt`（默认 100、独立读写、持久化）

**权限请求流（spec: "if not granted, trigger the request flow"）：**
- Plan 页 Start 按钮 onClick：`ContextCompat` 查 FINE——已授权走原路径；未授权 `ActivityResultLauncher(RequestMultiplePermissions)` 请求 FINE+COARSE
- 结果回调：FINE 授予 → 继续 start；coarse-only → notice "Precise (fine) location required"；denied → notice 明确拒绝原因；均不启动（与引擎 preflight 双保险）

## Task 6: CSV 导出 24 列

**Files:**
- Modify: `util/AttemptCsvMapper.kt`（HEADER +8 尾列；行映射；legacy 行空白）
- Test: `util/AttemptCsvMapperTest.kt`

新增尾列：`actual_latitude, actual_longitude, location_error_meters, fix_is_mock, fix_at, verified_at, fix_accuracy_meters, tolerance_meters_used`。`fix_at`/`verified_at` 走 `formatTs`；`fix_is_mock` → "true"/"false"/""。

## Task 7: L2 spike（设备实测，time-boxed）→ 决策记录

设备已在线：`ZY22JHW9M4`（moto g54 5G, Android 15），两 app 均已装。
方法：adb 驱动 + `uiautomator dump` 树分析 + `dumpsys location` 诊断（仅诊断通道，生产验证用 Task 4 的 in-app API）。

候选顺序（spec §L2）：
1. **长按地图 pin**（当前 hopefactory app）：启动 → 长按地图点 → dump 树查坐标展示/pin 设置对话框 → 若接受精确坐标，对比 `dumpsys location` mock 坐标与目标
2. **Favorites/保存位置流**：同 app 内收藏流程是否支持精确坐标输入
3. **`name.caiyao.fakegps`**：dump 树查直接经纬度输入框
4. 全部失败 → 仅 L1 可关闭，F002 保持 open

产出：`docs/decisions/2026-08-02-f002-l2-precise-path-decision.md`（winner + 证据 + 交互序列）。

## Task 8: L2 winner 实现（TDD 可行部分 + 设备验证）

- winner=长按 pin → `FakeGpsHandler` 新增精确路径（替换 search-snap 序列中的坐标落点步骤；`AccessibilityBridge` 如需加长按手势 `dispatchLongPress`）
- winner=caiyao app → 新 handler（F003 已解耦，位置 app 可换）；包名常量 + 交互序列来自 Task 7 决策记录
- 节点查找/按钮判定逻辑抽纯函数 → JVM 单测（照 `verifyFakeGpsActivation` 模式）

## Task 9: 设备验收（AC-F2-4 / AC-F2-5）

1. assembleDebug + adb install 到 moto g54
2. 非可寻址坐标（park/river 类）10 次 attempt：≥9 次闸门 Verified 且 error ≤ tolerance
3. 地址吸附对照：旧 search-snap 序列落点（~1km 偏差）必须被闸门 MISMATCH 拒绝
4. 证据归档：`feature-discussions/2026-08-02-f002-*/device-acceptance/`（截图 + logcat + 导出 CSV）

## Task 10: 质量门禁 + review 传球

- `./gradlew :app:testDebugUnitTest` 全绿 + `:app:lintDebug` 无新警告 + `assembleDebug`
- spec AC 逐条对照表
- 球传 `@codex-sol`，附红绿证据、设备证据、AC 对照

---

## Open Questions

| # | 问题 | 分类 | 处置 |
|---|------|------|------|
| OQ-F2-1 | tolerance 默认 100m、per-attempt 快照 | 技术 | 已定：默认 100.0，per-attempt lambda 快照，audit 回显实际使用值 |
| OQ-F2-2 | 精确选点 winner | 技术 | Task 7 spike 决定 |
| — | FRESH_FIX_BUDGET=10s / SAMPLE_TIMEOUT=15s | 技术 | 自决（显式常量，可评审） |

## 风险

- `getCurrentLocation` 在 mock-only 环境下的回调行为依赖真机验证 → Task 4 后立刻设备冒烟
- PAUSED 后 operator 恢复路径 = 再按 Start（既有 INV-9 幂等恢复），不新增 resume API
