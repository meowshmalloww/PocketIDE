package com.pocketide.data.model

enum class Language(
    val displayName: String,
    val fileExtension: String,
    val mimeType: String,
) {
    PYTHON("Python", "py", "text/x-python"),
    JAVASCRIPT("JavaScript", "js", "text/javascript"),
    TYPESCRIPT("TypeScript", "ts", "text/typescript"),
    KOTLIN("Kotlin", "kt", "text/x-kotlin"),
    DART("Dart", "dart", "text/x-dart"),
    SQL("SQL", "sql", "application/sql"),
    HTML("HTML", "html", "text/html"),
    CSS("CSS", "css", "text/css"),
    JAVA("Java", "java", "text/x-java"),
    LUA("Lua", "lua", "text/x-lua"),
    SHELL("Shell", "sh", "application/x-sh"),
    YAML("YAML", "yaml", "text/yaml"),
    MARKDOWN("Markdown", "md", "text/markdown"),
    JSON("JSON", "json", "application/json"),
    ;

    companion object {
        fun fromExtension(ext: String): Language? =
            entries.firstOrNull { it.fileExtension.equals(ext, ignoreCase = true) }
    }
}
