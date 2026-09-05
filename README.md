---
feature_ids: [F-13, F-18]
topics: [android, location, release]
doc_kind: readme
created: 2026-09-05
---

# fakexxx

fakexxx 是一套用于受控位置模拟与自动化的双 App Android 工具：

| App | 包名 | 最低版本 | 职责 |
| --- | --- | --- | --- |
| 千万游 | `name.caiyao.fakegps` | Android 7.0 / API 24 | 管理并应用位置档案 |
| CellRebel Auto | `com.example.cellrebelauto` | Android 8.0 / API 26 | 选择档案并请求 Provider 侧执行 |

两只 App 通过版本化 Binder 契约通信。安装时先装千万游，再装 Auto，并在千万游设置页完成配对。

## 两种运行模式

- **System Mock**：Android 系统的 Mock Location 能力本身不要求 root 或
  LSPosed；需要在开发者选项中把千万游选为模拟位置信息应用。但当前 Release
  构建仍依赖跨进程配置发布通道；未经目标真机验证，不承诺“无框架环境必然可用”。
- **Hook**：要求设备已 root，安装 Xposed/LSPosed，启用千万游模块，并为目标
  App 配好作用域。

Binder 配对只证明两只已安装 App 可以通信，不证明 APK 的发布者或文件身份。
必须用下述校验安装流程确认手机上实际安装的字节。

## 安装候选版本

1. 备份千万游中的位置档案，并记录当前数据库 schema/version。
2. 构建两只 Release APK，记录 SHA-256。
3. 先校验安装千万游，再校验安装 Auto。
4. 首次启动后检查数据库健康、档案完整性和预期运行模式。
5. 在千万游设置页配对 Auto，并完成真机验收矩阵。

```sh
./scripts/install_apk_verified.sh -s DEVICE_SERIAL -p name.caiyao.fakegps -- PATH_TO_QWY_APK
./scripts/install_apk_verified.sh -s DEVICE_SERIAL -p com.example.cellrebelauto -- PATH_TO_AUTO_APK
```

脚本会执行 `adb install -r`，再比对手机内 base APK 与本地文件的 SHA-256。
`-r` 只保留数据目录，不保证 App 内数据库迁移无损；不要为绕过签名冲突而卸载，
除非已经明确接受数据丢失。

## 构建与校验

```sh
./scripts/verify-a-plus.sh --stage full
./apps/qianwangyou/gradlew -p apps/qianwangyou assembleRelease
./apps/cellrebel-auto/gradlew -p apps/cellrebel-auto assembleRelease
```

发布前对最终 APK 运行 Release 纯度检查：

```sh
./scripts/check-debug-only-collector.sh apps/qianwangyou/app --apk PATH_TO_QWY_APK
./scripts/check-debug-only-collector.sh apps/cellrebel-auto/app --apk PATH_TO_AUTO_APK
```

## 文档入口

- [版本与发布操作规范](docs/releasing.md)
- [版本变更记录](CHANGELOG.md)
- [v0.1.0 版本说明](docs/releases/v0.1.0.md)
- [真机验收矩阵](docs/acceptance/a-plus-device-matrix.md)

## 签名与分发边界

当前 v0.1.x Release 使用仓库内公开的 Android debug/bench 凭据，只允许受控、
非商店、私下安装，以维持跨构建机覆盖安装的身份一致性。该私钥事实上是公开的，
不能证明发布者身份。仓库本身是公开的，因此把 APK 附加到 GitHub Release 就属于
公开分发，在现有签名策略下禁止。公开发布前必须迁移到受控 Release key，并制定
兼容现有安装与数据的升级方案；任何 keystore、私钥或密码都不得作为 Release 资产。
