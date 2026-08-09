---
feature_ids:
  - cellular-hook-verification
  - profile-unavailable-state
topics:
  - android
  - xposed
  - review
  - plmn
  - acceptance
doc_kind: bug-report
created: 2026-08-01
---

# Review follow-up: PLMN canonicalization and physical-channel census

## 报告人

深海猫/深深在 PR #3 HEAD `54be3cd76b29d597d79508452caa3adecdbce567`
的独立复审中报告。正式 verdict 已持久化到 PR comment `#issuecomment-5148609550`。

## Bug 诊断胶囊

| 栏位 | 内容 |
|------|------|
| **1. 现象** | 配置 `mnc=0` 时，重建 identity 的 PLMN 是 `46000`，但 API 28+ `getMncString()` hook 返回 `"0"`；physical-channel unavailable 的 `band=0` / `physical_cell_id=-1` 又与空 Builder 默认值相同，动态负对照无法单独识别 getter no-op。 |
| **2. 证据** | `HookUtils` 四个 PLMN string lambda 使用 `String.valueOf`，而 serving constructor 已使用 `Snapshot.resolvePlmnString`；neighbor JSON 仍用 `JSONObject.optString`。矩阵所有 serving MNC 为 260，且 band/PCI no-op 报告会同时通过 exact 与 full-rscp negative-control 比较。 |
| **3. 根因** | 同一个“整数档案值 → Android PLMN string”变换在 getter、constructor、neighbor 和 verifier 中各自表达；physical-channel 四个支持 unavailable 的 getter 都可能与空 Builder 默认值重合，却没有一个由生产注册路径驱动的静态 census。 |
| **4. 诊断策略** | 逆向追踪 profile integer → Snapshot → constructor/getter → probe/verifier，并扫描所有 `mccString`/`mncString`/`String.valueOf`/`optString` sibling；对 physical-channel 比较 snapshot sentinel、Builder 默认值、hook registry 与 matrix control。 |
| **5. 超时策略** | 30 分钟内若 canonicalizer 无法同时覆盖 serving、neighbor 与 verifier，则停止局部 patch，回到 PLMN 字段模型评估是否需要保留 MNC digit width。 |
| **6. 预警策略** | 若出现第三轮 PLMN width finding，按 receive-review 升级规则停止代码层补洞，补齐 plan/spec 的 PLMN 状态边与 digit-width 不变量。 |
| **7. 用户可见交互修正** | `mnc=0/3` 在 public string surface 显示为 `00/03`；当真实 baseline 也是同一 PLMN 时 Verify 显示“无法区分”，不再误报失败或生效。 |
| **8. 验收** | JVM 测试锁定 getter canonicalizer、verifier `SPOOFED/AMBIGUOUS`、neighbor PLMN 与 physical registry；Python matrix 覆盖 serving `00` 和 neighbor `03`；最后复跑 234+ JVM、Python、Debug/Release/R8/lintVital。 |

## 修复方案与权衡

- 复用 `Snapshot.resolvePlmnString` 作为唯一 PLMN string canonicalizer；不在 getter 再造格式规则。
- verifier 只对 `mcc`/`mnc` 做字段级数值等价，baseline 使用同一规则，因此真实值相同会落
  `AMBIGUOUS`，不会把 inert module 误判为 `SPOOFED`。
- physical-channel 将四个默认重合 getter 收进生产安装路径使用的 registry；JVM census
  锁定 API 门控和方法名。动态矩阵仍负责 callback replacement 与实际值，静态 census
  只补其数学上无法区分的 per-getter 证据。
- 不接受把 Python matrix 改成运行时 regex 读取 Kotlin 源码：现有 pinning test 已把 fixture 与
  canonical writer constant 绑定并在 drift 时红灯；源码树外运行时解析会引入新的部署依赖。

## 验证方式

先记录精确失败测试，再运行 targeted JVM/Python；全部变绿后执行完整质量门禁，最后交回
deepseek-flash 对新 SHA 复审。

## Quality Gate Report

- 原始需求：profile 的透传 / unavailable / 具体值必须在所有 Android public surface 保持
  同一决定；验证不能把与真实 baseline 相同的值当成生效证据。
- Architecture cell：profile persistence → flat config transport → hook snapshot → Android
  public surfaces；Map delta: none。`PhysicalChannelHookRegistry` 是同一 hook cell 的静态
  方法 census，不是新的 store/router/lifecycle owner。
- Red：JVM 编译缺少 PLMN canonical getter 与 physical registry；Python matrix
  `mnc=260` 不满足 leading-zero 场景。第二轮 registry census 在 API 29/31/33 集合上失败。
- Green：`testDebugUnitTest` 237 tests / 0 failures / 0 errors / 0 skipped；Python 24 tests OK。
- 构建：`compileDebugAndroidTestKotlin`、`assembleDebug`、`assembleRelease`（R8）、
  `lintVitalRelease` 全部 `BUILD SUCCESSFUL`。
- 其他：`bash -n scripts/test-hook.sh`、`git diff --check` 退出 0；根目录媒体工件为空。
- `lintDebug`：20 errors / 158 warnings，与 clean remote baseline 同数；changed diff 0 新 error。
- Dogfood：设备写入仍未授权，因此未安装 APK、未改 LSPosed scope、未重启、未运行
  instrumentation。PLMN 与 registry 的纯函数/JVM + deterministic Python 路径已实跑；本报告
  不将静态门禁解释为 runtime feature-complete 证据。
- Fallback 层：仓库无 `check-fallback-layers.mjs`；人工 diff 审计后，PLMN 仅保留必要的
  passthrough fallback，physical 映射使用 registry + switch，无同文件新增三层 fallback。
- P3 disposition：不把 Python matrix 改成运行时解析 Kotlin 源文件。现有 pinning test 每次
  直接读取 `ConfigPrefsSync.SCHEMA_VERSION` 并与 fixture 比较，drift 会红；源码外运行时解析
  会新增部署依赖，可靠性更差。

[砚砚/gpt-5.6-sol🐾]
