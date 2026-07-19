package com.pocketide.data.ai

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToLong

/** One measured decode result used to compare llama.cpp thread counts. */
data class ThreadProfileSample(
    val threadCount: Int,
    val tokensPerSecond: Float,
    val ttftMs: Long,
    val peakProcessPssBytes: Long,
)

/** The real, model-and-device-specific result of a thread calibration run. */
data class ThreadCalibration(
    val threadCount: Int,
    val medianTokensPerSecond: Float,
    val averageTtftMs: Long,
    val averagePeakProcessPssBytes: Long,
    val comparisonThreadCount: Int? = null,
    val comparisonMedianTokensPerSecond: Float? = null,
    val measuredAtMs: Long = System.currentTimeMillis(),
)

/**
 * Selects a sustainable decode profile from real measurements.
 *
 * Results within 1% of the fastest median are treated as measurement noise. In that
 * band, fewer threads win before sampled process memory. PSS snapshots are useful evidence but
 * are too sensitive to page-cache reclamation and run order to break a near-tie reliably.
 */
object ThreadProfileSelector {
    private const val NEAR_FASTEST_RATIO = 0.99f

    fun select(samples: List<ThreadProfileSample>): ThreadCalibration? {
        val profiles = samples
            .filter { it.threadCount > 0 && it.tokensPerSecond > 0f }
            .groupBy { it.threadCount }
            .mapNotNull { (threads, runs) ->
                if (runs.isEmpty()) return@mapNotNull null
                val positivePss = runs.map { it.peakProcessPssBytes }.filter { it > 0L }
                Profile(
                    threads = threads,
                    medianTps = median(runs.map { it.tokensPerSecond }),
                    averageTtftMs = runs.map { it.ttftMs }.filter { it >= 0L }
                        .takeIf { it.isNotEmpty() }?.average()?.roundToLong() ?: -1L,
                    averagePssBytes = positivePss.takeIf { it.isNotEmpty() }
                        ?.average()?.roundToLong() ?: Long.MAX_VALUE,
                )
            }
        val fastest = profiles.maxOfOrNull { it.medianTps } ?: return null
        val selected = profiles
            .filter { it.medianTps >= fastest * NEAR_FASTEST_RATIO }
            .minWithOrNull(compareBy<Profile> { it.threads }.thenBy { it.averagePssBytes })
            ?: return null
        return ThreadCalibration(
            threadCount = selected.threads,
            medianTokensPerSecond = selected.medianTps,
            averageTtftMs = selected.averageTtftMs,
            averagePeakProcessPssBytes = selected.averagePssBytes.takeUnless { it == Long.MAX_VALUE } ?: 0L,
        )
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2f
        } else {
            sorted[middle]
        }
    }

    private data class Profile(
        val threads: Int,
        val medianTps: Float,
        val averageTtftMs: Long,
        val averagePssBytes: Long,
    )
}

/** Persists a measured thread profile for one exact device and model file. */
class ThreadCalibrationRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(modelPath: String): ThreadCalibration? {
        val raw = prefs.getString(key(modelPath), null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            ThreadCalibration(
                threadCount = json.getInt("threads"),
                medianTokensPerSecond = json.getDouble("median_tps").toFloat(),
                averageTtftMs = json.getLong("average_ttft_ms"),
                averagePeakProcessPssBytes = json.getLong("average_pss_bytes"),
                comparisonThreadCount = json.optInt("comparison_threads", -1).takeIf { it > 0 },
                comparisonMedianTokensPerSecond = json.optDouble("comparison_median_tps", Double.NaN)
                    .takeIf { it.isFinite() && it > 0.0 }?.toFloat(),
                measuredAtMs = json.getLong("measured_at_ms"),
            )
        }.getOrNull()?.takeIf {
            it.threadCount in 1..Runtime.getRuntime().availableProcessors()
        }
    }

    fun save(modelPath: String, calibration: ThreadCalibration) {
        val json = JSONObject()
            .put("threads", calibration.threadCount)
            .put("median_tps", calibration.medianTokensPerSecond.toDouble())
            .put("average_ttft_ms", calibration.averageTtftMs)
            .put("average_pss_bytes", calibration.averagePeakProcessPssBytes)
            .put("comparison_threads", calibration.comparisonThreadCount ?: JSONObject.NULL)
            .put(
                "comparison_median_tps",
                calibration.comparisonMedianTokensPerSecond?.toDouble() ?: JSONObject.NULL,
            )
            .put("measured_at_ms", calibration.measuredAtMs)
        prefs.edit { putString(key(modelPath), json.toString()) }
    }

    fun clear(modelPath: String) {
        prefs.edit { remove(key(modelPath)) }
    }

    private fun key(modelPath: String): String {
        val model = File(modelPath)
        val identity = buildString {
            append(CALIBRATION_KEY_SCHEMA).append('|')
            append(Build.MANUFACTURER).append('|')
            append(Build.MODEL).append('|')
            append(Build.DEVICE).append('|')
            append(Build.VERSION.SDK_INT).append('|')
            append(Runtime.getRuntime().availableProcessors()).append('|')
            append(BackendInfo.llamaCppNativeLibrary).append('|')
            append(LLAMA_CPP_WRAPPER_VERSION).append('|')
            append(model.absolutePath).append('|')
            append(model.length()).append('|')
            append(model.lastModified())
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val PREFS_NAME = "pocketide_thread_calibration"
        // Schema 4 invalidates profiles produced by the old per-generation thread sweep.
        private const val CALIBRATION_KEY_SCHEMA = 4
        private const val LLAMA_CPP_WRAPPER_VERSION = "0.4.0"
    }
}
