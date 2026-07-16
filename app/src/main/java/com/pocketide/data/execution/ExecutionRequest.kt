package com.pocketide.data.execution

import com.pocketide.data.model.Language
import java.io.File

data class ExecutionRequest(
    val code: String,
    val language: Language,
    val fileName: String = "main.${language.fileExtension}",
    val projectDirectory: File? = null,
    val console: ExecutionConsole = ExecutionConsole(),
)

