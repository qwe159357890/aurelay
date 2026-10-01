# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog (https://keepachangelog.com/en/1.0.0/)
and this project adheres to Semantic Versioning (https://semver.org/).

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