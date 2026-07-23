# HaloLink v0.1.3

把一台 Android 手机变成 ChatGPT 网页的实时状态指示器。

## 组成

- `chrome-extension/`：读取 ChatGPT 网页状态。
- `mac-bridge/`：在 Mac 上接收浏览器状态，通过局域网广播，并用 mDNS 让手机自动发现。
- `android-app/`：Mate 20 Pro 应用，打开后自动发现 Mac，显示中央 Halo 圆环。
- `apk/`：若构建成功，这里包含可直接安装的 APK。

## 支持的状态

- `Searching`：灰白旋转圆环
- `Ready`：暗绿色静态圆环
- `Thinking`：红色呼吸圆环
- `Working`：橙色旋转圆环
- `Streaming`：红色旋转圆环
- `Listening`：蓝色脉冲圆环
- `Completed!`：绿色圆环与勾号
- `Error`：紫色闪烁圆环

## 第一次实机测试

### 1. 在 Mac 启动 Bridge

打开 `mac-bridge` 文件夹，双击：

```text
run_bridge.command
```

第一次会创建 Python 虚拟环境并安装两个依赖。macOS 如果询问是否允许接收入站连接，请选择 **Allow**。

终端出现以下内容即成功：

```text
HaloLink Bridge listening on 0.0.0.0:8765
mDNS service: _halolink._tcp.local.
```

### 2. 安装 Chrome 扩展

1. 在 Chrome 地址栏打开 `chrome://extensions/`
2. 开启右上角 **Developer mode**
3. 点击 **Load unpacked**
4. 选择本项目中的 `chrome-extension` 文件夹
5. 打开或刷新 `https://chatgpt.com/`

扩展图标弹窗会显示 Bridge 是否连接，以及最近一次识别到的状态。

### 3. 安装 Android App

如果 `apk/HaloLink-v0.1-debug.apk` 已存在，直接传到 Mate 20 Pro 并安装。

如果没有 APK：

1. 用 Android Studio 打开 `android-app`
2. 等待 Gradle Sync 完成
3. 连接手机并点击 Run，或执行 `Build > Build APK(s)`

首次打开 HaloLink 后，它会在同一 Wi-Fi 中搜索 `_halolink._tcp` 服务并自动连接。无需蓝牙。

### 4. 先测试圆环，不依赖 ChatGPT

Bridge 运行时，打开另一个终端：

```bash
cd mac-bridge
./demo_states.command
```

手机会按顺序显示 Thinking、Working、Streaming、Completed 和 Error。
终端会同时显示每一个状态已成功写入多少个当前手机 WebSocket（例如
`Phone delivery: 1/1 sent`）。`0/0` 表示 Demo 本身正常，但没有手机连接到
这个 Bridge 实例。

## 连接诊断与备用更新

- `http://MAC_IP:PORT/health` 会显示当前 `phoneClients`、`browserClients` 和最近一次
  `lastDelivery`。
- `http://MAC_IP:PORT/api/state` 返回当前状态，供显示页在 WebSocket 断开时每 1.5 秒轮询。
- 手机必须打开 Bridge 启动时打印的完整 `Phone display` URL；端口不是固定的，若 8765
  已被占用，Bridge 会选择 8766–8775 并写入 `mac-bridge/.halolink_port`。

### 5. 测试 ChatGPT

在 ChatGPT 网页发送一条消息：

1. 刚发送：`Thinking`
2. 工具调用或搜索：`Working`
3. 回答文字持续出现：`Streaming`
4. 回答结束：`Completed!`
5. 页面出现错误：`Error`

## 重要限制

ChatGPT 网页没有公开的“状态 API”。Chrome 扩展使用语义化按钮、ARIA 标签、消息区域变化和 DOM 观察来推断状态。OpenAI 更新网页结构后，某些选择器可能需要调整。核心逻辑集中在：

```text
chrome-extension/content.js
```

## 网络与隐私

- Bridge 只在局域网传输状态名称，不传输对话正文。
- v0.1 为本地原型，没有加入设备配对和加密。
- 请只在可信家庭或办公局域网中使用。
- 下一版可加入六位码配对、共享密钥和 TLS。

## Mac 开机自动启动（可选）

先至少成功运行一次 Bridge，然后双击：

```text
mac-bridge/install_autostart.command
```

卸载自动启动：

```text
mac-bridge/uninstall_autostart.command
```
