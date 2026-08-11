---
feature_ids: [F001]
topics: [android, unit-test, content-provider, authority, rebase]
doc_kind: bug-report
created: 2026-08-04
---

# AppInfoProvider authority local test 触发 Android stub 初始化

## 1. 报告人

砚砚在把 PR #10 rebase 到最新 master `6fe6915931408dff6e795c5a433c4538a21a118d` 后运行 selected-profile / provider-authority 交叉契约时发现。

## 2. 复现步骤

运行：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'name.caiyao.fakegps.data.AppInfoProviderAuthorityTest' \
  --rerun-tasks
```

期望：local JVM 测试验证当前 variant 的 application id 被用于 provider authority。

实际：测试读取 `AppInfoProvider.AUTHORITY` 时触发整个 class 的静态初始化；`APP_CONTENT_URI` 随即调用 Android stub 的 `Uri.parse`，抛出 `ExceptionInInitializerError` / `Method parse in android.net.Uri not mocked`。

## 3. 根因分析

产品实现的 authority 是纯字符串，但测试把契约挂在 Android `ContentProvider` 类的静态字段上。Java 第一次读取该字段会初始化类内所有静态字段，因此测试实际依赖了 Android runtime，而不是只检查 authority。仓内已有 `MainHookRefreshContractTest` 用 class bytes 检查生产 wiring，证明 local JVM 可以在不初始化 Android 类的前提下验证接线。

第一次修复只保留 bytecode 符号接线断言，丢掉了 master 原有的“组合后 authority 值必须精确等于 manifest 模板”契约。Fable5 构造 `helper(...) + ".x"` 变异后 418 tests 全绿，证明符号存在不等于最终值正确；同时 `ConfigPrefsSync` 还独立拼接了同一 authority，存在 provider 与 publisher 漂移的同类风险。

## 4. 修复方案

把当前 variant 的精确值 `ProviderAuthority.AUTHORITY` 放进不依赖 Android runtime 的纯 JVM 持有者；`AppInfoProvider` 的 URI/`UriMatcher` 与 `ConfigPrefsSync` 的 publisher URI 全部只引用这个值。测试恢复普通 `assertEquals(BuildConfig.APPLICATION_ID + ".data.AppInfoProvider", ProviderAuthority.AUTHORITY)`，并分别用 class bytes 守住 provider 与 publisher 的 production wiring。

放弃给 Gradle 开启全局 Android stub 默认值：那会把更多误用 Android runtime 的 local tests 静默变成假绿。也不引入 Robolectric，因为本契约只需验证纯 helper 与生产 wiring，不值得增加测试 runtime。

## 5. 验证方式

- RED：原测试稳定以 `AppInfoProvider.<clinit>` → `Uri.parse` 失败，32 tests 中 1 failed。
- RED：精确值暂时附加 `.x` 时，定向集合编译成功并以 `ComparisonFailure` 变红。
- GREEN：移除 `.x` 后定向集合通过；最终形态重放同一 MA3 仍以精确值断言失败，证明原 418/418 存活变异已被击杀。
- 全量：remediation implementation `8c92f65727568f71f02862b5ab52849c3932c536` 为 420 tests，0 failure/error/skipped；Debug/Release/`lintVitalRelease` 成功。
- 真机：使用同一生产 APK 运行完整 picker → selected Kyiv profile → System Mock → Maps → recovery → GNSS restore 链，exit 0。

[砚砚/gpt-5.6-sol🐾]
