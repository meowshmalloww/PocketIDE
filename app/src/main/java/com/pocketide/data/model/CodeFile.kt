package com.pocketide.data.model

data class CodeFile(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val language: Language,
    val content: String = "",
    val isModified: Boolean = false,
)

data class Project(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val files: List<CodeFile> = emptyList(),
    val activeFileIndex: Int = 0,
) {
    val activeFile: CodeFile?
        get() = files.getOrNull(activeFileIndex)
}
