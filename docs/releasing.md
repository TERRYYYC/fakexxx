---
feature_ids: [F-13, F-18]
topics: [release, versioning, signing, operations]
doc_kind: runbook
created: 2026-09-05
---

# 版本与发布操作规范

本文是 fakexxx 构建、验收、安装和发布的操作真相源。构建成功不等于已经发布。

## 版本规则

- 使用 Semantic Versioning，tag 格式为 `vMAJOR.MINOR.PATCH`。
- 两只 App 的 `versionName` 必须与去掉 `v` 的 tag 一致。
- 两个包各自维护独立 `versionCode`，且必须高于该包所有已分发版本。
- Binder contract v1、spec v1.x 是协议或文档版本，不是 App 版本。
- 每次发版同时更新 `CHANGELOG.md` 和
  `docs/releases/vMAJOR.MINOR.PATCH.md`。

## 产物与签名规则

APK 名称必须包含产品、版本名和 versionCode，例如：

- `fakexxx-qianwangyou-0.1.0-vc8.apk`
- `fakexxx-cellrebel-auto-0.1.0-vc1.apk`

每个产物记录：文件名、包名、versionName、versionCode、APK SHA-256、签名证书
SHA-256、源码 commit、构建 workflow/run。

v0.1.x 两只 Release 使用 `bench.keystore`，只允许受控私下非商店安装。它是公开的
标准 Android debug 凭据，不能认证发布者。本仓库公开，GitHub Release 资产也是公开
分发，所以不得上传由该 key 签名的 APK，更不得上传 keystore、私钥、密码或签名配置。

公开分发前必须制定并批准受控 Release key 迁移：覆盖两个包的升级兼容性、现有数据、
密钥托管和备份、访问控制、轮换和恢复。禁止静默切换签名身份。

## 发布流程

### 1. 冻结版本

1. 从干净工作区和指定 main commit 开始。
2. 设置两只 App 的 `versionName`，并分别递增所需的 `versionCode`。
3. 把用户可见变更从 Unreleased 移到本版 changelog。
4. 新建状态为 `release-candidate` 的版本说明。

### 2. 校验源码

```sh
./scripts/verify-a-plus.sh --stage full
```

记录精确 commit 和结果；最终发布 diff 必须经过独立审阅，受保护 CI 必须在被审阅
head 上通过。不要把候选树的本地结果表述成 main 上重新运行的结果。

### 3. 构建并识别产物

```sh
./apps/qianwangyou/gradlew -p apps/qianwangyou clean assembleRelease
./apps/cellrebel-auto/gradlew -p apps/cellrebel-auto clean assembleRelease
```

从 APK 本身核对包名、版本名和 versionCode，记录 APK 与签名证书 SHA-256，然后运行：

```sh
./scripts/check-debug-only-collector.sh apps/qianwangyou/app --apk PATH_TO_QWY_APK
./scripts/check-debug-only-collector.sh apps/cellrebel-auto/app --apk PATH_TO_AUTO_APK
```

只要重建过 APK，旧 hash 证据立即失效，必须重新检查和验收。

### 4. 在目标手机校验安装

先记录手机序列号、型号和 Android 版本。导出位置档案，并记录数据库 schema/version。
然后按 Provider 在先的顺序执行：

```sh
./scripts/install_apk_verified.sh -s DEVICE_SERIAL -p name.caiyao.fakegps -- PATH_TO_QWY_APK
./scripts/install_apk_verified.sh -s DEVICE_SERIAL -p com.example.cellrebelauto -- PATH_TO_AUTO_APK
```

脚本要求 `adb install -r` 成功，并验证手机内 base APK hash 与本地文件一致。版本号不是
文件身份证明。签名不兼容时停止；未经明确授权，不得用卸载清数据来绕过。

`adb install -r` 只保留数据目录，不证明 App 的数据库迁移无损。首次启动后先检查
schema 健康和档案内容，再继续功能验收。任何 fallback destructive migration 都是
数据丢失风险，不是兼容性证据。

### 5. 真机验收

按 `docs/acceptance/a-plus-device-matrix.md` 留存至少以下证据：

- 首次启动、schema 健康和必要档案完整；
- System Mock 的系统选择、跨进程配置发布和真实档案应用；
- Auto 的发现、配对、能力协商、执行、耗尽停止与终态反馈；
- Hook 仅在已 root 且正确配置模块与作用域的 Xposed/LSPosed 设备验收；
- Provider 不可用、能力不兼容、进程或设备重启后的恢复路径。

debug/bench、模拟器或只验证 Binder 的证据不能替代“最终 Release APK + 目标真机”。

### 6. 发布

仅在前五步全部通过且 APK 已迁移到获批受控 Release key 后：

1. 把版本说明状态改为 `released`，补齐最终产物和真机证据。
2. 在被审阅的精确 commit 创建 annotated tag；tag 不得移动或复用。
3. 发布两只 APK、checksums 和版本说明，不发布任何私钥材料。
4. 回下载一次公开资产并重新比对 hash。
5. 在 changelog 补发布日期和 Release 链接。

## 失败与回滚

发布前失败时保留 `release-candidate`，不打 tag；修复后重跑受影响的全部门禁。已发布
版本若有缺陷，明确标记受影响 hash 与影响范围，从推荐路径撤下，并用更高 versionCode
发布修复；不得移动旧 tag。

## “已发布”的定义

精确被审阅源码产生了记录中的 APK、自动门禁与独立审阅通过、两只精确 APK 在目标
真机通过验收、签名满足分发范围，且不可变 tag 与 Release 记录已发布，才可称为已发布。
此前统一称 Release Candidate。
