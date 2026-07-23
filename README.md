# HaloLink v0.1.5

把一台 Android 手机变成 ChatGPT 网页的实时状态指示器。

## 组成

- `chrome-extension/`：读取 ChatGPT 网页状态。
- `mac-bridge/`：接收浏览器状态，通过局域网广播，并用 mDNS 让手机自动发现。
- `android-app/`：Android 原生应用，自动发现并验证 Bridge，显示中央 Halo 圆环。
- `apk/`：可直接安装的 debug APK 与 SHA-256 校验文件。

## 支持的状态

- `Searching`：灰白旋转圆环
- `Ready`：暗绿色静态圆环
- `Thinking`：红色呼吸圆环
- `Working`：橙色旋转圆环
- `Responding`：红色旋转圆环（协议状态名为 `STREAMING`）
- `Listening`：蓝色脉冲圆环
- `Completed!`：绿色圆环与勾号
- `Error`：紫色闪烁圆环

## 快速开始

### 1. 在 Mac 启动 Bridge

打开 `mac-bridge` 文件夹，双击：

```text
run_bridge.command
```

第一次运行会创建 Python 虚拟环境并安装依赖。Bridge 优先监听
`0.0.0.0:8766`；如果端口被占用，会依次尝试 `8767–8775`。启动日志会打印：

- 当前项目目录与 PID
- 最终监听端口
- 手机可访问的局域网地址
- mDNS 服务名
- 当前浏览器与手机连接数

Bridge 使用进程锁保证同一份项目只有一个实例。重复启动会报告当前 PID 和端口，
不会再创建第二个 Bridge。

> `127.0.0.1:8765` 可由 Camera Activity Notifier 等本机服务使用。HaloLink v0.1.5
> 不会占用或修改该服务。

### 2. 安装 Chrome 扩展

1. 在 Chrome 打开 `chrome://extensions/`
2. 开启 **Developer mode**
3. 点击 **Load unpacked**
4. 选择本项目中的 `chrome-extension` 文件夹
5. 打开或刷新 `https://chatgpt.com/`

扩展只会接受满足以下条件的 Bridge：

- `/health.ok === true`
- `/health.product === "HaloLink"`
- `pid` 是有效整数
- 响应中的 `port` 与实际访问端口一致

扩展弹窗会显示连接状态、Bridge PID、端口、版本、手机连接数和最近状态。

### 3. 安装 Android App

仓库中的 APK：

```text
apk/HaloLink-v0.1.5-debug.apk
```

连接手机后可使用：

```bash
adb install -r apk/HaloLink-v0.1.5-debug.apk
```

App 优先使用 mDNS 广播的真实端口，并验证 `/health`；发现失败时会扫描
`8766–8775`。连续失败会清理旧的 SharedPreferences 地址并重新发现，不需要清除
应用数据或重新安装。

状态更新同时使用：

- `/ws/phone` WebSocket 实时推送
- `/api/state` 每 1.5 秒 HTTP polling 备用通道

因此 WebSocket 暂时中断时，手机仍可继续更新；Bridge 恢复后会自动重连。

### 4. 独立测试圆环

Bridge 运行时打开另一个终端：

```bash
cd mac-bridge
./demo_states.command
```

手机会依次显示 Ready、Thinking、Working、Responding、Completed 和 Error。每个状态
都会输出真实投递计数：

```text
Phone delivery: 1/1 sent, 0 failed
```

`0/0` 表示命令已到达 Bridge，但当前没有手机连接到该实例。

## 连接诊断

- `http://MAC_IP:PORT/health`：Bridge 身份、版本、PID、项目目录、端口、连接数和最近投递。
- `http://MAC_IP:PORT/api/state`：当前状态，供 Android polling 使用。
- `ws://MAC_IP:PORT/ws/phone`：手机实时状态通道。
- `ws://127.0.0.1:PORT/ws/browser`：Chrome 扩展状态通道。

理想的 `/health` 至少应满足：

```json
{
  "ok": true,
  "product": "HaloLink",
  "port": 8766,
  "browserClients": 1,
  "phoneClients": 1,
  "lastDelivery": {
    "attempted": 1,
    "sent": 1,
    "failed": 0
  }
}
```

Android 日志统一使用 `HaloLink` TAG：

```bash
adb logcat -v time HaloLink:I '*:S'
```

日志会记录 mDNS、端口扫描、健康验证、选定 Bridge、WebSocket、HTTP polling、状态应用
和重连过程。

## 测试 ChatGPT

在 ChatGPT 网页发送消息后，典型状态链为：

1. 刚发送：`Thinking`
2. 工具调用或搜索：`Working`
3. 回答文字持续出现：`Responding`
4. 回答结束：`Completed!`
5. 页面出现错误：`Error`

ChatGPT 网页没有公开状态 API。扩展通过语义化按钮、ARIA 标签、消息区域变化和 DOM
观察推断状态。核心逻辑位于 `chrome-extension/content.js`。

## Mac 开机自动启动（可选）

先成功运行一次 Bridge，然后双击：

```text
mac-bridge/install_autostart.command
```

卸载 HaloLink 自动启动：

```text
mac-bridge/uninstall_autostart.command
```

该脚本只管理 `com.halolink.bridge`，不会操作其他 LaunchAgent。

## Android APK 云端构建

GitHub Actions 工作流位于：

```text
.github/workflows/build-android-apk.yml
```

工作流会编译 v0.1.5 debug APK、生成 SHA-256，并上传构建产物。

## 网络与隐私

- Bridge 只传输状态名称，不传输对话正文。
- 当前是局域网原型，没有设备配对和加密。
- 请只在可信家庭或办公局域网中使用。
- 后续版本可加入六位码配对、共享密钥和 TLS。
