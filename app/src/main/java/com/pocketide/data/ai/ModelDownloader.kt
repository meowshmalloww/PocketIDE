package com.pocketide.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads model files from a remote URL (e.g. HuggingFace) to app-internal storage.
 *
 * Files are saved to [Context.filesDir]/models/. The download runs on [Dispatchers.IO]
 * and reports progress as a 0..100 integer via [onProgress].
 *
 * HuggingFace direct-download URLs should use the /resolve/ path, e.g.:
 *   https://huggingface.co/<user>/<repo>/resolve/main/<file>.gguf
 */
class ModelDownloader(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    sealed class DownloadResult {
        data class Success(val savedPath: String) : DownloadResult()
        data class Error(val message: String) : DownloadResult()
    }

    /**
     * Downloads [downloadUrl] to filesDir/models/[fileName].
     *
     * If the file already exists and [overwrite] is false, returns the existing path immediately.
     * Calls [onProgress] with a percentage 0..100 as bytes are received.
     */
    suspend fun download(
        downloadUrl: String,
        fileName: String,
        overwrite: Boolean = false,
        onProgress: (Int) -> Unit = {},
    ): DownloadResult = withContext(dispatcher) {
        val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
        val targetFile = File(modelsDir, fileName)

        if (targetFile.exists() && !overwrite) {
            return@withContext DownloadResult.Success(targetFile.absolutePath)
        }

        try {
            val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
            }

            connection.inputStream.use { input ->
                val totalBytes = connection.contentLengthLong
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (true) {
                        bytesRead = input.read(buffer)
                        if (bytesRead == -1) break

                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        if (totalBytes > 0) {
                            val percent = ((totalRead * 100) / totalBytes).toInt()
                                .coerceIn(0, 100)
                            onProgress(percent)
                        }
                    }
                }
            }

            connection.disconnect()

            if (targetFile.length() == 0L) {
                targetFile.delete()
                return@withContext DownloadResult.Error("Downloaded file is empty")
            }

            Log.i(TAG, "Downloaded $fileName (${targetFile.length()} bytes) to ${targetFile.absolutePath}")
            DownloadResult.Success(targetFile.absolutePath)
        } catch (e: IOException) {
            targetFile.delete()
            Log.e(TAG, "Download failed for $downloadUrl", e)
            DownloadResult.Error(e.message ?: e.javaClass.simpleName)
        } catch (e: SecurityException) {
            targetFile.delete()
            Log.e(TAG, "Security exception during download", e)
            DownloadResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Returns the local path where a model with [fileName] would be stored,
     * or null if it doesn't exist yet.
     */
    fun getLocalPath(fileName: String): String? {
        val file = File(context.filesDir, "models/$fileName")
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

    companion object {
        private const val TAG = "ModelDownloader"
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 300_000
        private const val BUFFER_SIZE = 8 * 1024
        private const val USER_AGENT = "PocketIDE/1.0 (Android)"
    }
}
