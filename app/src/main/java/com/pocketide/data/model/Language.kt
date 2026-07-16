package com.pocketide.data.model

enum class Language(
    val displayName: String,
    val fileExtension: String,
    val mimeType: String,
    val executionSupport: ExecutionSupport,
    val supportsWebPreview: Boolean = false,
    val supportsHardwareBridge: Boolean = false,
) {
    PYTHON("Python", "py", "text/x-python", ExecutionSupport.RUNTIME, supportsHardwareBridge = true),
    JAVASCRIPT("JavaScript", "js", "text/javascript", ExecutionSupport.RUNTIME, supportsWebPreview = true, supportsHardwareBridge = true),
    TYPESCRIPT("TypeScript", "ts", "text/typescript", ExecutionSupport.COMPATIBILITY, supportsWebPreview = true, supportsHardwareBridge = true),
    KOTLIN("Kotlin", "kt", "text/x-kotlin", ExecutionSupport.EDITOR_ONLY),
    DART("Dart", "dart", "text/x-dart", ExecutionSupport.EDITOR_ONLY),
    SQL("SQL", "sql", "application/sql", ExecutionSupport.RUNTIME),
    HTML("HTML", "html", "text/html", ExecutionSupport.PREVIEW, supportsWebPreview = true),
    CSS("CSS", "css", "text/css", ExecutionSupport.PREVIEW, supportsWebPreview = true),
    JAVA("Java", "java", "text/x-java", ExecutionSupport.COMPATIBILITY, supportsHardwareBridge = true),
    LUA("Lua", "lua", "text/x-lua", ExecutionSupport.RUNTIME, supportsHardwareBridge = true),
    SHELL("Shell", "sh", "application/x-sh", ExecutionSupport.RUNTIME),
    YAML("YAML", "yaml", "text/yaml", ExecutionSupport.EDITOR_ONLY),
    MARKDOWN("Markdown", "md", "text/markdown", ExecutionSupport.EDITOR_ONLY),
    JSON("JSON", "json", "application/json", ExecutionSupport.EDITOR_ONLY),
    ;

    companion object {
        fun fromExtension(ext: String): Language? =
            entries.firstOrNull { it.fileExtension.equals(ext, ignoreCase = true) }
    }
}

enum class ExecutionSupport(val label: String, val description: String) {
    RUNTIME("On-device runtime", "Executed by an embedded on-device runtime"),
    COMPATIBILITY("Compatibility subset", "Common syntax is translated or interpreted; it is not a complete compiler"),
    PREVIEW("Browser preview", "Rendered by the phone browser through a loopback-only server"),
    EDITOR_ONLY("Editor only", "Editing and syntax support only; no on-device runtime is bundled"),
}
