---
feature_ids: [F001]
topics: [android, location, mock-provider, lifecycle, acceptance]
doc_kind: bug-report
created: 2026-08-03
---

# Mock Provider 在进程退出后残留，旧验收产生假绿

## 现象

Lab 服务与进程已经停止、mock-location app-op 已恢复给参考 App，但 `dumpsys location` 仍显示：

- `gps provider [mock]`
- owner 为 `name.caiyao.fakegps.mockprovider`
- last location 仍是纽约 `40.7128,-74.0060`

## 期望

用户执行 Stop 或切回 Hook 后，系统 `gps` provider 恢复为 Android `GnssService`，不再带 `[mock]`，last mock location 不再被继续提供。

## 最小复现

1. 将 Lab 设为开发者选项中的模拟位置 App，并 Start。
2. 确认 `dumpsys location` 的 gps provider 为 `[mock]`。
3. 在没有完成显式 Stop 的窗口中 force-stop/杀死 Lab。
4. 恢复参考 App 的 mock-location app-op。
5. Lab PID 已消失，但 `gps provider [mock]` 仍存在。

对照：重新把 Lab 设为 mock app，打开 Lab 并显式点击 Stop 后，`gps provider` 立即恢复 `identity=1000/android[GnssService]`。这证明 cleanup primitive 有效，问题不在 `removeTestProvider` 实现本身。

## 根因

Android test provider 是 system_server 持有的系统状态，不随创建它的 App 进程自动销毁。`Service.onDestroy()` 在 force-stop/SIGKILL 路径不可靠，因此把 cleanup 仅放在 `onDestroy()` 无法覆盖进程死亡。

同时，旧 `mock_provider_acceptance.sh` 的 restore 只检查：

- UI 中找到并点击了 Stop 文本；
- Lab PID 消失；
- 参考 App 获得 mock-location app-op。

这些都是代理信号，没有读取 `gps` provider 的实际身份，所以把残留 provider 判为通过。

## 修复契约

1. 主 App 显式 Stop 无条件调用 `removeTestProvider`，不依赖内存 state。
2. 在首次 system provider 变更前持久化 cleanup marker；主 App 以 Hook 启动且 marker 尚未清除时执行 recovery stop，普通 Hook 启动不碰 provider。
3. System Mock 成功运行后才提交该模式；失败则回滚为 Hook 并 cleanup。
4. 验收以 `dumpsys location` 的 provider identity / `[mock]` 为真相，PID 与 app-op 仅为辅助。

## 当前设备恢复证据

复现结束后已显式 Stop，并恢复 `com.hopefactory2021.fakegpslocation` 的 mock-location app-op。设备 `ZY22JHW9M4` 当前 `gps provider` 为 `identity=1000/android[GnssService]`、`last location=null`，无 mock provider 残留。
