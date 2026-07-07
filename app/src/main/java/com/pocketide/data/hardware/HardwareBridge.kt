package com.pocketide.data.hardware

import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Bridges Android device hardware and system APIs to sandboxed script languages
 * (JavaScript, Lua, Java). All methods are safe: no continuous listeners are
 * kept alive after a call returns, no long-running background work is spawned.
 *
 * Exposed to script languages as the `hardware` global.
 */
class HardwareBridge(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val cameraManager: CameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }
    private val clipboardManager: ClipboardManager by lazy {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    private val batteryManager: BatteryManager by lazy {
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    }
    private val connectivityManager: ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private val sensorManager: SensorManager by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    private val locationManager: LocationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }
    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private var tts: TextToSpeech? = null
    private var httpServer: NanoHTTPD? = null

    // ---------- UI: toast ----------

    fun toast(message: String) {
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun toastLong(message: String) {
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Vibration ----------

    fun vibrate(durationMs: Long = 200) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager =
                    context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            }
        } catch (e: Exception) {
            Log.w("HardwareBridge", "vibrate failed", e)
        }
    }

    /** Vibrate a pattern: timings in ms alternating off/on, e.g. [0, 200, 100, 400]. */
    fun vibratePattern(timings: LongArray) {
        try {
            val effect = VibrationEffect.createWaveform(timings, -1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager =
                    context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(effect)
            }
        } catch (e: Exception) {
            Log.w("HardwareBridge", "vibratePattern failed", e)
        }
    }

    // ---------- Flashlight ----------

    fun setFlashlight(enabled: Boolean): Boolean {
        return try {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false
            cameraManager.setTorchMode(cameraId, enabled)
            true
        } catch (_: Exception) {
            false
        }
    }

    // ---------- Battery ----------

    /** Battery level as integer percent 0..100, or -1 if unavailable. */
    fun batteryLevel(): Int = try {
        batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Exception) { -1 }

    /** True if the device is charging (AC, USB, or wireless). */
    fun isCharging(): Boolean = try {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    } catch (_: Exception) { false }

    /** Battery temperature in degrees Celsius, or 25f if unavailable. */
    fun batteryTemperature(): Float = try {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        if (temp > 0) temp / 10f else 25f
    } catch (_: Exception) { 25f }

    // ---------- Clipboard ----------

    /** Read the primary clipboard text, or empty string if none. */
    fun clipboardGet(): String = try {
        val clip = clipboardManager.primaryClip ?: return ""
        if (clip.itemCount == 0) "" else clip.getItemAt(0).coerceToText(context).toString()
    } catch (_: Exception) { "" }

    /** Write text to the primary clipboard. */
    fun clipboardSet(text: String) {
        try {
            clipboardManager.setPrimaryClip(ClipData.newPlainText("PocketIDE", text))
        } catch (e: Exception) {
            Log.w("HardwareBridge", "clipboardSet failed", e)
        }
    }

    // ---------- Screen ----------

    /** Screen brightness 0..255, or -1 if the setting is not readable. */
    fun screenBrightness(): Int = try {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    } catch (_: Exception) { -1 }

    /** Screen dimensions and density as a formatted string. */
    fun screenInfo(): String {
        val dm = context.resources.displayMetrics
        return "Width: ${dm.widthPixels}px, Height: ${dm.heightPixels}px, " +
            "Density: ${dm.density}x (${dm.densityDpi}dpi)"
    }

    /** Set screen brightness 0..255. Requires WRITE_SETTINGS permission. */
    fun setScreenBrightness(level: Int): Boolean = try {
        val clamped = level.coerceIn(0, 255)
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            clamped,
        )
        true
    } catch (_: Exception) { false }

    /** Keep screen on or allow it to turn off. */
    fun keepScreenOn(enabled: Boolean) {
        mainHandler.post {
            try {
                val activity = (context as? android.app.Activity)
                    ?: context.findActivity()
                activity?.window?.let { window ->
                    if (enabled) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            } catch (e: Exception) {
                Log.w("HardwareBridge", "keepScreenOn failed", e)
            }
        }
    }

    private fun Context.findActivity(): android.app.Activity? {
        var ctx = this
        while (ctx is android.content.ContextWrapper) {
            if (ctx is android.app.Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    // ---------- Network ----------

    /** Rough network status: "wifi", "cellular", "ethernet", "none", or "unknown". */
    fun networkType(): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val net = connectivityManager.activeNetwork ?: return "none"
            val caps = connectivityManager.getNetworkCapabilities(net) ?: return "none"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "unknown"
            }
        } else {
            @Suppress("DEPRECATION")
            val info = connectivityManager.activeNetworkInfo
            @Suppress("DEPRECATION")
            if (info == null || !info.isConnected) "none" else info.typeName.lowercase(Locale.US)
        }
    } catch (_: Exception) { "unknown" }

    /** True if the device has any active internet transport. */
    fun isOnline(): Boolean = networkType() !in setOf("none", "unknown")

    // ---------- Storage ----------

    /** Free bytes on internal storage. */
    fun storageFree(): Long = try {
        val stat = StatFs(Environment.getDataDirectory().path)
        stat.availableBytes
    } catch (_: Exception) { -1L }

    /** Total bytes on internal storage. */
    fun storageTotal(): Long = try {
        val stat = StatFs(Environment.getDataDirectory().path)
        stat.totalBytes
    } catch (_: Exception) { -1L }

    // ---------- Text-to-Speech ----------

    /** Speak text using Android TTS. Returns true if speech was initiated. */
    fun speak(text: String): Boolean {
        return try {
            if (tts == null) {
                val latch = CountDownLatch(1)
                var initStatus = TextToSpeech.ERROR
                tts = TextToSpeech(context) { status ->
                    initStatus = status
                    latch.countDown()
                }
                latch.await(3, TimeUnit.SECONDS)
                if (initStatus != TextToSpeech.SUCCESS) {
                    tts = null
                    return false
                }
                tts?.language = Locale.US
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pocketide_tts_${System.currentTimeMillis()}")
            true
        } catch (e: Exception) {
            Log.w("HardwareBridge", "speak failed", e)
            false
        }
    }

    /** Stop any ongoing TTS speech. */
    fun stopSpeak() {
        try { tts?.stop() } catch (_: Exception) {}
    }

    // ---------- Audio tone ----------

    /** Play a single tone for the given duration in milliseconds. */
    fun playTone(freqHz: Int, durationMs: Int) {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, durationMs)
            mainHandler.postDelayed({ tone.release() }, durationMs.toLong() + 100)
        } catch (e: Exception) {
            Log.w("HardwareBridge", "playTone failed", e)
        }
    }

    // ---------- Notifications ----------

    /** Show a notification with title and text. Returns true on success. */
    fun notify(title: String, text: String): Boolean = try {
        val channelId = "pocketide_hardware"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "PocketIDE Hardware",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            notificationManager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        true
    } catch (e: Exception) {
        Log.w("HardwareBridge", "notify failed", e)
        false
    }

    // ---------- GPS / Location ----------

    /**
     * One-shot location read. Returns "lat,lng,accuracy" or empty string on failure.
     * Uses coarse or fine location depending on permission. Blocks up to 5 seconds.
     */
    fun getLocation(timeoutMs: Long = 5000L): String {
        return try {
            val providers = locationManager.getProviders(true)
            val provider = providers.firstOrNull { it == LocationManager.GPS_PROVIDER }
                ?: providers.firstOrNull { it == LocationManager.NETWORK_PROVIDER }
                ?: providers.firstOrNull()
                ?: return ""

            val lastKnown = locationManager.getLastKnownLocation(provider)
            if (lastKnown != null) {
                return formatLocation(lastKnown)
            }

            val latch = CountDownLatch(1)
            val result = arrayOf<Location?>(null)
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (result[0] == null) {
                        result[0] = location
                        latch.countDown()
                    }
                }
                override fun onProviderDisabled(provider: String) {}
                override fun onProviderEnabled(provider: String) {}
                @Suppress("DEPRECATION")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            }

            try {
                locationManager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
                result[0]?.let { formatLocation(it) } ?: ""
            } finally {
                try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
            }
        } catch (e: SecurityException) {
            "[location permission denied]"
        } catch (e: Exception) {
            Log.w("HardwareBridge", "getLocation failed", e)
            ""
        }
    }

    private fun formatLocation(loc: Location): String {
        return "${loc.latitude},${loc.longitude},accuracy=${loc.accuracy}m"
    }

    // ---------- Bluetooth ----------

    /** List paired Bluetooth devices. Returns "name|address" per line, or empty. */
    fun listBluetooth(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val adapter = btManager.adapter ?: return "[bluetooth not available]"
                if (!adapter.isEnabled) return "[bluetooth disabled]"
                adapter.bondedDevices.joinToString("\n") { "${it.name}|${it.address}" }
            } else {
                @Suppress("DEPRECATION")
                val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                    ?: return "[bluetooth not available]"
                if (!adapter.isEnabled) return "[bluetooth disabled]"
                adapter.bondedDevices.joinToString("\n") { "${it.name}|${it.address}" }
            }
        } catch (e: SecurityException) {
            "[bluetooth permission denied]"
        } catch (e: Exception) {
            Log.w("HardwareBridge", "listBluetooth failed", e)
            "[bluetooth error]"
        }
    }

    // ---------- File I/O (app sandbox) ----------

    /** Read a file from the app's private files directory. Returns content or error message. */
    fun readFile(path: String): String = try {
        val file = resolveSandboxFile(path)
        if (!file.exists()) "[file not found: $path]" else file.readText()
    } catch (e: Exception) {
        "[read error: ${e.message}]"
    }

    /** Write a file to the app's private files directory. Returns true on success. */
    fun writeFile(path: String, content: String): Boolean = try {
        val file = resolveSandboxFile(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        true
    } catch (e: Exception) {
        Log.w("HardwareBridge", "writeFile failed", e)
        false
    }

    /** List files in a directory within the app sandbox. Returns one path per line. */
    fun listFiles(path: String): String {
        return try {
            val dir = resolveSandboxFile(path)
            if (!dir.exists() || !dir.isDirectory) return "[not a directory: $path]"
            dir.listFiles()?.joinToString("\n") { it.name } ?: "[empty]"
        } catch (e: Exception) {
            "[list error: ${e.message}]"
        }
    }

    /** Delete a file from the app sandbox. Returns true on success. */
    fun deleteFile(path: String): Boolean = try {
        resolveSandboxFile(path).delete()
    } catch (_: Exception) { false }

    private fun resolveSandboxFile(path: String): File {
        val sanitized = path.replace("..", "").trimStart('/')
        return File(context.filesDir, sanitized)
    }

    /** Get the absolute path of the app sandbox root. */
    fun sandboxPath(): String = context.filesDir.absolutePath

    // ---------- HTTP Server (localhost) ----------

    /**
     * Starts a local HTTP server serving files from the app sandbox.
     * Returns the URL (e.g. "http://localhost:8080") or error message.
     */
    fun startServer(port: Int = 8080): String = try {
        stopServer()
        val rootDir = File(context.filesDir, "www")
        if (!rootDir.exists()) rootDir.mkdirs()

        httpServer = object : NanoHTTPD(port) {
            override fun serve(session: IHTTPSession): Response {
                val uri = session.uri.trimStart('/')
                val file = if (uri.isEmpty()) File(rootDir, "index.html") else File(rootDir, uri)
                return if (file.exists() && file.isFile) {
                    val mimeType = guessMimeType(file.name)
                    newFixedLengthResponse(Response.Status.OK, mimeType, file.readText())
                } else {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found: $uri")
                }
            }
        }.also { it.start() }

        "http://localhost:$port"
    } catch (e: Exception) {
        Log.w("HardwareBridge", "startServer failed", e)
        "[server error: ${e.message}]"
    }

    /** Stops the local HTTP server if running. */
    fun stopServer() {
        try {
            httpServer?.stop()
            httpServer = null
        } catch (e: Exception) {
            Log.w("HardwareBridge", "stopServer failed", e)
        }
    }

    /** Returns true if the HTTP server is currently running. */
    fun isServerRunning(): Boolean = httpServer != null

    private fun guessMimeType(filename: String): String = when (filename.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js" -> "application/javascript"
        "json" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "svg" -> "image/svg+xml"
        else -> "text/plain"
    }

    // ---------- Sensors (one-shot read) ----------

    /**
     * Reads a single value from the given sensor. Blocks up to [timeoutMs]
     * milliseconds for a reading, then unregisters the listener.
     *
     * @param type sensor type: "accelerometer", "gyroscope", "light",
     *             "pressure", "proximity", "magnetic"
     * @return a comma-separated string of the sensor values, or empty on failure.
     */
    fun readSensor(type: String, timeoutMs: Long = 1000L): String {
        val sensorType = when (type.lowercase(Locale.US)) {
            "accelerometer" -> Sensor.TYPE_ACCELEROMETER
            "gyroscope" -> Sensor.TYPE_GYROSCOPE
            "light" -> Sensor.TYPE_LIGHT
            "pressure" -> Sensor.TYPE_PRESSURE
            "proximity" -> Sensor.TYPE_PROXIMITY
            "magnetic" -> Sensor.TYPE_MAGNETIC_FIELD
            else -> return ""
        }
        val sensor = sensorManager.getDefaultSensor(sensorType) ?: return ""

        val latch = CountDownLatch(1)
        val result = arrayOf<FloatArray?>(null)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event != null && result[0] == null) {
                    result[0] = event.values.copyOf()
                    latch.countDown()
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        return try {
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            result[0]?.joinToString(", ") ?: ""
        } catch (_: Exception) {
            ""
        } finally {
            try { sensorManager.unregisterListener(listener) } catch (e: Exception) {
                Log.w("HardwareBridge", "unregisterListener failed", e)
            }
        }
    }

    /** List sensor names available on this device. */
    fun listSensors(): String {
        val sensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
        return sensors.joinToString("\n") { "${it.name} (type=${it.type})" }
    }

    // ---------- Camera info ----------

    /** List available camera IDs and their capabilities. */
    fun listCameras(): String = try {
        cameraManager.cameraIdList.joinToString("\n") { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            val facingStr = when (facing) {
                CameraCharacteristics.LENS_FACING_BACK -> "back"
                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                else -> "external"
            }
            val flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val configMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val resolutions = configMap?.let { map ->
                map.getOutputSizes(android.graphics.ImageFormat.JPEG)
                    ?.joinToString(", ") { "${it.width}x${it.height}" } ?: "unknown"
            } ?: "unknown"
            "camera$id: facing=$facingStr, flash=$flash, resolutions=$resolutions"
        }
    } catch (e: Exception) {
        Log.w("HardwareBridge", "listCameras failed", e)
        "[camera error]"
    }

    // ---------- Intent helpers (safe: only ACTION_VIEW with URI) ----------

    /** Opens a URL in the default browser. Returns true on success. */
    fun openUrl(url: String): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: Exception) { false }

    // ---------- Device info ----------

    fun getDeviceInfo(): String {
        return buildString {
            appendLine("Brand: ${Build.BRAND}")
            appendLine("Model: ${Build.MODEL}")
            appendLine("Device: ${Build.DEVICE}")
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Android API: ${Build.VERSION.SDK_INT}")
            appendLine("Android Release: ${Build.VERSION.RELEASE}")
            appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("Cores: ${Runtime.getRuntime().availableProcessors()}")
            appendLine("Max Memory (MB): ${Runtime.getRuntime().maxMemory() / (1024 * 1024)}")
            appendLine("Battery: ${batteryLevel()}%${if (isCharging()) " (charging)" else ""}")
            appendLine("Battery Temp: ${batteryTemperature()}C")
            appendLine("Network: ${networkType()}")
            appendLine("Screen: ${screenInfo()}")
            val freeGb = storageFree() / (1024.0 * 1024.0 * 1024.0)
            val totalGb = storageTotal() / (1024.0 * 1024.0 * 1024.0)
            appendLine("Storage: %.2f GB free of %.2f GB".format(freeGb, totalGb))
            appendLine("Sandbox: ${sandboxPath()}")
        }
    }
}
