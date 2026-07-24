# HaloLink v0.1.6 省电优化测试报告

测试日期：2026-07-24（Europe/Dublin）

目标设备：HUAWEI Mate 20 Pro / Android 10

测试方式：Gradle、Android Lint、单元测试、APK 元数据、Bridge 日志、`/health`、
Chrome 扩展回归测试和现有手机 WebSocket 连接

## 结论

HaloLink v0.1.6 已完成手机端省电优化，同时保留：

- `/ws/phone` 实时状态推送；
- WebSocket 断线时的 `/api/state` HTTP polling 备用通道；
- Bridge 自动发现、端口验证和断线重连；
- Ready、Thinking、Working、Responding、Completed、Error 全部显示状态；
- 手机屏幕常亮的实时状态指示器用途。

本地干净构建、3 个 Android 省电策略测试、Android Lint 和 12 个 Chrome 状态识别
测试均通过。运行中的 v0.1.6 Bridge 上，浏览器和手机均自动重连，完整 Demo 状态序列
每一步均投递 `1/1`。

## 原有耗电原因

1. Android App 即使 WebSocket 正常，也每 1.5 秒请求一次 `/api/state`，约
   2,400 次 HTTP 请求/小时。
2. `HaloView` 的无限 `ValueAnimator` 在 Ready 和 Completed 等静态状态下仍持续
   `invalidate()`；60 Hz 屏幕上理论上可达到约 216,000 次重绘/小时。
3. 圆环使用全局软件渲染，且每帧新建 `RectF`、`BlurMaskFilter`、`SweepGradient`
   和渐变数组，增加 CPU、GC 和绘图负担。
4. mDNS 的 `MulticastLock` 从 App 启动一直持有到 `onDestroy()`。
5. App 进入后台后没有在 `onStop()` 暂停网络、发现、polling 和动画。
6. 手机浏览器 `/display` 在 WebSocket 健康时仍保留一个每 1.5 秒唤醒的 JS 定时器，
   虽然不会真正发送 HTTP 请求。
7. 屏幕为了状态指示功能保持常亮，长时间 Ready 时仍使用系统亮度。

## 修改内容

### Android 网络和生命周期

- WebSocket 健康后立即停止 HTTP polling。
- 只有前台、已选定 Bridge、WebSocket 未连接时才启动 1.5 秒 polling。
- polling 加入 in-flight 合并，避免慢请求重叠。
- WebSocket 失败或关闭后恢复 polling，并继续自动重连。
- `onStop()` 暂停 WebSocket、polling、mDNS、重连任务和动画。
- 回到 `onStart()` 后重新验证保存的 Bridge 地址并自动恢复连接。
- mDNS `MulticastLock` 仅在发现期间持有，发现停止或成功解析后立即释放。
- 删除未使用的 `WAKE_LOCK` 权限；屏幕常亮仍由可见 Activity 的
  `FLAG_KEEP_SCREEN_ON` 控制。

### Android 绘图和亮度

- Ready、Completed 停止 `ValueAnimator`，只在状态改变或每两分钟 OLED 像素位移时
  重绘。
- Thinking、Working、Responding、Listening、Error 和连接状态保留动态效果。
- Android 9 及以上使用硬件渲染；仅 Android 8.x 保留软件兼容路径。
- 缓存并复用 `RectF`、模糊滤镜、SweepGradient 和渐变数组，消除逐帧对象分配。
- 相同状态和标签重复到达时不再触发重绘或重置亮度计时。
- Ready、Completed 显示 30 秒后，将 App 窗口亮度限制在当前系统亮度和 12% 中较低的
  数值；新活动状态到达时立即恢复系统亮度。

### `/display` 浏览器页面

- WebSocket 正常时销毁 polling 定时器，而不是每 1.5 秒空调用一次。
- WebSocket 断线时才创建 polling 定时器。
- 页面隐藏后暂停 polling 和重连；重新可见时自动恢复。

### 自动化和版本

- Android 版本升级到 `0.1.6` / `versionCode 4`。
- 新增 `EnergyPolicy` 纯 Java 策略及 3 个单元测试。
- GitHub Android 工作流现在执行
  `testDebugUnitTest lintDebug assembleDebug`。
- 构建脚本和 APK 工作流产物名更新为 `HaloLink-v0.1.6-debug.apk`。

## 自动化验证

### Android 干净构建

执行：

```text
./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug
```

结果：

```text
BUILD SUCCESSFUL
48 actionable tasks
```

### Android 单元测试

`EnergyPolicyTest`：

```text
tests=3, failures=0, errors=0, skipped=0
```

覆盖：

- WebSocket 健康时禁止 polling；
- 后台或未选定 Bridge 时禁止 polling；
- 仅断线前台状态启用备用 polling；
- Ready、Completed 为静态和空闲状态；
- Thinking、Working、Streaming、Error 等动态状态继续动画。

### Android Lint

结果：

```text
0 errors, 22 warnings
```

原先与逐帧 `RectF`、`BlurMaskFilter`、`SweepGradient` 分配有关的 5 个
`DrawAllocation` 警告已全部消除。剩余警告为 SDK/Gradle 可用更新、固定竖屏和已有图标
资源等，与本次省电逻辑无关。

### APK

```text
package: com.halolink.app
versionCode: 4
versionName: 0.1.6
minSdk: 26
targetSdk: 36
```

未再包含 `android.permission.WAKE_LOCK`。

本地 APK：

```text
apk/HaloLink-v0.1.6-debug.apk
SHA-256: 69509302a7e66e6fa054de0a4ed2a44c1afde27f1ff907f8358ffa5a3f331b54
```

### Chrome 扩展和 Bridge

- `node --test chrome-extension/tests/state-detector.test.js`：12/12 通过。
- Chrome background/content/popup JavaScript 语法：通过。
- `/display` inline JavaScript 语法：通过。
- Bridge Python 编译检查：通过。
- shell 脚本语法检查：通过。

## 当前连接与状态投递验证

重启 `com.halolink.bridge` 后：

```json
{
  "version": "0.1.6",
  "port": 8766,
  "browserClients": 1,
  "phoneClients": 1
}
```

手机 `192.168.0.70` 和 Chrome 扩展均自动重连。Demo 实测：

| 协议状态 | 手机投递 |
| --- | --- |
| READY | 1/1 |
| THINKING | 1/1 |
| WORKING | 1/1 |
| STREAMING / Responding | 1/1 |
| COMPLETED | 1/1 |
| ERROR | 1/1 |
| READY | 1/1 |

最终 `/health`：

```json
{
  "state": {"state": "READY", "source": "test"},
  "browserClients": 1,
  "phoneClients": 1,
  "lastDelivery": {"attempted": 1, "sent": 1, "failed": 0}
}
```

## 预期节省

在 WebSocket 健康、手机保持 Ready 的典型空闲场景：

- HTTP polling：约 `2,400 次/小时 → 0 次/小时`；
- 圆环重绘：约 `216,000 次/小时 → 约 30 次/小时`，另加实际状态变化；
- mDNS MulticastLock：从 Activity 全生命周期持有改为仅发现阶段持有；
- Ready/Completed：30 秒后降低 App 窗口亮度；
- WebSocket 20 秒 keepalive 保留，以维持实时推送和快速断线检测。

以上是由执行路径和频率得出的预期改善，不等同于电池百分比测量。实际续航提升仍受
系统亮度、OLED 面板、Wi-Fi 信号和状态动画持续时间影响。

## 实机验证边界

测试时 Bridge 可以看到 `192.168.0.70` 的手机 WebSocket 已连接，完整状态序列均已
`1/1` 投递。但本机 `adb devices` 没有列出设备，旧无线 ADB 地址
`192.168.0.70:5555` 返回 `Connection refused`，因此本轮无法：

- 将 v0.1.6 APK 安装到 Mate 20 Pro；
- 使用 scrcpy 观察新 APK 的视觉状态；
- 通过 logcat 直接确认 “WebSocket healthy → polling stopped”；
- 进行 v0.1.5/v0.1.6 的定时电量 A/B 测试。

因此，代码、构建、策略测试和实时网络投递已经通过；新 APK 的视觉与实际电池耗电
测试需要手机重新开启无线 ADB 或通过 USB 连接后补充。
