package com.pocketide.data.ai

import android.app.DownloadManager
import java.io.File
import java.security.MessageDigest

internal data class DownloadRecord(
    val status: Int,
    val reason: Int,
    val downloadedBytes: Long,
) {
    val isActive: Boolean
        get() = status == DownloadManager.STATUS_PENDING ||
            status == DownloadManager.STATUS_RUNNING ||
            status == DownloadManager.STATUS_PAUSED
}

internal fun File.hasExpectedSize(asset: ModelCatalogAsset): Boolean =
    isFile && length() == asset.expectedBytes

internal fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered(HASH_BUFFER_BYTES).use { input ->
        val buffer = ByteArray(HASH_BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

internal fun downloadFailure(reason: Int): String = when (reason) {
    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Download failed: insufficient storage."
    DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Download failed: too many redirects. Tap Retry."
    DownloadManager.ERROR_UNHANDLED_HTTP_CODE ->
        "Download failed: Hugging Face returned an HTTP error."
    DownloadManager.ERROR_CANNOT_RESUME -> "Download could not resume. Tap Retry."
    DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Download storage is unavailable."
    else -> "Download failed (Android reason $reason). Tap Retry."
}

internal fun pausedReason(reason: Int): String = when (reason) {
    DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for a network connection"
    DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi"
    DownloadManager.PAUSED_WAITING_TO_RETRY -> "Waiting to retry"
    else -> "Download paused"
}

private const val HASH_BUFFER_BYTES = 1024 * 1024
