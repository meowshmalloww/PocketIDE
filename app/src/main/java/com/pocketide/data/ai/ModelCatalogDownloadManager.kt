package com.pocketide.data.ai

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Persistent, checksum-verified downloads for PocketIDE's built-in model catalog. */
class ModelCatalogDownloadManager(context: Context) {
    private val appContext = context.applicationContext
    private val downloadManager = requireNotNull(
        appContext.getSystemService(DownloadManager::class.java),
    ) { "Android Download Manager is unavailable" }
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val configRepository = AiConfigRepository(appContext)

    fun modelsDirectory(): File? = appContext
        .getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?.let { File(it, MODELS_DIRECTORY).apply { mkdirs() } }

    suspend fun state(entry: ModelCatalogEntry): CatalogDownloadState = withContext(Dispatchers.IO) {
        operationMutex.withLock { stateLocked(entry) }
    }

    suspend fun start(entry: ModelCatalogEntry): Result<Unit> = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            runCatching {
                val directory = requireModelsDirectory()
                val pending = mutableListOf<ModelCatalogAsset>()
                var requiredBytes = 0L

                entry.assets.forEach { asset ->
                    val target = File(directory, asset.localFileName)
                    val record = downloadId(entry, asset)?.let(::query)
                    when {
                        record?.isActive == true -> {
                            requiredBytes += (asset.expectedBytes - record.downloadedBytes).coerceAtLeast(0L)
                        }
                        record?.status == DownloadManager.STATUS_SUCCESSFUL && target.hasExpectedSize(asset) -> Unit
                        record == null && target.hasExpectedSize(asset) -> Unit
                        else -> {
                            downloadId(entry, asset)?.let { downloadManager.remove(it) }
                            clearDownloadId(entry, asset)
                            target.delete()
                            pending += asset
                            requiredBytes += asset.expectedBytes
                        }
                    }
                }

                if (pending.isNotEmpty()) {
                    ensureFreeSpace(directory, requiredBytes)
                    val newlyEnqueued = mutableListOf<Long>()
                    try {
                        pending.forEach { asset ->
                            val request = DownloadManager.Request(asset.downloadUrl.toUri())
                                .setTitle(asset.remoteFileName)
                                .setDescription("PocketIDE model download")
                                .setMimeType("application/octet-stream")
                                .setAllowedOverMetered(true)
                                .setAllowedOverRoaming(false)
                                .setNotificationVisibility(
                                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                                )
                                .setDestinationInExternalFilesDir(
                                    appContext,
                                    Environment.DIRECTORY_DOWNLOADS,
                                    "$MODELS_DIRECTORY/${asset.localFileName}",
                                )
                            val id = downloadManager.enqueue(request)
                            check(id >= 0L) { "Android Download Manager rejected ${asset.remoteFileName}" }
                            newlyEnqueued += id
                            saveDownloadId(entry, asset, id)
                        }
                    } catch (error: Throwable) {
                        if (newlyEnqueued.isNotEmpty()) {
                            downloadManager.remove(*newlyEnqueued.toLongArray())
                        }
                        pending.forEach { clearDownloadId(entry, it) }
                        throw error
                    }
                }
                prefs.edit {
                    putBoolean(verifiedKey(entry), false)
                    remove(errorKey(entry))
                }
            }.onFailure { saveError(entry, it.message ?: it.javaClass.simpleName) }
        }
    }

    suspend fun finalizeIfComplete(entry: ModelCatalogEntry): CatalogDownloadState =
        withContext(Dispatchers.IO) {
            operationMutex.withLock {
                val directory = runCatching { requireModelsDirectory() }.getOrElse {
                    return@withLock failedState(entry, it.message ?: "Model storage is unavailable")
                }
                if (!entry.assets.all { File(directory, it.localFileName).hasExpectedSize(it) }) {
                    return@withLock stateLocked(entry)
                }

                entry.assets.forEach { asset ->
                    val file = File(directory, asset.localFileName)
                    val actual = runCatching { file.sha256() }.getOrElse { error ->
                        return@withLock failedState(
                            entry,
                            "Could not verify ${asset.remoteFileName}: ${error.message ?: "read error"}",
                        )
                    }
                    if (!actual.equals(asset.sha256, ignoreCase = true)) {
                        downloadId(entry, asset)?.let { downloadManager.remove(it) }
                        clearDownloadId(entry, asset)
                        file.delete()
                        return@withLock failedState(
                            entry,
                            "Integrity check failed for ${asset.remoteFileName}. Tap Retry to download it again.",
                        )
                    }
                }

                prefs.edit {
                    putBoolean(verifiedKey(entry), true)
                    remove(errorKey(entry))
                }
                configRepository.upsertAndActivate(entry.installedModel(directory), Quantization.INT4)
                installedState(entry)
            }
        }

    suspend fun cancel(entry: ModelCatalogEntry) = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val directory = modelsDirectory()
            entry.assets.forEach { asset ->
                val id = downloadId(entry, asset) ?: return@forEach
                val record = query(id)
                if (record == null || record.status != DownloadManager.STATUS_SUCCESSFUL) {
                    downloadManager.remove(id)
                    clearDownloadId(entry, asset)
                    directory?.let { File(it, asset.localFileName) }
                        ?.takeUnless { it.hasExpectedSize(asset) }
                        ?.delete()
                }
            }
            prefs.edit {
                putBoolean(verifiedKey(entry), false)
                remove(errorKey(entry))
            }
        }
    }

    suspend fun reconcileAll() {
        ModelCatalog.entries.forEach { entry ->
            if (state(entry).phase == CatalogDownloadPhase.VERIFYING) {
                finalizeIfComplete(entry)
            }
        }
    }

    private fun stateLocked(entry: ModelCatalogEntry): CatalogDownloadState {
        val directory = modelsDirectory()
            ?: return failedState(entry, "App-specific model storage is unavailable")
        val allSized = entry.assets.all { File(directory, it.localFileName).hasExpectedSize(it) }
        if (prefs.getBoolean(verifiedKey(entry), false) && allSized) {
            return installedState(entry)
        }
        if (!allSized) prefs.edit { putBoolean(verifiedKey(entry), false) }

        val records = entry.assets.associateWith { asset -> downloadId(entry, asset)?.let(::query) }
        records.entries.firstOrNull { it.value?.status == DownloadManager.STATUS_FAILED }?.let { failed ->
            return failedState(entry, downloadFailure(failed.value?.reason ?: DownloadManager.ERROR_UNKNOWN))
        }

        val downloaded = entry.assets.sumOf { asset ->
            val record = records[asset]
            when {
                File(directory, asset.localFileName).hasExpectedSize(asset) -> asset.expectedBytes
                record != null -> record.downloadedBytes.coerceIn(0L, asset.expectedBytes)
                else -> 0L
            }
        }
        val active = records.values.filterNotNull().filter { it.isActive }
        if (active.isNotEmpty()) {
            val paused = active.firstOrNull { it.status == DownloadManager.STATUS_PAUSED }
            return CatalogDownloadState(
                phase = when {
                    paused != null -> CatalogDownloadPhase.PAUSED
                    active.all { it.status == DownloadManager.STATUS_PENDING } -> CatalogDownloadPhase.QUEUED
                    else -> CatalogDownloadPhase.DOWNLOADING
                },
                downloadedBytes = downloaded,
                totalBytes = entry.totalBytes,
                detail = paused?.let { pausedReason(it.reason) },
            )
        }
        if (allSized) {
            return CatalogDownloadState(
                CatalogDownloadPhase.VERIFYING,
                downloadedBytes = entry.totalBytes,
                totalBytes = entry.totalBytes,
            )
        }

        return CatalogDownloadState(
            phase = CatalogDownloadPhase.AVAILABLE,
            downloadedBytes = downloaded,
            totalBytes = entry.totalBytes,
            detail = prefs.getString(errorKey(entry), null)
                ?: downloaded.takeIf { it > 0L }?.let { "Partial bundle retained; tap Download to continue." },
        )
    }

    private fun installedState(entry: ModelCatalogEntry) = CatalogDownloadState(
        CatalogDownloadPhase.INSTALLED,
        downloadedBytes = entry.totalBytes,
        totalBytes = entry.totalBytes,
        detail = "Verified and selected automatically",
    )

    private fun failedState(entry: ModelCatalogEntry, message: String): CatalogDownloadState {
        saveError(entry, message)
        return CatalogDownloadState(
            CatalogDownloadPhase.FAILED,
            totalBytes = entry.totalBytes,
            detail = message,
        )
    }

    private fun requireModelsDirectory(): File = modelsDirectory()
        ?: error("App-specific external storage is unavailable")

    private fun ensureFreeSpace(directory: File, downloadBytes: Long) {
        val required = downloadBytes + FREE_SPACE_RESERVE_BYTES
        val available = StatFs(directory.absolutePath).availableBytes
        check(available >= required) {
            "Not enough storage. Need ${formatBytes(required)}, available ${formatBytes(available)}."
        }
    }

    private fun query(id: Long): DownloadRecord? = runCatching {
        downloadManager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            DownloadRecord(
                status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                downloadedBytes = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                ).coerceAtLeast(0L),
            )
        }
    }.getOrNull()

    private fun downloadId(entry: ModelCatalogEntry, asset: ModelCatalogAsset): Long? =
        prefs.getLong(idKey(entry, asset), NO_DOWNLOAD_ID).takeIf { it != NO_DOWNLOAD_ID }

    private fun saveDownloadId(entry: ModelCatalogEntry, asset: ModelCatalogAsset, id: Long) {
        prefs.edit { putLong(idKey(entry, asset), id) }
    }

    private fun clearDownloadId(entry: ModelCatalogEntry, asset: ModelCatalogAsset) {
        prefs.edit { remove(idKey(entry, asset)) }
    }

    private fun saveError(entry: ModelCatalogEntry, message: String) {
        prefs.edit { putString(errorKey(entry), message) }
    }

    private fun idKey(entry: ModelCatalogEntry, asset: ModelCatalogAsset) =
        "download_${entry.id}_${asset.id}"

    private fun verifiedKey(entry: ModelCatalogEntry) = "verified_${entry.id}"
    private fun errorKey(entry: ModelCatalogEntry) = "error_${entry.id}"

    private fun formatBytes(bytes: Long): String =
        String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)

    companion object {
        private val operationMutex = Mutex()
        private const val PREFS_NAME = "model_catalog_downloads"
        private const val MODELS_DIRECTORY = "models"
        private const val NO_DOWNLOAD_ID = -1L
        private const val FREE_SPACE_RESERVE_BYTES = 256L * 1024L * 1024L
    }
}
