# Radio USB Recorder v0.1.0-alpha.3 — AB13X USB diagnostics

## 中文

这是为华为鸿蒙 4 / AB13X（VID:PID 001F:0B21）提供的**诊断测试版**，不是已经完成实机兼容性修复的声明。

- 修复 USB 授权回调的设备身份校验，重新获取当前 USB 设备对象，减少连接变化造成的状态陈旧。
- 记录授权、`openDevice()`、JNI 初始化及 USB 断开事件。
- 新增 **COPY USB DIAGNOSTICS**，一键复制带时间戳的本地启动日志。
- 将固定版本 libuac 的 `No transfers submitted!` 扩展成具体的 Isochronous 提交结果：返回码、最佳努力获取的 errno、端点/interface/alternate setting 和缓冲分配失败计数。
- 继续使用 **libusb + libuac** 直接采集 PCM，不使用 AudioRecord、系统降噪或内置麦克风回退。
- `versionCode=3`，复用原签名证书，可升级安装 alpha.2。
- 若关闭 Android 开发者选项中的 USB 自动音频路由仍失败，请在 Start 失败后点击 **COPY USB DIAGNOSTICS**，发送复制的全文，用于定位 USBFS 等时 URB 提交失败。
- 编译/签名检查通过不代表鸿蒙实机录音已验证。

## English

This is a **diagnostic prerelease** for the Huawei HarmonyOS 4 / AB13X USB ADC (VID:PID 001F:0B21), **not** a claim of proven device compatibility.

- Match USB permission callbacks to the requested device and re-resolve the currently enumerated device.
- Preserve timestamps and failures for Android USB Host open, JNI/UAC startup and USB disconnects; add **COPY USB DIAGNOSTICS**.
- Instrument the pinned libuac ISO submission loop with all eight transfer results, best-effort errno, interface/alternate setting/endpoint details, and allocation failure counts.
- Preserve raw libusb/libuac capture, with no AudioRecord/AGC/NS/AEC/microphone fallback.
- Version code 3 reuses the established release signing certificate for upgrades from alpha.2.
- After any Start failure, copy and share the diagnostic report so we can diagnose USBFS submit behavior instead of guessing.
- Signed cloud build success is distinct from on-device recording acceptance.

See [USB diagnostics](docs/USB-DIAGNOSTICS.md) in the repository.
