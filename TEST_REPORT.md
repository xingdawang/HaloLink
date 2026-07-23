# HaloLink v0.1.5 实机测试报告

测试日期：2026-07-23（Europe/Dublin）

测试设备：HUAWEI Mate 20 Pro / Android 10

测试方式：adb、scrcpy、Bridge 日志、`/health`、Chrome 扩展调试、真实 ChatGPT

## 结论

HaloLink v0.1.5 已完成修复与实机验证。Bridge 最终运行在 `0.0.0.0:8766`，
Camera Activity Notifier 保持运行在 `127.0.0.1:8765`。手机与 Chrome 扩展连接的是
同一个 Bridge 实例；Demo、真实 ChatGPT、HTTP polling 和断线恢复均已通过。

## 根因

原始故障由多个问题叠加造成：

1. `127.0.0.1:8765` 已被 Camera Activity Notifier 使用，而旧版 HaloLink 也可能在
   其他本地地址上复用 8765。手机、Demo 和本机健康检查可能进入不同进程。
2. 旧版发现与健康校验过宽，只检查到可响应的 HTTP 服务，没有严格确认
   `product`、PID 和响应端口。
3. Android 保存的旧地址可能长期复用，发现失败后缺少可靠的清理与全端口重扫。
4. 仅依赖 WebSocket 时，连接短暂失败会使手机停留在旧状态。
5. Chrome 内容脚本的语音检测范围过宽，会把普通页面元素误判为 Listening。
6. 扩展开发态重载会使旧内容脚本上下文失效；必须刷新 ChatGPT 页面以重新注入。
7. Android 的旧 WebSocket `onClosed/onFailure` 回调也会安排重连；多个延迟任务叠加
   后，同一台手机可能暂时建立两条 `/ws/phone` 连接。

## 修改内容

### Bridge

- 默认端口改为 8766，冲突时依次尝试 8767–8775。
- 加入单实例文件锁和明确的 PID/端口提示。
- `/health` 增加 `product`、`version`、`pid`、`projectPath`、`port`、
  `browserClients`、`phoneClients` 和 `lastDelivery`。
- WebSocket hello 帧包含版本、PID 和端口。
- 广播记录 `attempted`、`sent`、`failed`；Demo 对每个状态输出真实手机投递数。
- mDNS 注册与注销移出 asyncio 事件循环阻塞路径。
- 启动日志明确打印项目目录、PID、最终端口、局域网地址、mDNS 和连接数。

### Android

- 版本升级为 0.1.5（versionCode 3）。
- 优先使用 mDNS 广播端口，失败时扫描 8766–8775。
- 严格验证 `/health` 的 HaloLink 产品标识和端口。
- 失败后清理过期 SharedPreferences，再执行完整发现。
- 增加 `HaloLink` TAG 日志：mDNS、扫描、health、Bridge 选择、WebSocket、
  polling、状态应用和重连。
- 保留 `/ws/phone` 实时通道，并每 1.5 秒轮询 `/api/state` 作为备用通道。
- 只有当前 WebSocket 可以安排重连，并合并待执行的重连任务，避免重复连接。

### Chrome 扩展

- 严格验证 HaloLink `/health`，扫描 8766–8775。
- 弹窗显示 Bridge PID、端口、版本、手机连接数和最近状态。
- 收窄 Listening 检测范围，避免普通按钮或 ARIA 元素造成误判。
- 在真实 ChatGPT 当前 DOM 上验证 `stop-button`、消息变化和完成状态。

## 实机测试

### A. 单实例与端口选择

- 第一次启动：Bridge 选择 8766。
- 第二次运行 `run_bridge.command`：报告已有实例的 PID/端口并退出。
- 进程检查：仅一个 HaloLink Bridge。
- `/health.projectPath`：与当前 HaloLink checkout 一致。

结果：通过。

### B. 与 Camera Activity Notifier 共存

- Camera Activity Notifier：`127.0.0.1:8765`，`/health` 仍返回正常。
- HaloLink：`0.0.0.0:8766`。
- HaloLink 启停和断线恢复期间均未停止、卸载或修改 Camera Activity Notifier。

结果：通过。

### C. 手机连接、WebSocket 与 polling

全新启动 Android App 后，日志记录：

```text
mDNS service resolved ... advertisedPort=8766
health verified ... port=8766 version=0.1.5
selected bridge ... port=8766
WebSocket opened path=/ws/phone
HTTP polling success ... port=8766
state received and applied ... source=websocket
state received and applied ... source=http-poll
```

`/health` 实测：

```json
{
  "product": "HaloLink",
  "version": "0.1.5",
  "port": 8766,
  "browserClients": 1,
  "phoneClients": 1
}
```

结果：WebSocket 与 HTTP polling 均连接到同一个 Bridge，`phoneClients: 1`。通过。

### D. Demo 状态顺序与投递数

使用 `demo_states.command` 并通过 scrcpy 观察 Mate 20 Pro，圆环按顺序显示：

| 协议状态 | 手机显示 | 投递结果 |
| --- | --- | --- |
| READY | Ready | 1/1 |
| THINKING | Thinking... | 1/1 |
| WORKING | Working... | 1/1 |
| STREAMING | Responding... | 1/1 |
| COMPLETED | Completed! | 1/1 |
| ERROR | Error | 1/1 |

每个状态均报告：

```text
Phone delivery: 1/1 sent, 0 failed
```

结果：通过。

### E. 真实 ChatGPT

在已登录的真实 ChatGPT 页面发送测试消息，并在 Chrome 开发态扩展、Bridge health 和
Android `HaloLink` 日志中交叉验证。

手机实际收到：

```text
THINKING  source=websocket
STREAMING source=websocket
COMPLETED source=websocket
```

同时 polling 也持续读取并应用相同状态。最终 `/health`：

```json
{
  "state": {"state": "COMPLETED", "source": "browser"},
  "phoneClients": 1,
  "lastDelivery": {"attempted": 1, "sent": 1, "failed": 0}
}
```

scrcpy/adb 实机画面最终显示绿色圆环、勾号和 `Completed!`。未再出现错误的
Listening 状态。

结果：通过。

### F. Bridge 断线恢复

只停止 HaloLink LaunchAgent 后：

- 手机显示 Searching / Looking for your Mac。
- Camera Activity Notifier 的 8765 listener 保持存在。

重新启动同一 HaloLink LaunchAgent 后，无需清除应用数据或重装：

- mDNS/扫描重新找到 8766。
- `/health` 验证通过。
- `/ws/phone` 自动重连。
- polling 自动恢复。
- 手机回到 Ready / Connected。
- 连续再次重启后 `phoneClients` 稳定为 1，没有重复 WebSocket。

结果：通过。

## 构建与代码检查

- `python3 -m py_compile mac-bridge/bridge.py mac-bridge/demo_states.py`：通过。
- `node --check`（background/content/popup）：通过。
- shell 脚本语法检查：通过。
- GitHub Actions run
  [30053417261](https://github.com/xingdawang/HaloLink/actions/runs/30053417261)：通过。
- 构建产物：`apk/HaloLink-v0.1.5-debug.apk`
- SHA-256：
  `6a5a432c1a8e59765baf6a41f4d6f811c529b366af8a3e208d0cd38849b378bb`

## 最终状态

- HaloLink Bridge：单实例，端口 8766。
- Camera Activity Notifier：仍运行在 8765。
- Chrome 扩展：已连接同一 Bridge。
- Mate 20 Pro：`phoneClients: 1`，WebSocket 与 polling 均正常。
- Demo：全部状态 1/1。
- 真实 ChatGPT：Thinking、Responding、Completed 已实机到达。
- 断线恢复：无需重装或清数据，自动恢复。
