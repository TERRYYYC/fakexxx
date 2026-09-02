---
feature_ids: [G2-66]
topics: [codex-bench, android, device, install, isolated-validation]
doc_kind: evidence
created: 2026-09-02
device_serial4: ZY22
exact_code_head: 51e330e11846eab405a66b0f88203e0d8ad70dc9
status: installed-only
---

# codex-bench 独立安装记录（Moto ZY22）

## 结论与授权边界

两只独立名称、独立 application ID 的 debug APK 已安装到 operator 指定的唯一 Moto，
安装后的 `base.apk` SHA-256 与本机构建产物逐一相等。旧三个 APK 字节未覆盖；
安装后快照仍看到旧 QWY / Auto 进程及原服务绑定。

**这只证明安装共存，不证明新应用运行可用、旧业务完全无干扰、配对成功、#66 AC7 或 FULL。**
应用名称来自编译后 APK 检查，尚未做手机桌面/UI 的目视验收。

- operator 已授权在指定 Moto 安装两只 debug APK，以及后续 mock location / LSPosed 验证；
  同时要求不能与正在运行的 app 重名。本轮以 `codex-bench` 作为已确认的隔离名称。
- `codex-bench` 的命名回复不被解释为暂停旧自动化的同意。旧 Auto 当时仍在运行并绑定旧 QWY。
  已询问能否临时暂停并恢复现有自动化；实际验证等待此运行窗口确认。
- 所有设备命令均指定原授权的完整 serial；未操作其他设备。
- 本轮仅安装新包及只读核验：没有启动新应用、停止/清理旧应用、迁移配对数据、
  修改 mock location 授权、变更 LSPosed 作用域或重启手机。
- 两只新包保留安装。尚未建立 mock / hook / 运行测试状态，故本轮没有此类状态需要回滚。

## 冻结代码与 APK 身份

基线为 PR #69 的 `bc62767c626eb247b64b17d97ff82c807262c5b8`，
代码 HEAD 为 `51e330e11846eab405a66b0f88203e0d8ad70dc9`，分支 `codex/codex-bench`。
本证据文档是代码审查与安装后补记；并非宣称文档提交重新构建了 APK。

| 应用 | 编译后名称 | application ID | 版本 |
| --- | --- | --- | --- |
| QWY | 千网游 · codex-bench | `name.caiyao.fakegps.codexbench` | 8 / 3.0.0 |
| Auto | CellRebel Auto · codex-bench | `com.example.cellrebelauto.codexbench` | 1 / 1.0 |

```text
QWY artifact SHA-256 = installed base.apk SHA-256
4b6027d2a8339accc8ad90a74a96d239e314e92e1c2423eb5b9777926aea108a

Auto artifact SHA-256 = installed base.apk SHA-256
304efe65e27f246c55cd2d36e5a5ce148acbe0484d7dec2732de65980cd1e9f1

Both artifact signer certificate SHA-256
7a598cbe6fb816ba74f01b58e3f43b8ff0f463989157e590ebd86c89b53f7e41
```

产物路径分别为两个 app 的 `app/build/outputs/apk/codexBench/app-codexBench.apk`。
`scripts/check-codex-bench-apks.sh` 对编译后的包名、各语言 label、launcher、
debuggable、authority/permission、debug probes 和 signer 检查通过。
安装 helper 对两个包均输出 `installed+verified ... MATCH`，退出码为 0。

## 安装后快照与限制

只读安装后快照时间：`2026-09-02T12:45:25Z`。旧 APK 的前置基线与后置 SHA 相等：

```text
name.caiyao.fakegps
63275aea12fda9612058d83109eca2093599fc3f779748ea6cf176614e096c0e

name.caiyao.fakegps.bench
d950131a15745d0a20ea2810a3cf3c4f7d4251ed1a5d3bad08598edaa54142ae

com.example.cellrebelauto
c10c2216fa88defc60ec8dc37bbbc9342e6ff03e405b259fc569eba97a76cae7
```

后置进程：旧 QWY bench PID `694`、旧 Auto PID `32722`，与安装前一致。
后置服务记录仍是旧 Auto → 旧 QWY bench `EnvironmentControlService`。
这不等价于持续无中断监测，也不证明旧数据或业务结果完全未变化。

执行者没有发出启动新包的命令。原始后置日志末尾两个 `stopped=true notLaunched=true`
过滤片段没有包名标题，不能脱离命令记录独立确定归属；独立记录者不将这两个片段
作为新应用运行状态的完整证据。无需为补记证据再次触碰设备。

## 本机构建验证与独立审查

| 验证 | 结果 | 证明范围 |
| --- | --- | --- |
| `verify-a-plus.sh` | 12 / 12 gates PASS | 仓库 host gates，非设备验收 |
| codexBench Auto 定向测试 | 24 / 24 PASS | 实际新 variant 的选择、隔离、绑定与组合 |
| 标准 debug Auto 定向测试 | 20 / 20 PASS | 原默认选择保留 |
| QWY debug tests | 1,030 tests PASS | 原 debug host 回归 |
| principal-routing selftest | 26 / 26 PASS | 结构门禁的正负与 mutation cases |
| release APK 检查 | 两 app PASS | debug-only purity；Auto principal routing |
| codexBench APK 检查 | 两 app PASS | 独立身份、label 与 pinned signer |
| `git diff --check` | PASS | 差异格式 |

以上为本地结果，不代表远端 CI 已通过。标准 debug/release 身份保持不变；
冻结 ContractV1 与生产 fingerprint allowlist 未修改。

非作者 `/root/codex_bench_exact_review` 对 exact code HEAD 给出 **APPROVE**，
无 P0/P1/P2 或新增 P3；包括独立执行产物/结构门禁和读取测试结果。
范围仅 packaging/routing，不包括真机 UI、oracle 或 AC7。
独立记录者 `/root/moto_codex_install_witness` 未向设备发命令，读取安装与后置日志后
确认新包字节、旧三包 SHA、旧 PID 与绑定这几个安装共存事实，并指出上述状态片段限制。

## 原始证据定位

原始日志保留在执行机 `/tmp/fakexxx-codex-bench-evidence.Bmj3Hq/`。
此为本机临时目录，不是可从 GitHub 下载的归档，也不保证长期保留；下列摘要便于核对，
不能代替缺失的完整原始材料。本文已保留足以理解安装结论的限定事实，未上传手机原始路径。

```text
3191cb43cedce063e5f4eb66ba5d9b914be423da419bcb9da4a03b98e0e024f2  moto-install.log
0e1f19b5e542cfaf5a4d9ecf3fb8dbcfab5482c73b77c75ff4f4ece6d85e2361  moto-post-install.log
ab77be803bd12d830d26a0c792acfbfaed0741a8225fee55a00d64e698f499de  apk-isolation-green.log
16d9f46655c01dbfc6de2b64457e279f3dd7bacda1c6f1577588400f1b5ba815  full-host-gate.log
```

## 交接五件套

- **What:** 新 `codexBench` build type、固定身份路由、回归门禁、两只实装 APK。
- **Why:** 在不替换旧应用的前提下，为 #66 提供可区分的受控验证入口。
- **Tradeoff:** 应用 sandbox 隔离不等于系统 mock location 或 LSPosed system-server hook 隔离。
- **Open Questions:** 现有自动化何时可以暂停并恢复；Moto 的 production oracle attestation 尚未建立。
- **Next Action:** 确认运行窗口后，以本轮 exact APK 字节重新规划并执行获授权的真机测试和恢复核验。
  当前 `ATTESTED_FINGERPRINTS` 仍为空，installer 在装 hooks 前返回；
  #66 AC7 **NOT_PASSED**、FULL **BLOCKED**，不得以安装成功或旧 emulator 证据关闭问题。
