package org.iu.radio.usbrecorder

import android.app.Activity
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10

/**
 * Direct USB audio recorder.
 *
 * Start begins capture immediately after one-time USB access authorization; no Save As dialog.
 * WAV output is visible in Music/RadioUsbRecorder through MediaStore on API >= 29.
 * Native code alone receives PCM from libusb/libuac, never AudioRecord or the phone microphone.
 * The app is foreground-only in this alpha.
 */
class MainActivity : Activity() {
    private val permissionAction = "org.iu.radio.usbrecorder.USB_PERMISSION"
    private lateinit var usbManager: UsbManager
    private lateinit var info: TextView
    private lateinit var metrics: TextView
    private lateinit var deviceButton: Button
    private lateinit var recordButton: Button
    private lateinit var stopButton: Button
    private lateinit var shareButton: Button
    private lateinit var diagnosticsView: TextView
    private val diagnostics = ArrayDeque<String>()
    private var pendingDeviceName: String? = null
    private var device: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var recording = false
    private var startedAt = 0L
    private var wavUri: Uri? = null
    private var wavFile: File? = null
    private var lastRecording: Uri? = null
    private var outputLabel = ""
    private val handler = Handler(Looper.getMainLooper())

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                permissionAction -> {
                    @Suppress("DEPRECATION")
                    val authorizedDevice = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val expectedName = pendingDeviceName
                    pendingDeviceName = null
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val fresh = usbManager.deviceList.values.firstOrNull {
                        it.deviceName == expectedName
                    }
                    addDiagnostic(
                        "Authorization callback: granted=$granted expected=$expectedName " +
                            "returned=${authorizedDevice?.deviceName} present=${fresh != null} " +
                            "hasPermission=${fresh?.let(usbManager::hasPermission)}"
                    )
                    if (!granted || expectedName == null ||
                        authorizedDevice?.deviceName != expectedName ||
                        fresh == null || !usbManager.hasPermission(fresh)
                    ) {
                        refreshDevice()
                        showMessage("USB authorization failed or device changed. Refresh and try again.")
                        return
                    }
                    device = fresh
                    beginRecording()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    @Suppress("DEPRECATION")
                    val removed = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    addDiagnostic("USB detached: ${removed?.deviceName}")
                    if (removed != null && removed.deviceName == device?.deviceName) {
                        if (recording) stopRecording("USB device disconnected.")
                        device = null
                        pendingDeviceName = null
                        refreshDevice()
                    }
                }
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (recording) {
                try {
                    val result = NativeRecorder.snapshot()
                    if (result.size >= 4) {
                        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                        val seconds = elapsedMs / 1000L
                        val peak = result[2] / 1_000_000.0
                        val dbfs = if (peak > 0.0) {
                            "%.1f dBFS".format(Locale.US, 20.0 * log10(peak))
                        } else {
                            "-inf dBFS"
                        }
                        metrics.text = "RECORDING  " + seconds + " s" +
                            "\nPCM bytes: " + result[0] +
                            "\nDropped packets: " + result[1] +
                            "\nRecent peak: " + dbfs +
                            "\nUAC transfer error: " + result[3] +
                            "\nFile: " + outputLabel
                        when {
                            result[3] != 0L -> stopRecording(
                                "USB stream/write error " + result[3] + "; stopped without microphone fallback."
                            )
                            elapsedMs >= 5000L && result[0] == 0L -> stopRecording(
                                "No USB PCM arrived for 5 seconds; capture stopped. Check USB interface/UAC compatibility."
                            )
                        }
                    }
                } catch (e: Exception) {
                    stopRecording("USB recorder diagnostics failed: " + e.message)
                }
            }
            handler.postDelayed(this, 400L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        buildUi()
        val filter = IntentFilter(permissionAction).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(permissionReceiver, filter)
        }
        refreshDevice()
        addDiagnostic("App initialized: SDK=${Build.VERSION.SDK_INT}, manufacturer=${Build.MANUFACTURER}, model=${Build.MODEL}")
        handler.post(ticker)
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        fun dp(px: Int) = (px * density).toInt()
        fun label(value: String, size: Float) = TextView(this).apply {
            text = value
            textSize = size
            setPadding(0, dp(9), 0, dp(9))
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(22))
        }
        layout.addView(label("Radio USB Recorder", 24f).apply { gravity = Gravity.CENTER_HORIZONTAL })
        layout.addView(label("Native USB UAC input • No AGC / NS / AEC", 13f))
        info = label("Searching for USB microphone…", 15f)
        layout.addView(info)
        deviceButton = Button(this).apply {
            text = "Refresh USB devices"
            setOnClickListener { refreshDevice() }
        }
        layout.addView(deviceButton)
        recordButton = Button(this).apply {
            text = "Start recording"
            setOnClickListener { requestRecording() }
        }
        layout.addView(recordButton)
        stopButton = Button(this).apply {
            text = "Stop & save WAV"
            isEnabled = false
            setOnClickListener { stopRecording() }
        }
        layout.addView(stopButton)
        shareButton = Button(this).apply {
            text = "Share last WAV"
            isEnabled = false
            setOnClickListener { shareLastRecording() }
        }
        layout.addView(shareButton)
        metrics = label("Idle. Tap Start to record immediately.", 15f)
        layout.addView(metrics)
        layout.addView(label("USB diagnostics (copy this text after a failure)", 15f))
        val copyButton = Button(this).apply {
            text = "COPY USB DIAGNOSTICS"
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(
                    "Radio USB Recorder diagnostics", diagnosticReport()
                ))
                addDiagnostic("Diagnostic report copied")
            }
        }
        layout.addView(copyButton)
        diagnosticsView = label("No USB diagnostic events yet.", 12f).apply {
            setTextIsSelectable(true)
        }
        layout.addView(diagnosticsView)
        layout.addView(label(
            "Auto-save: Music/RadioUsbRecorder (Android 10+).\n" +
                "Only the first USB AudioStreaming input is selected in this alpha. " +
                "Keep this screen open. Requested format: 48 kHz / 16-bit / mono PCM. " +
                "If USB capture fails, the app reports an error and never uses the phone mic.",
            13f
        ))
        val scroll = ScrollView(this)
        scroll.addView(layout, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        setContentView(scroll)
    }

    private fun addDiagnostic(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$timestamp  $message"
        Log.i("RadioUsbRecorder", line)
        if (diagnostics.size >= 60) diagnostics.removeFirst()
        diagnostics.addLast(line)
        diagnosticsView.text = diagnostics.joinToString("\n")
    }

    private fun diagnosticReport(): String =
        "Radio USB Recorder USB diagnostic build (alpha.2 baseline)\n" +
            "Android SDK: ${Build.VERSION.SDK_INT}\n" +
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Selected USB: ${device?.deviceName ?: "none"}\n" +
            "VID:PID: ${device?.let { "%04X:%04X".format(Locale.US, it.vendorId, it.productId) } ?: "none"}\n" +
            "Permission: ${device?.let(usbManager::hasPermission)}\n" +
            "Events:\n" + diagnostics.joinToString("\n")

    private fun hasAudioStreaming(candidate: UsbDevice): Boolean =
        (0 until candidate.interfaceCount).any { i ->
            val item = candidate.getInterface(i)
            item.interfaceClass == UsbConstants.USB_CLASS_AUDIO && item.interfaceSubclass == 2
        }

    private fun refreshDevice() {
        if (recording) return
        val available = usbManager.deviceList.values.filter(::hasAudioStreaming)
        device = available.firstOrNull()
        val selected = device
        addDiagnostic("USB scan: ${available.size} AudioStreaming device(s); selected=${selected?.deviceName}, " +
            "VID:PID=${selected?.let { "%04X:%04X".format(Locale.US, it.vendorId, it.productId) }}, " +
            "permission=${selected?.let(usbManager::hasPermission)}")
        info.text = if (selected == null) {
            "USB AudioStreaming device not detected. Connect the AB13X using USB OTG."
        } else {
            "USB device: " + selected.deviceName +
                "\nVID:PID %04X:%04X".format(Locale.US, selected.vendorId, selected.productId) +
                "\nPermission: " + usbManager.hasPermission(selected) +
                "\nDevices: " + available.size
        }
        recordButton.isEnabled = selected != null
    }

    private fun requestRecording() {
        if (recording || pendingDeviceName != null) return
        val selected = device ?: run {
            showMessage("No USB AudioStreaming input.")
            return
        }
        val fresh = usbManager.deviceList.values.firstOrNull {
            it.deviceName == selected.deviceName
        }
        if (fresh == null) {
            addDiagnostic("Start rejected: selected USB device no longer enumerated")
            refreshDevice()
            showMessage("USB device disconnected or re-enumerated. Refresh and retry.")
            return
        }
        device = fresh
        val authorized = usbManager.hasPermission(fresh)
        addDiagnostic("Start request: device=${fresh.deviceName}, authorized=$authorized")
        if (authorized) {
            beginRecording()
            return
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val intent = PendingIntent.getBroadcast(
            this, 0, Intent(permissionAction).setPackage(packageName), flags
        )
        pendingDeviceName = fresh.deviceName
        showMessage("Waiting for USB device authorization…")
        try {
            usbManager.requestPermission(fresh, intent)
        } catch (e: Exception) {
            pendingDeviceName = null
            addDiagnostic("requestPermission failed: ${e.javaClass.simpleName}: ${e.message}")
            showMessage("USB authorization request failed: ${e.message}")
        }
    }

    private fun filename() = "radio-usb-" +
        SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date()) + ".wav"

    /**
     * On Android Q+, MediaStore provides a music-library entry without any Save As dialog
     * and without WRITE_EXTERNAL_STORAGE. Pending is cleared only once capture stops.
     * Legacy versions use the app-specific Music directory, also without storage permission.
     * Caller owns the returned detached descriptor until it is handed to JNI.
     */
    private fun createWavOutput(): Int {
        val name = filename()
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/RadioUsbRecorder")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Unable to create MediaStore recording")
            wavUri = uri
            outputLabel = "Music/RadioUsbRecorder/" + name
            val descriptor = contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IllegalStateException("Unable to open MediaStore WAV output")
            val detached = descriptor.detachFd()
            descriptor.close()
            return detached
        }
        val directory = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir, "RadioUsbRecorder")
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IllegalStateException("Unable to create app-private recordings directory")
        }
        val target = File(directory, name)
        wavFile = target
        outputLabel = target.absolutePath
        val descriptor = ParcelFileDescriptor.open(
            target,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or
                ParcelFileDescriptor.MODE_TRUNCATE
        )
        val detached = descriptor.detachFd()
        descriptor.close()
        return detached
    }

    private fun beginRecording() {
        if (recording) return
        val previous = device ?: return
        val selected = usbManager.deviceList.values.firstOrNull { it.deviceName == previous.deviceName }
        if (selected == null) {
            addDiagnostic("Open rejected: device disappeared before openDevice")
            refreshDevice()
            showMessage("USB device disappeared; reconnect and retry.")
            return
        }
        device = selected
        if (!usbManager.hasPermission(selected)) {
            addDiagnostic("Open rejected: USB permission no longer valid for ${selected.deviceName}")
            showMessage("USB permission lost. Press Start to authorize the device again.")
            return
        }
        addDiagnostic("Opening: ${selected.deviceName}, permission=${usbManager.hasPermission(selected)}")
        showMessage("Opening USB microphone and preparing WAV…")
        var outputFd: Int? = null
        try {
            connection = usbManager.openDevice(selected)
                ?: throw IllegalStateException("UsbManager.openDevice() returned null")
            addDiagnostic("UsbManager.openDevice: OK, fd=${connection!!.fileDescriptor}")
            outputFd = createWavOutput()
            addDiagnostic("WAV output ready: $outputLabel")
            val ownedFd = outputFd ?: throw IllegalStateException("Output FD missing")
            // JNI consumes/owns wavFd even when it returns a startup error.
            outputFd = null
            val error = NativeRecorder.start(connection!!.fileDescriptor, ownedFd, 48_000)
            if (error != null) {
                addDiagnostic("JNI start FAILED: $error")
                throw IllegalStateException(error)
            }
            addDiagnostic("JNI start: streaming initialized")
            recording = true
            startedAt = SystemClock.elapsedRealtime()
            deviceButton.isEnabled = false
            recordButton.isEnabled = false
            stopButton.isEnabled = true
            shareButton.isEnabled = false
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            showMessage("RECORDING from AB13X/USB input\n" + outputLabel)
        } catch (e: Exception) {
            addDiagnostic("Start FAILED: ${e.javaClass.simpleName}: ${e.message}; " +
                "USB present=${usbManager.deviceList.containsKey(selected.deviceName)}, " +
                "permission=${usbManager.hasPermission(selected)}")
            outputFd?.let { ParcelFileDescriptor.adoptFd(it).close() }
            connection?.close()
            connection = null
            removeFailedOutput()
            showMessage("Start failed: " + (e.message ?: e.javaClass.simpleName))
            recordButton.isEnabled = device != null
            deviceButton.isEnabled = true
        }
    }

    private fun finishOutput() {
        val uri = wavUri
        if (Build.VERSION.SDK_INT >= 29 && uri != null) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }
            contentResolver.update(uri, values, null, null)
        }
        if (uri != null) lastRecording = uri
        wavUri = null
        wavFile = null
    }

    private fun removeFailedOutput() {
        try {
            wavUri?.let { contentResolver.delete(it, null, null) }
            wavFile?.delete()
        } catch (_: Exception) {
            // Never hide the actual USB startup error behind cleanup failures.
        } finally {
            wavUri = null
            wavFile = null
        }
    }

    private fun stopRecording(problem: String? = null) {
        if (!recording) return
        val fileLocation = outputLabel
        var stopError: String? = null
        try {
            addDiagnostic("Stop requested: ${problem ?: "normal"}")
            stopError = NativeRecorder.stop()
        } catch (e: Exception) {
            stopError = e.message ?: e.javaClass.simpleName
        } finally {
            recording = false
            connection?.close()
            connection = null
            deviceButton.isEnabled = true
            recordButton.isEnabled = device != null
            stopButton.isEnabled = false
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        try {
            addDiagnostic("Stop result: ${stopError ?: "WAV finalized"}; file=$fileLocation")
            finishOutput()
            shareButton.isEnabled = lastRecording != null
            val status = when {
                problem != null -> "Recording stopped: " + problem
                stopError != null -> "WAV finalization failed: " + stopError
                else -> "WAV saved successfully."
            }
            showMessage(status + "\nFile: " + fileLocation)
        } catch (e: Exception) {
            showMessage("Recording stopped, but file library finalization failed: " + e.message +
                "\nFile: " + fileLocation)
        }
    }

    private fun shareLastRecording() {
        val uri = lastRecording ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share recorded WAV"))
    }

    private fun showMessage(message: String) {
        info.text = message + "\n" + (device?.let {
            "VID:PID %04X:%04X".format(Locale.US, it.vendorId, it.productId)
        } ?: "No USB device")
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        stopRecording()
        connection?.close()
        unregisterReceiver(permissionReceiver)
        super.onDestroy()
    }
}
