package com.pocketide.data.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ImportedModelFile(
    val displayName: String,
    val internalPath: String,
    val sizeBytes: Long,
)

/** Copies a user-selected SAF document into app-private storage for stable native-library paths. */
class ModelFileImporter(private val context: Context) {

    suspend fun import(uri: Uri, requireModelExtension: Boolean): Result<ImportedModelFile> =
        withContext(Dispatchers.IO) {
            runCatching {
                val displayName = queryDisplayName(uri).ifBlank { "selected_model" }
                if (requireModelExtension &&
                    !displayName.endsWith(".gguf", true) &&
                    !displayName.endsWith(".pte", true)
                ) {
                    error("Select a .gguf or .pte model file")
                }

                val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
                val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val target = uniqueTarget(modelsDir, safeName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
                } ?: error("The selected file could not be opened")

                ImportedModelFile(displayName, target.absolutePath, target.length())
            }
        }

    fun deleteImported(path: String): Boolean = runCatching {
        if (path.isBlank()) return@runCatching false
        val root = File(context.filesDir, "models").canonicalFile
        val target = File(path).canonicalFile
        val insideRoot = target.path == root.path || target.path.startsWith(root.path + File.separator)
        insideRoot && target.delete()
    }.getOrDefault(false)

    private fun queryDisplayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0).orEmpty()
            }
        return uri.lastPathSegment.orEmpty().substringAfterLast('/')
    }

    private fun uniqueTarget(directory: File, name: String): File {
        val initial = File(directory, name)
        if (!initial.exists()) return initial
        val stem = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
        var index = 2
        while (true) {
            val candidate = File(directory, "$stem-$index$extension")
            if (!candidate.exists()) return candidate
            index++
        }
    }
}
