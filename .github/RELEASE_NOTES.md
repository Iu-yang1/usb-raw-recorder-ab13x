# Radio USB Recorder v0.1.0-alpha.4 — UAC1 EPIPE startup compatibility

## 中文

针对华为 HarmonyOS 4 上 AB13X USB Audio（VID:PID 001F:0B21）在启动时出现
`No ISO transfers submitted / LIBUSB_ERROR_IO(errno=32)` 的兼容性修复。

- 与 Linux `snd-usb-audio` 的 UAC1 初始化顺序对齐：**先启用 AudioStreaming Alternate Setting 1，再通过 SET_CUR 设置 48 kHz 采样率**。旧实现顺序相反。
- 使用标准 GET_INTERFACE 回读核对接口 2 的 Alternate Setting，必要时重新设置。
- 当首个 USBFS Isochronous URB 因 EPIPE 失败时，只针对 AB13X 进行一次受控 Alt 0 → Alt 1 恢复与重新设置采样率，避免连续发送八次相同的失败 URB。
- 修复停止流的次序：先等待已取消的 URB 全部完成回收，再切换 Alt 0；限制 UAC 控制传输超时。
- 保留 alpha.3 的 USB 诊断、原始 PCM/WAV、签名证书与 USB 声卡独占采集路线；**不使用 AudioRecord、NS、AGC、AEC、压缩或手机内置麦克风回退**。
- versionCode 4，可覆盖安装 alpha.3。
- 自动 CI 构建/签名/静态回归检查不等于已实机验证。鸿蒙设备是否允许等时 URB 仍取决于 USBFS 和主机控制器；不能保证所有机型都能实现无 Root 采集。

## English

Compatibility fix for USBFS `EPIPE` / zero submitted isochronous transfers with
AB13X UAC1 on Huawei HarmonyOS 4.

- Match Linux snd-usb-audio UAC1 ordering: enable the streaming alternate setting
  **before** setting sample rate via endpoint SET_CUR.
- Read back the selected interface with GET_INTERFACE; retry setting a mismatched alt.
- On the first AB13X ISO submit failure with EPIPE, perform **one bounded** alt0/alt1
  reinitialization and rate-setting attempt, rather than submitting eight identical
  failing URBs.
- During Stop, reap canceled URBs before resetting alt0. Give control transfers
  bounded timeouts.
- Retain direct native libusb/libuac PCM/WAV, the alpha.3 diagnostics, and the original
  release signing certificate. No Android AudioRecord or microphone fallback.
- Version code 4 supports in-place upgrade.
- Build/signature checks are **not** proof of recording acceptance on HarmonyOS 4.
