# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog (https://keepachangelog.com/en/1.0.0/)
and this project adheres to Semantic Versioning (https://semver.org/).

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