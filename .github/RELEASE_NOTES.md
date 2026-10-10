# Radio USB Recorder v0.1.0-alpha.2 — Start / Stop UX fix

## English

- Fix: Start no longer opens the Android Save As document picker; recording begins immediately after USB device authorization.
- Automatically save raw USB WAV recordings to Music/RadioUsbRecorder using MediaStore on Android 10+ (app-specific Music directory on older Android).
- Stop finalizes the WAV header and makes the recording visible in the media library; Share last WAV is available on Android 10+.
- Show explicit recording status, PCM byte count, peak, drop count, USB errors and saved location.
- Stop after five seconds without incoming USB PCM, reporting the device compatibility problem rather than falling back to the internal microphone.
- Upgrade versionCode to 2 so this signed APK replaces alpha.1. On-device AB13X compatibility remains unverified.
- Verification: GitHub Actions compiles arm64 Kotlin + native libusb/libuac. The signed APK is checked with apksigner against the pinned SHA-256 certificate fingerprint; checksum is attached.

## 中文

- 修复：Start 不再打开系统“另存为”对话框，完成 USB 授权后立即开始录音。
- 自动把 USB 原始 WAV 保存至 Music/RadioUsbRecorder；Android 10+ 使用 MediaStore，旧版 Android 使用应用专属 Music 目录。
- Stop 完成 WAV 文件头并使录音出现在系统媒体库；Android 10+ 支持“分享上次录音”。
- 显示录制状态、PCM 字节数、峰值、丢包、USB 错误和文件位置。
- 如果五秒内未收到 USB PCM，则停止并报告兼容性异常，不回退手机内置麦克风。
- versionCode 升至 2，可覆盖安装 alpha.1；鸿蒙 4 上的 AB13X 实机兼容性仍未验证。
- 验证：GitHub Actions 编译 arm64 Kotlin + 原生 libusb/libuac；通过 apksigner 按固定证书 SHA-256 指纹验证签名，并附文件校验值。
