package com.pocketide.data.hardware

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
import android.widget.Toast
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
        } catch (_: Exception) {}
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
        } catch (_: Exception) {}
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
            try { sensorManager.unregisterListener(listener) } catch (_: Exception) {}
        }
    }

    /** List sensor names available on this device. */
    fun listSensors(): String {
        val sensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
        return sensors.joinToString("\n") { "${it.name} (type=${it.type})" }
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
            appendLine("Network: ${networkType()}")
            appendLine("Screen: ${screenInfo()}")
            val freeGb = storageFree() / (1024.0 * 1024.0 * 1024.0)
            val totalGb = storageTotal() / (1024.0 * 1024.0 * 1024.0)
            appendLine("Storage: %.2f GB free of %.2f GB".format(freeGb, totalGb))
        }
    }
}
