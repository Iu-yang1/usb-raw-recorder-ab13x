# Radio USB Recorder — Alpha / 测试版

## English

- Kotlin Android user interface with USB permission management.
- libusb + libuac direct USB Audio Class 1 input; no Android AudioRecord capture or microphone fallback.
- 48 kHz, 16-bit, mono WAV, live peak meter, transfer error and dropped-packet diagnostics.
- This is experimental. HarmonyOS 4 + AB13X hardware operation **has not been validated**. Keep the app visible during recording.
- Signing: Android APK signature is checked by apksigner; SHA-256 is published alongside the APK.

## 中文

- 原生 Kotlin Android 界面与 USB 设备权限管理。
- 通过 libusb + libuac 直接采集 UAC1 数据，不走 Android AudioRecord，也不会回退至手机内置麦克风。
- 48 kHz、16-bit、单声道 WAV，实时峰值电平及 USB 传输异常、丢包诊断。
- 实验性版本，**尚未在鸿蒙 4 + AB13X 上完成硬件验收**；录音时需保持应用在前台。
- 签名：使用 apksigner 验证 Android APK 签名，同时公开 APK 的 SHA-256 校验值。
