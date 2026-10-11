# HarmonyOS 4 / AB13X USB capture diagnostics

## Background

Device: Huawei Enjoy 70X, HarmonyOS 4, AB13X ADC, VID:PID **001F:0B21**.

Two **distinct** failure stages have been seen on the physical device:

- `UsbManager.openDevice() returned null`: the Android USB Host service did not return a `UsbDeviceConnection`; JNI/libusb were not reached.
- `No transfers submitted!`: pinned nExtCamera/libuac reached its USB Isochronous startup loop, but zero transfers were successfully submitted.

Disabling **USB audio routing** in developer options did **not** resolve the failure. This rules out a simple audio routing toggle as a sufficient workaround; it does **not** prove that no system USB driver or USBFS policy is involved.

## Diagnostic build behavior

- Refresh and Start revalidate the enumerated device and its USB permission.
- Permission callbacks are matched to the device for which access was requested. Unexpected/stale callbacks are rejected.
- USB detachment is logged, and recording is stopped if an event is delivered for the selected device.
- A **COPY USB DIAGNOSTICS** button copies a bounded, timestamped app log.
- Both CI workflows apply `scripts/patch_libuac.py` to the fixed upstream libuac checkout. The patch fails closed if the pinned code anchors change.
- If zero USB Isochronous transfers are submitted, libuac reports the interface, alternate setting, endpoint address (decimal), raw endpoint `wMaxPacketSize`, packets per transfer, allocation failures, and all eight submit results.
- Each result includes `libusb_error_name()` and a *best-effort* immediate native `errno`. The latter is not guaranteed to identify the underlying USBFS ioctl; if unknown or zero, interpret only the libusb result.
- **No AudioRecord, AAudio, AGC, NS, AEC, or microphone fallback** is added.

## Reproduction

1. Install the next **signed** diagnostic release. A CI Debug APK uses another signature and normally cannot upgrade the existing Release installation.
2. Connect AB13X through USB OTG and open the app. Press **Refresh USB devices**.
3. Press **Start recording** and grant USB access if requested.
4. On failure, press **COPY USB DIAGNOSTICS** and share the copied text. The prior failure is retained in the diagnostic section even if the status header changes.
5. If the stream starts, check increasing PCM bytes, meaningful peak level, and zero dropped data, then stop normally; compare the resulting WAV with the Windows control recording.
6. If available, include a filtered ADB logcat: `adb logcat -d -s RadioUsbRecorder:I libusb:D '*:S'`. Do not assume a nonzero errno without logs.

## How to interpret results

| Diagnostic | Meaning |
| --- | --- |
| `Authorization callback ... granted=false` | USB authorization was denied or revoked, or the callback did not match the intended device. |
| `USB present=false` after `openDevice` | Device detached or re-enumerated during startup. |
| `openDevice ... returned null` with `permission=true` | Failure is in Android USB Host device open, before JNI. |
| `No ISO transfers submitted ... submitFailed=8 ...` | Claim and alternate-setting operations succeeded; the initial submission of URBs failed. Inspect symbolic error per transfer. |
| `allocFailed>0` | Native transfer/buffer allocation failed in the app process; distinguish from USBFS rejection. |
| `libusb ... errno=0` | The kernel errno is unavailable or not set; do not infer success. |
| `LIBUSB_ERROR_ACCESS`, `LIBUSB_ERROR_BUSY`, `LIBUSB_ERROR_NO_MEM`, or `LIBUSB_ERROR_IO` | Investigate permissions, interface ownership, resource allocation, or USBFS submit behavior respectively; these are not equivalent and require further evidence. |

**Release build success is not evidence of successful UAC recording on HarmonyOS 4.**
