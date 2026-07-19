package com.pocketide.data.ai

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

enum class ProcessExitCategory(val displayName: String) {
    LOW_MEMORY("Low memory kill"),
    NATIVE_CRASH("Native model crash"),
    JAVA_CRASH("Application crash"),
    ANR("Application not responding"),
    SIGNAL("Signal termination"),
    INITIALIZATION("Initialization failure"),
    EXCESSIVE_RESOURCE_USE("Excessive resource use"),
}

data class PreviousProcessExit(
    val category: ProcessExitCategory,
    val timestampMs: Long,
    val status: Int,
    val description: String?,
    val pssBytes: Long,
    val rssBytes: Long,
) {
    fun userMessage(): String = when (category) {
        ProcessExitCategory.LOW_MEMORY ->
            "The previous AI run ended because Android ran low on memory. " +
                "PocketIDE will use a smaller context before loading the model."
        ProcessExitCategory.NATIVE_CRASH ->
            "The previous AI run ended inside the native model runtime. " +
                "The stability report contains the recorded memory and exit details."
        ProcessExitCategory.JAVA_CRASH ->
            "The previous PocketIDE run ended with an application crash. " +
                "The stability report contains the recorded exit details."
        ProcessExitCategory.ANR ->
            "The previous PocketIDE run stopped responding. Generation now runs with a visible stop control."
        ProcessExitCategory.SIGNAL ->
            "Android terminated the previous PocketIDE process with signal $status. " +
                "This can happen during severe memory pressure."
        ProcessExitCategory.INITIALIZATION ->
            "The previous PocketIDE process failed during initialization."
        ProcessExitCategory.EXCESSIVE_RESOURCE_USE ->
            "Android ended the previous PocketIDE process for excessive resource use."
    }
}

/** Reads and consumes the newest abnormal exit record once per app process history entry. */
class ProcessExitDiagnostics(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun consumeLatestAbnormal(): PreviousProcessExit? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val latest = runCatching {
            manager.getHistoricalProcessExitReasons(appContext.packageName, 0, 1).firstOrNull()
        }.getOrNull() ?: return null
        val lastSeen = preferences.getLong(KEY_LAST_SEEN_TIMESTAMP, 0L)
        if (latest.timestamp <= lastSeen) return null
        preferences.edit().putLong(KEY_LAST_SEEN_TIMESTAMP, latest.timestamp).apply()
        return mapRecord(
            reason = latest.reason,
            timestampMs = latest.timestamp,
            status = latest.status,
            description = latest.description,
            pssKb = latest.pss,
            rssKb = latest.rss,
        )
    }

    companion object {
        internal fun mapRecord(
            reason: Int,
            timestampMs: Long,
            status: Int,
            description: String?,
            pssKb: Long,
            rssKb: Long,
        ): PreviousProcessExit? {
            val category = when (reason) {
                ApplicationExitInfo.REASON_LOW_MEMORY -> ProcessExitCategory.LOW_MEMORY
                ApplicationExitInfo.REASON_CRASH_NATIVE -> ProcessExitCategory.NATIVE_CRASH
                ApplicationExitInfo.REASON_CRASH -> ProcessExitCategory.JAVA_CRASH
                ApplicationExitInfo.REASON_ANR -> ProcessExitCategory.ANR
                ApplicationExitInfo.REASON_SIGNALED -> ProcessExitCategory.SIGNAL
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> ProcessExitCategory.INITIALIZATION
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ->
                    ProcessExitCategory.EXCESSIVE_RESOURCE_USE
                else -> return null
            }
            return PreviousProcessExit(
                category = category,
                timestampMs = timestampMs,
                status = status,
                description = description?.takeIf { it.isNotBlank() },
                pssBytes = pssKb.coerceAtLeast(0L) * 1024L,
                rssBytes = rssKb.coerceAtLeast(0L) * 1024L,
            )
        }

        private const val PREFERENCES = "pocketide_process_exit_diagnostics"
        private const val KEY_LAST_SEEN_TIMESTAMP = "last_seen_timestamp"
    }
}
