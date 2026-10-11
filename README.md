# Radio USB Recorder — UAC1 raw USB audio (alpha)

Standalone Android/Kotlin radio recording project. Independently maintained in this repository.

## Design

Android USB Host is used only for USB permission and device FD acquisition. A native JNI bridge opens that FD using libusb and nExtCamera/libuac, receives isochronous PCM data, and stores it using a bounded native queue/writer thread. It does NOT use Android AudioRecord, AAudio, phone microphone, AGC, NS, AEC, filtering, resampling, or AAC encoding. No microphone permission, Internet, or Root is required by the application.

Alpha supports **UAC1 mono 48 kHz 16-bit PCM input** only. Press **Start** to begin USB capture immediately after granting USB device access. WAV is automatically stored in **Music/RadioUsbRecorder** via Android MediaStore (Android 10+); no Save As picker appears. Tap **Stop & save WAV** to finalize the RIFF header. On Android 8–9, the file is saved in the app-specific external Music directory. WAV target must be seekable. App does not silently fall back to phone microphone if the USB input is lost.

## Building

## Huawei HarmonyOS 4 / AB13X acceptance

1. Connect the same AB13X ADC, radio and 3.5 mm cable verified to be working on Windows.
2. Disable receiver squelch to generate continuous FM noise.
3. Tap Start, grant USB access if prompted, and confirm it immediately records without showing a file picker. WAV is automatically created in Music/RadioUsbRecorder. Verify PCM byte count increases, recent dBFS peak updates, and dropped packets stay zero.
4. Keep recording for at least 5 minutes, especially across the previously observed ~3:54 timepoint.
5. Stop; verify RIFF header, listen for gating and compare with a WAV from Windows and the original HarmonyOS voice recorder.

Do NOT claim hardware compatibility without on-device tests. libuac supports only part of UAC1; UAC2 or unusual AB13X USB descriptors may fail. Native USBFS isochronous transfer may also be blocked by OS interface ownership, without Root; the app reports errors rather than changing input sources.

Alpha is foreground-only: keep this screen open. File splitting, Android foreground service, app-level watchdog, sample-rate negotiation, multi-device selection, and richer diagnostics are future work.

Original code: Apache-2.0. Third-party sources have separate licenses; preserve notices and corresponding sources on redistribution.
