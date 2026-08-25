---
feature_ids: [F-18]
related_features: [F-13]
topics: [signing, debug-keystore, ci, install, false-green, device]
doc_kind: bug-report
created: 2026-08-25
status: fixed-on-branch (fix/f18-signer-identity)
---

# F-18: debug keystore 发散 → install -r 静默失败 + 版本号认包假绿

## Bug 诊断胶囊

| 栏位 | 内容 |
|---|---|
| **1. 现象** | 同日两线独立实测：`adb install -r` 报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（设备证书 `53fd8e58…` ≠ 构建证书 `7a598cbe…`），且失败后 versionCode=8 / versionName=3.0.0 纹丝不动——按版本号认包的调用方把"装失败"读成"已是新包"，在旧包上跑出假绿（C5 run#1 `runF-PASS` 即假绿现场，后被 §11 自审掀出）。 |
| **2. 证据** | ① 封存包 `c5-evidence/f18-signer-divergence/device-bench-unknown-signer.apk`（sha256 `8bc53fb1…`，cert `53fd8e58…`）② CI run `#32763136108`（commit `d3008ea`，2026-08-24 21:33 EEST）的 `qianwangyou-evidence` artifact 与封存包**逐字节一致**（sha/size 全同）③ 同一 workflow 连续 7 个 run 证书全不同（`5f7b44cb/53fd8e58/6fef77c0/9137a0ec/51140f4c/9042f26d/634b6df3`）= runner 每次随机生成 debug.keystore ④ 8月24日 03:03 设备 bench 仍为 `7a598cbe`（`c5-evidence/installed-*.apk`，cmp 验证），53fd8e58 注入窗口锁定 8月24 17:59 → 25 12:02 ⑤ 本机 `~/.android/debug.keystore` 自 2026-07-16 23:57 未变（cert=7a598cbe），排除本机 keystore 重生成 |
| **3. 根因** | 三层叠加：(a) **签名是环境态不是仓库态**——两 app 的签名依赖机器本地/runner 本地 keystore，CI runner 每次 roll 随机 key；(b) **外来签名产物被直装设备**——`d3008ea`（F-11 probe lifecycle 迭代期）的 CI artifact 被下载后 `adb install` 上 ZY22JHW9M4（adb 装的，installerPackageName=null），此后一切本仓库构建都无法 `-r` 覆盖；(c) **失败不可见 + 认包靠版本号**——`test-hook.sh` 把 install stdout `>/dev/null`；`mock_provider_acceptance.sh:368` 裸 install 无输出/退出码检查、失败后继续跑；而 CI 构建与本地构建 versionCode/versionName 完全相同，版本号判据对"哪个字节在设备上"零分辨力。 |
| **4. 诊断策略** | 封存字节先行（APK 原始字节 + 全链证书指纹），再按"证书出生地"分类排除：本机 keystore 时间线（mtime/创建日期 7-16，未重生成）→ 多 checkout 产物指纹扫描（本地产物全 7a598cbe；唯 `fakexxx-apk-1b3451e/` 归档产物为随机证书 `b4a5966d` → CI 指向）→ 窗口内 CI run 逐个下载 artifact 指纹比对 → 字节级命中 run `#32763136108`。 |
| **5. 超时策略** | 若窗口内 run 的 artifact 已过期无法比对，退路为机制证明：连续 run 证书随机 + 窗口内无其他外来源 + `fakexxx-apk-1b3451e` 归档先例。本次未用退路，直接字节命中。 |
| **6. 预警策略** | 版本号相同 ≠ 同一个包；`adb install` 输出必须全量可见；CI debug artifact 天然不可 `-r` 覆盖本地装过的同包名 app（除非签名入库）。任何"装完检查 versionCode"的判据都是把 (c) 类缺陷固化。 |
| **7. 用户可见交互修正** | 无产品 UI 变化；开发/验收工作流变化：install 走 `install_apk_verified.sh`（失败全文 + 实装 SHA-256 比对），构建签名固定为入库 keystore（7a598cbe，与设备现装兼容），CI 加 signer gate。 |
| **8. 验收** | ① 本分支两 app `assembleDebug` 产物 cert 均 `7a598cbe`（guard 通过，跨 worktree 构建亦然）② guard 对封存外来包报 `SIGNER MISMATCH` rc=2（真包负例）③ `selftest-install-apk-verified.sh` 10/10（含 INSTALL_FAILED 全文 surfaced、Success-but-stale 拒绝）④ `selftest-debug-signer.sh` 7/7 ⑤ CI 两 lane 各挂 `check-debug-signer.sh` gate。 |

## 时间线（全部来自封存字节与只读查询）

| 时间 (EEST) | 事件 | 证据 |
|---|---|---|
| 07-16 23:57 | 本机 `~/.android/debug.keystore` 生成（cert 7a598cbe），此后未变 | keytool 创建日期 = 文件 mtime |
| 08-13 19:59 | 设备 bench 此前一直装的是 7a598cbe 系 | `RESTORE-SNAPSHOT.txt` lastUpdate + 08-24 03:03 拉取包 cmp |
| 08-24 03:03–04 | C5 precheck：设备 bench = fresh = 7a598cbe | `c5-evidence/installed-*.apk` 指纹 + MANIFEST |
| 08-24 21:33 | CI run `#32763136108`（`d3008ea`）产出 cert=53fd8e58 的 debug APK 并上传 artifact | artifact 下载指纹比对（本次） |
| 08-24 晚–08-25 午 | 该 artifact 被下载并 `adb install` 上设备（注入点，装者身份属并行 session，归调度线问询） | 设备 dumpsys `installerPackageName=null`；窗口排除 |
| 08-25 12:02 | F-10 证据已拉到该包并标注 `foreign-cert-53fd8e58` | `f10-legacy-db/device-bench-8bc53fb1-foreign-cert-53fd8e58.apk` |
| 08-25 15:45–47 | C5 run#1（chainF）：install -r 失败被吞 → 旧包跑完全链 → `runF-PASS` **假绿**；15:46 封存设备包 | `RUN1-SECTION11-AUDIT.txt` + `runF-PASS/` |
| 08-25 15:53:51 | 设备 uninstall + fresh install（7a598cbe 干净态交还） | dumpsys firstInstallTime=lastUpdateTime（只读） |
| 08-25 15:54–59 | C5 run#2 真跑通过（实装 SHA = 构建 SHA MATCH） | `docs/acceptance/g1-smoke-2026-08-25-ZY22-run2.md` |

## 修复清单（fix/f18-signer-identity）

| 层 | 文件 | 内容 |
|---|---|---|
| 签名=仓库态 | `apps/qianwangyou/keystores/bench.keystore`、`apps/cellrebel-auto/keystores/bench.keystore` | 入库 keystore（= 设备现信任的 7a598cbe 字节，install -r 连续性保持；两 app 各持一份拷贝，保持 app 独立性，字节相同是有意为之——见 build 文件注释） |
| | `apps/qianwangyou/app/build.gradle` | signingConfigs `release`→`bench`（storeFile 指向入库 keystore）；debug buildType 显式挂 `signingConfigs.bench` |
| | `apps/cellrebel-auto/app/build.gradle.kts` | 新增 signingConfigs.bench；debug buildType 挂之（release 维持 unsigned 不变） |
| 防回归 gate | `scripts/check-debug-signer.sh` + `scripts/selftest-debug-signer.sh` | keystore 证书 vs APK signer 证书比对；CI 两 lane 各加 step；负例已用封存外来包实测 |
| install 不可静默 | `scripts/install_apk_verified.sh` + `scripts/selftest-install-apk-verified.sh` | install 退出码 + `Success` 双查、失败全文（含 INSTALL_FAILED_* 原因）回显；`pm path` 单 base.apk 断言；设备实装 SHA-256 vs 本地产物 SHA-256 字节比对 |
| 调用点 | `apps/qianwangyou/scripts/test-hook.sh` | 不再 `>/dev/null`；失败回显全文 + UPDATE_INCOMPATIBLE 提示（原有装前 SHA 幂等逻辑保留） |
| | `apps/qianwangyou/scripts/mock_provider_acceptance.sh` | 裸 install 换成 verified helper（set -e 下失败即中止，不再在旧包上继续跑） |

## 闭环判据对照

1. **同 repo 任意猫/任意 worktree 构建签名一致，可互相 install -r** — 入库 keystore + 两 app 实构产物 cert=7a598cbe（本 worktree 与主 checkout 双点验证）；CI 构建同源同 key（guard 挂 gate）。注：与设备旧包的互相覆盖验证未做——**设备禁触**（边界），现状设备已是 7a598cbe 干净态，本修复保证其连续性。
2. **install 失败不可静默** — helper 双查 + 全文回显；selftest c2/c3/c4/c5 覆盖四类失败形态；两调用点接线。
3. **认包不依赖 versionCode/versionName** — helper 以实装 base.apk 字节 SHA-256 为唯一身份判据（F-13 runbook 侧判据的代码化）；test-hook 原有 SHA 幂等逻辑保留。

## 未覆盖 / 待调度线

- 8月24 晚–25 午间**执行下载安装的具体 session**未指认（并行 session 无共享 history；窗口+字节证据已闭合到 CI artifact 级）。问询归调度线。
- 设备侧操作（含任何与 53fd8e58 残留相关的处理）一律未经调度线不碰；本分支全程只读设备查询（dumpsys / pm path 类）。
- 不 merge、不 undraft、不 close #7、不放行 G1（边界遵守）。
