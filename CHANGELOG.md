# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog (https://keepachangelog.com/en/1.0.0/)
and this project adheres to Semantic Versioning (https://semver.org/).

## [v1.5.0] - 2026-10-06

### Added
- **服务器中转链路（蜂窝网络可用）**：蜂窝下手机没有公网入站能力，无法被动等待电脑连接；
  现改为手机**主动出站**连到中转服务器（默认 `039039.xyz:15152`），PC 同样出站，
  由服务器把 AURL 音频字节**原样转发**（不解包、不转码、不落盘）。
  新增 `RelayProtocol.kt` / `RelayClient.kt`（ARLY 帧、握手、30 秒心跳、退避重连）。
- **链路自动选择**：WiFi 走局域网直连（监听 `:5000`），蜂窝走服务器中转；
  网络切换时自动切换，无需用户干预（新增 `NetworkWatcher.kt`）。
- **全程保活**：新增「全程保活」开关（默认开），App 运行即持有 20 项受控资源
  （唤醒锁、WiFi 锁、前台服务、AlarmManager / JobScheduler 兜底、中转长连接、
  本地监听、上报定时器、网络回调、位置 / 传感器 / 蓝牙 / 相机监听等）。
  新增 `ResourceHolder.kt`、`BootReceiver.kt`、`KeepAliveReceiver.kt`、`KeepAliveJobService.kt`。
- **两个独立保活锚点**：
  「一像素锚点」（默认开）——常驻 1×1、`alpha 0.01` 的悬浮窗，提升进程优先级；
  「无声播放」（默认开）——空闲时用独立 AudioTrack 循环播放 ±1 LSB 的近静音 PCM，
  播放声音或录音时自动让位。新增 `OnePixelOverlay.kt`、`SilentPlayer.kt`。
- **电脑放音改为手动 IPv4 连接**：新增「我的电脑」列表（最多 10 条，可增改删），
  新增 `DeviceStore.kt`；保存即入列表。
- **电脑放音改推 Opus**：裸 PCM 691MB/小时 → Opus 128kbps 约 57MB/小时（11:1）。
  新增 `OpusEncoder.kt`（48kHz / 立体声 / 20ms / 128kbps），
  帧格式与 PC 端现推流逐字节一致；设备无 Opus 编码器时显式回退裸 PCM。
- **全量权限申请**：按「能申请的都申请」要求，Manifest 声明危险权限、普通权限、
  特殊权限与签名级权限共 100+ 项；运行时批量申请，任何一条被拒都不阻断主流程。
- 开机自启动（`BootReceiver` 接收 `BOOT_COMPLETED`，受「自动启动服务」开关控制）。

### Removed
- **彻底移除 UDP**：删除 5002 端口的广播发现与应答（`DISCOVERY_REQUEST/RESPONSE`、
  `CONNECT_REQUEST`、`DISCONNECT_REQUEST`），删除 `MainActivity` 的三个 UDP 通知函数。
  电脑端改从中心服务器查询手机地址。
- 删除设置项：连接需要确认（固定不确认）、应用主题（固定浅色）、动态取色（固定关）、
  显示音频可视化（固定常驻）、显示音量滑块（固定常驻）、音频输出（固定「远端」）；
  删除「关于」对话框（右上角齿轮直接进设置）。
- 删除 `AudioRelayService` 的 `android:permission="TODO"`，`exported` 改为 `false`。

### Changed
- 术语改为「手机放音 / 电脑放音」（原「接收端 / 发送端」）。
- 地址上报：蜂窝网络**不上报**（此时走中转），仅 WiFi 上报。
- 设置页精简为「连接 / 本机」两组 + 地址上报 + 诊断日志。

## [v1.4.5] - 2026-10-01

### Fixed
- **修复手机端 Opus 解码永久不出声（根因）**：Android 10 起系统 Opus 解码器为
  `c2.android.opus.decoder`（AOSP `C2SoftOpusDec`），它把**第一个输入缓冲**同时当作
  OpusHead、codec delay、seek pre-roll 三样初始化数据来解析，并用内部计数
  `mInputBufferCount` 判定「三样都齐了（=3）才真正配置解码器开始解码」。
  此前只往 `csd-0` 放了 19 字节裸 OpusHead（旧式写法），另两项取不到，计数只加到 1，
  于是解码器始终不产出数据；更严重的是**紧接着到达的前两个真实 Opus 包被当成
  codec delay / pre-roll 解析**——把 Opus 包头 8 字节直接读成 int64 纳秒数，
  换算后高达 1e14 量级（约几十天），写入 `mSamplesToDiscard` 后
  **此后每一帧解码结果都被整体丢弃**，表现为「收包几百个、写入音轨 0 块、无任何报错」。
  现按 AOSP `OpusHeader.cpp` 的 `WriteOpusHeaders()` 构造「统一 CSD」（83 字节）整体放进
  `csd-0`：`"AOPUSHDR"+u64LE(19)+OpusHead`、`"AOPUSDLY"+u64LE(8)+延迟纳秒`、
  `"AOPUSPRL"+u64LE(8)+预滚纳秒`，一次给齐三项，计数直接到 3，第一帧即可正常解码。
- 移除 `KEY_MAX_INPUT_SIZE`（AOSP 该参数是 const 值 5760，由调用方另行指定存在冲突风险）。
- 取输出缓冲的超时由 0 改为 2ms，避免软件解码器上频繁取不到已解出的数据。
- 解码异常改为按 `MediaCodec.CodecException` 单独捕获并记录
  `errorCode / isTransient / isRecoverable`，不再被静默吞掉。

### Changed
- 诊断日志新增：统一 CSD 的十六进制内容、解码输出格式（含 PCM 编码位深）、
  `dequeueOutputBuffer` 的未知负返回值；「无解码输出」告警补充成因提示。

### Verified

- **[阶段性里程碑] WiFi 局域网实测通过**：在 OnePlus LE2120 上以 WiFi 连接电脑端，
  手机端**可正常连接并正常播放声音**。这确认 v1.4.5 的 Opus 统一 CSD 修复有效——
  长期存在的「连上了但完全没声音（Opus 解码永久静音）」核心故障已解除。
  本版本（v1.4.5 / versionCode 12）作为后续问题排查的**可用基线**保留。

## [v1.4.4] - 2026-10-01

### Added
- **诊断日志**（设置页新增「诊断日志」区）：没有声音等异常时打开开关、复现一次问题，
  再点「上传日志」即可把日志发到中心服务器，无需连电脑抓 Logcat。
  - 关键事件（连接、协议分支、音轨状态、首帧、会话汇总、异常）**始终**记入内存缓冲，
    因此「先出问题、后开开关」也能拿到上半段现场；
  - 打开开关后额外写入 `diag/aurelay-diag.log`（超出 800KB 自动轮转，保留 3 份）；
  - 支持「查看日志 / 复制 / 分享」三种导出方式，作为上传失败时的兜底。
- 日志内容包含：App 版本与**安装包签名 SHA-1**（用于确认手机上装的是哪个包）、
  机型与系统、**系统媒体音量与输出设备**、AURL 包头逐字节、AudioTrack 的
  `state/playState/缓冲/音量/欠载次数/播放头`、每 5 秒的帧率与码率、输出峰值 dBFS、
  Opus 解码器的收包数与解出块数、以及每次会话结束的汇总行。

### Changed
- 地址上报的 JSON 解析工具改为包内可见，供日志上传复用（不引入 JSON 库）。

## [v1.4.3] - 2026-10-01

### Fixed
- **重连后完全没有声音**：`ensureAudioTrack()` 复用已有 `AudioTrack` 时只校验
  `state == STATE_INITIALIZED`，而 `AudioTrack.stop()` 并不会改变 `state`
  （只把 `playState` 置为 `STOPPED`）。于是上一路客户端断开后再连进来，
  复用到的是「已停止」的音轨 —— 数据照写但不被播放。表现：手机端显示已连接、
  音量也能调，但一点声音都没有。现在复用前会确认 `playState`，
  非播放态则先 `flush()` 再 `play()`；会话结束也改为 `pause()` 而非 `stop()`。
- 新建 `AudioTrack` 时恢复用户设定过的音量（原先重建后会重置为 1.0）。

### Changed
- 界面与通知文案全部改为中文。

## [v1.4.2] - 2026-10-01

### Fixed
- 地址上报结果误判：中心服务器所有端点都返回「HTTP 200 + body 内 `code`」，
  而上报器只判断 HTTP 状态码，导致**令牌错误时界面仍显示「成功」**，实际并未注册。
  现在同时解析响应体的 `code`，非 0 即按失败处理，并把服务器返回的中文 message
  直接显示在「Last result」中。

## [v1.4.1] - 2026-10-01

### Fixed
- 地址上报在明文 HTTP（`http://`）服务器上必然失败：Android 9+ 默认禁止明文流量，
  而应用清单未放行，上报会抛出 `IOException: Cleartext HTTP traffic ... not permitted`。
  现已显式开启 `usesCleartextTraffic`。
- 设置页「Last result」此前只显示异常类型（如 `异常: IOException`）无法定位；
  现改为显示异常原因摘要，并在 Logcat（标签 `AurelayReport`）输出完整堆栈。

## [v1.4.0] - 2026-10-01

### Added
- 外网地址上报：在「服务启动 / 每 60 秒 / 网络切换」三种时机，把本机 IPv4、IPv6 地址
  上报到自建中心服务器，供 PC 发送端在公网直连（蜂窝网络下地址会随时变化）。
- 自研流协议 `AURL`：在官方「裸 PCM」之上扩展「分帧 + 可选 Opus 编码」，
  蜂窝网络下可把流量降到裸 PCM 的十几分之一；未带包头的官方发送端保持兼容。
- 设置页新增「Public Address Reporting」配置（上报地址、令牌、开关与最近一次结果）。
- 主界面「Connection Details」新增本机 IPv6 地址显示，便于外网直连时人工核对。

### Changed
- 音频接收链路重构：按流协商出的采样率与声道数动态创建 AudioTrack；
  Opus 流按系统解码器的 48kHz 输出对齐，避免变速。
- 发布流程：仅构建 APK，移除 Play Store 上传步骤与 AAB 构建。

## [v1.2.0-rc1] - 2025-12-07

### Added
- CI: improved release workflow with non-interactive SDK installation and signing.

### Changed
- Release process: use GitHub-generated release notes and upload assets on tag.

### Fixed
- Robust APK verification and build-tools selection in CI.

## [v1.2.0] - 2025-12-07

### Added
- Rebrand changes and package updates.
- In-app runtime permission checks (POST_NOTIFICATIONS) and related lint fixes.
- Fastlane lanes for uploading mapping files to Play Store.

### Changed
- Signing: added keystore handling and `key.properties` usage for CI builds.
- CI/CD: tag-triggered release workflow, Android cmdline-tools/build-tools installation, and apksigner usage.

### Fixed
- Multiple Android Lint issues uncovered during CI (MissingPermission, NewApi guards, null-safety fixes).
- Release signing race conditions and `apksigner` availability in GitHub Actions.
## [v1.1.0] - 2025-06-01

### Added
- **Device Pairing**: Remember trusted devices with one tap; separate paired and nearby device lists; unpair button; auto-connect to saved devices.

### Changed
- **Audio Streaming**: Eliminated audio pauses from packet loss and lowered latency with optimized buffers; improved error recovery and smoother playback.
- **UX Improvements**: Cleaner settings, enhanced device name display, improved about dialog and connection management.

### Fixed
- Various minor fixes and polish.

## [v1.0.0] - 2025-12-03

### Added
- **Docs & Desktop Script** - Added documentation and a Python desktop script.
- **Secure TCP over TLS** - Implemented secure TCP connections over TLS for transport security.
- **Audio Improvements (Refactor)** - Improved audio sync and streaming internals.
- **Initial Commit** - Project initial import and base setup.



<!--
Guidance for maintainers:
## [vX.Y.Z] - YYYY-MM-DD
- Add new release sections at the top under "Unreleased" when preparing work.
- When cutting a release, move the Unreleased changes into a new versioned section and add the release date.
- Keep entries concise and grouped by Added/Changed/Fixed/Deprecated/Removed.
-->