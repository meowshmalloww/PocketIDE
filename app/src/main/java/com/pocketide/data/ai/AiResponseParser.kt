package com.pocketide.data.ai

import com.pocketide.data.model.Language

data class ParsedAiResponse(
    val plan: String?,
    val code: String?,
    val language: Language?,
    val filename: String?,
)

private val PLAN_REGEX = Regex("""(?m)^PLAN:\s*(.+)$""")
private val FILENAME_REGEX = Regex("""(?m)^FILENAME:\s*(\S+)$""")
private val CODE_BLOCK_REGEX = Regex("""```([A-Za-z0-9+#]*)\n([\s\S]*?)```""")

fun parseAiResponse(rawContent: String): ParsedAiResponse {
    val plan = PLAN_REGEX.find(rawContent)?.groupValues?.get(1)?.trim()
    val filename = FILENAME_REGEX.find(rawContent)?.groupValues?.get(1)?.trim()

    val codeMatch = CODE_BLOCK_REGEX.find(rawContent)
    val codeLangTag = codeMatch?.groupValues?.get(1)?.trim()?.lowercase()
    val code = codeMatch?.groupValues?.get(2)?.trimEnd()

    val language = when (codeLangTag) {
        "python", "py" -> Language.PYTHON
        "javascript", "js" -> Language.JAVASCRIPT
        "typescript", "ts" -> Language.TYPESCRIPT
        "kotlin", "kt" -> Language.KOTLIN
        "dart" -> Language.DART
        "java" -> Language.JAVA
        "sql" -> Language.SQL
        "shell", "sh", "bash" -> Language.SHELL
        "yaml", "yml" -> Language.YAML
        "css" -> Language.CSS
        "lua" -> Language.LUA
        "html" -> Language.HTML
        "markdown", "md" -> Language.MARKDOWN
        "json" -> Language.JSON
        else -> filename?.substringAfterLast('.', "")?.let { Language.fromExtension(it) }
    }

    return ParsedAiResponse(plan = plan, code = code, language = language, filename = filename)
}
