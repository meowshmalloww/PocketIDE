package com.pocketide.data.repository

import android.content.Context
import com.pocketide.data.model.CodeFile
import com.pocketide.data.model.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class FileRepository(private val context: Context) {

    private val projectsDir: File by lazy {
        File(context.filesDir, "projects").apply { mkdirs() }
    }

    private fun projectDir(projectName: String): File =
        File(projectsDir, projectName).apply { mkdirs() }

    fun projectDirectory(projectName: String): File = projectDir(projectName)

    suspend fun saveFiles(projectName: String, files: List<CodeFile>): Unit = withContext(Dispatchers.IO) {
        val dir = projectDir(projectName)
        files.forEach { file -> File(dir, file.name).writeText(file.content) }
    }

    suspend fun saveFile(projectName: String, file: CodeFile): Unit = withContext(Dispatchers.IO) {
        val dir = projectDir(projectName)
        val target = File(dir, file.name)
        target.writeText(file.content)
    }

    suspend fun loadFile(projectName: String, fileName: String): CodeFile? = withContext(Dispatchers.IO) {
        val dir = projectDir(projectName)
        val target = File(dir, fileName)
        if (!target.exists()) return@withContext null
        val ext = fileName.substringAfterLast('.', "")
        val language = Language.fromExtension(ext) ?: Language.PYTHON
        CodeFile(
            name = fileName,
            language = language,
            content = target.readText(),
        )
    }

    suspend fun listFiles(projectName: String): List<CodeFile> = withContext(Dispatchers.IO) {
        val dir = projectDir(projectName)
        dir.listFiles()?.map { f ->
            val ext = f.name.substringAfterLast('.', "")
            val language = Language.fromExtension(ext) ?: Language.PYTHON
            CodeFile(
                name = f.name,
                language = language,
                content = f.readText(),
            )
        }?.sortedBy { it.name } ?: emptyList()
    }

    suspend fun deleteFile(projectName: String, fileName: String): Unit = withContext(Dispatchers.IO) {
        val dir = projectDir(projectName)
        File(dir, fileName).delete()
    }

    suspend fun listProjects(): List<String> = withContext(Dispatchers.IO) {
        projectsDir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
    }

    suspend fun createProject(name: String): Unit = withContext(Dispatchers.IO) {
        projectDir(name)
    }

    suspend fun deleteProject(name: String): Unit = withContext(Dispatchers.IO) {
        File(projectsDir, name).deleteRecursively()
    }
}
