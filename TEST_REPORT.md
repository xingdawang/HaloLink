# HaloLink v0.1.7 省电重构测试报告

测试日期：2026-07-24（Europe/Dublin）

目标设备：HUAWEI Mate 20 Pro / Android 10 / `LYA-AL00`

## 结论

HaloLink v0.1.7 已完成 Android 端第二轮省电重构。完整模式的状态颜色、blur、glow、
圆环层数和文字布局保持不变；静态待机、断线恢复、动画帧率和网络重试策略已重新设计。

本地 Android 编译、8 个策略单元测试、Android Lint、APK 构建、Bridge Python
语法检查和 12 个 Chrome 状态识别测试全部通过。v0.1.7 APK 已成功安装到 Mate 20 Pro，
完整 Halo、极简待机、Bridge 重启恢复、Activity 前后台恢复均通过实机验证。

## 新的耗电策略

### 显示和动画

- `READY` 或 `COMPLETED` 保持 30 秒后，完整 Halo 切换为中央小状态点。
- 极简模式不绘制文字、大圆环、glow 或 `BlurMaskFilter`，窗口亮度最高为 5%。
- Bridge 或局域网持续不可用时，30 秒后也进入低亮度静态断线显示。
- 极简状态仍每两分钟移动数个像素，保留 OLED 防烧屏策略。
- 完整模式的 blur、glow、圆环绘制和状态颜色没有缩减。
- 动态状态由唯一的定时帧任务驱动，phase 根据 `elapsedRealtime` 计算：
  - `WORKING`、`STREAMING`：30 FPS
  - `SEARCHING`、`CONNECTING`：24 FPS
  - `THINKING`、`LISTENING`：20 FPS
  - `ERROR`：10 FPS
  - `READY`、`COMPLETED`：0 FPS

### 网络和发现

- 只在 Activity 前台且存在 Wi-Fi/以太网局域网时运行连接流程。
- 网络丢失后取消 mDNS、WebSocket、polling、重连和所有在途 HTTP 请求。
- 每轮 mDNS discovery 最多运行 8 秒，结束后立即释放 `MulticastLock`。
- mDNS 失败按 `5/10/20/30/30...` 秒退避。
- discovery、resolve、端口扫描、WebSocket 和 polling 各自使用代次或对象身份保护，
  旧异步回调不能覆盖新连接。

### WebSocket 和 fallback

- WebSocket 重连按 `2/4/8/15/30/30...` 秒退避，并加入 0–1000 毫秒抖动。
- 前五次直接重连已验证的 `host:port`，避免每次都扫描十个端口。
- 多次失败后先验证 `/health`；Bridge 已不可用时才回到 mDNS。
- HTTP fallback 只在前台、WebSocket 未连接且 Bridge 已选定时运行。
- polling 根据断线时间降频为 `1.5/3/6` 秒，并继续防止请求重叠。
- polling 响应同时检查 host、port 和连接代次。
- `/ws/phone` 只由 Android 每 45 秒主动 PING；aiohttp 保留自动 PONG，但不再主动
  为 phone 连接创建第二套 heartbeat。

## 自动化验证

### Android 单元测试

执行：

```text
./gradlew --no-daemon testDebugUnitTest
```

结果：

```text
tests=8, failures=0, errors=0, skipped=0
BUILD SUCCESSFUL
```

覆盖 polling 条件、静态/动态状态、每个目标 FPS、WebSocket 和 mDNS 退避、渐进
polling 间隔、大小写无关的 idle 判断，以及 5% 极简亮度上限。

### Android Lint 和 APK

执行：

```text
./gradlew --no-daemon lintDebug assembleDebug
```

结果：

```text
BUILD SUCCESSFUL
Lint: 0 errors, 23 warnings
```

23 个 warning 是目标/依赖可更新提示、固定竖屏、未使用颜色和既有 launcher icon
资源提示；本轮修改的 Java 源文件没有 Lint issue。

APK：

```text
package: com.halolink.app
versionCode: 5
versionName: 0.1.7
minSdk: 26
targetSdk: 36

apk/HaloLink-v0.1.7-debug.apk
SHA-256: a780d1c8e8916654be6c5f1dbc8b26563573648be932bad59dab4f7317a622e5
```

### Chrome 和 Bridge

- `python3 -m py_compile mac-bridge/bridge.py`：通过。
- `node --check`：Chrome background/content 脚本通过。
- `node --test chrome-extension/tests/state-detector.test.js`：12/12 通过。
- v0.1.7 Bridge 重启后 `/health.version` 为 `0.1.7`。

## Mate 20 Pro 实机验证

### 安装和连接

```text
adb install -r apk/HaloLink-v0.1.7-debug.apk
Performing Streamed Install
Success

versionCode=5
versionName=0.1.7
```

App 启动后验证保存的 `192.168.0.36:8766`，Bridge `/health` 返回
`phoneClients: 1`。

### 显示

- `WORKING`：完整橙色圆环、旋转亮弧、glow、主标题和副标题均正常。
- `READY` 30 秒后：画面只保留中央绿色状态点，背景为纯黑。
- 日志确认：`idle display entered minimal 5% brightness mode`。
- 从极简状态收到 `WORKING` 后立即恢复完整 Halo 和系统窗口亮度。

### Bridge 重启

Bridge 重启时手机收到正常关闭事件，首次重连日志为：

```text
reconnect scheduled ... attempt=0 delayMs=2741 verify=false
WebSocket opened ... generation=2
```

这符合 2 秒基础退避加 0–1000 毫秒抖动；重连后 Bridge 再次显示
`phoneClients: 1`。

### Activity 生命周期

将 App 切到后台后：

```text
LAN network callback unregistered
app left foreground; all connection and animation work paused
```

五秒后 Bridge 为 `phoneClients: 0`，没有新的 reconnect、polling 或 discovery 日志。
重新打开 App 后自动验证保存地址并恢复为 `phoneClients: 1`。

### 帧统计

在 `WORKING` 状态采集的 `gfxinfo` 时间窗约 28.4 秒：

```text
Total frames rendered: 825
Janky frames: 1 (0.12%)
```

约为 29 FPS，符合 30 FPS 上限目标；单帧 50/90/95 百分位分别为
`7/7/8 ms`。

## 仍需长期验证

- 5% 窗口亮度和小状态点已在目标手机上可见，但续航提升需要长时间 A/B 测量。
- 为避免主动断开家庭 Wi-Fi，本轮没有实机强制验证 `NetworkCallback.onLost()`；
  该路径已通过编译、Lint 和代码代次保护检查。
- mDNS 成功发现路径已由既有连接流程覆盖，但没有为了等待完整 8 秒而停掉当前可用
  Bridge；超时和退避数值由纯策略测试及回调实现检查覆盖。
- Android 10 的旧式 NSD 和沉浸模式 API 会产生编译弃用提示，但仍是当前
  `minSdk 26` 兼容路径。

## 推荐耗电 A/B 方法

1. 保持同一手机、Wi-Fi、系统亮度、充电起点和屏幕常亮时间。
2. v0.1.6 与 v0.1.7 各测试至少 2 小时，分别覆盖：
   - 90% `READY`、10% 动态状态；
   - Bridge 关闭的异常场景。
3. 每轮开始前充至相同电量并等待温度稳定，关闭其他前台 App。
4. 记录起止电量、机身温度和 `dumpsys batterystats`；每个版本至少重复三轮。
5. 比较中位数，不用单次电池百分比变化下结论。
