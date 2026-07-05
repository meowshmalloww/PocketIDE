package com.pocketide.data.ai

import com.pocketide.data.model.Language

data class ParsedAiResponse(
    val plan: String?,
    val code: String?,
    val language: Language?,
    val filename: String?,
)

private val PLAN_REGEX = Regex("""(?im)^\s*PLAN\s*[:\-]\s*(.+)$""")
private val FILENAME_REGEX = Regex("""(?im)^\s*FILENAME\s*[:\-]\s*[`"']?([^`"'\s]+)[`"']?\s*$""")
private val CODE_BLOCK_REGEX = Regex("""```([A-Za-z0-9+#]*)\s*\n([\s\S]*?)```""")
// Fallback: matches an unterminated code fence (AI stopped mid-generation).
private val UNCLOSED_CODE_BLOCK_REGEX = Regex("""```([A-Za-z0-9+#]*)\s*\n([\s\S]*)$""")

/**
 * Parses a raw AI response into structured fields. Tolerates:
 *  - Case-insensitive PLAN / FILENAME headers
 *  - PLAN/FILENAME separated by `:` or `-`
 *  - Filenames wrapped in quotes or backticks
 *  - Missing language tag on code fence
 *  - Unterminated code fence (AI hit token limit mid-block)
 *  - No fenced block at all — falls back to raw content if a filename was given
 */
fun parseAiResponse(rawContent: String): ParsedAiResponse {
    val plan = PLAN_REGEX.find(rawContent)?.groupValues?.get(1)?.trim()?.trim('`', '"', '\'')
    val filename = FILENAME_REGEX.find(rawContent)?.groupValues?.get(1)?.trim()

    // Try closed fence first, then unclosed fallback.
    val closedMatch = CODE_BLOCK_REGEX.find(rawContent)
    val (codeLangTag, code) = when {
        closedMatch != null -> {
            closedMatch.groupValues[1].trim().lowercase() to closedMatch.groupValues[2].trimEnd()
        }
        else -> {
            val open = UNCLOSED_CODE_BLOCK_REGEX.find(rawContent)
            if (open != null) {
                open.groupValues[1].trim().lowercase() to open.groupValues[2].trimEnd()
            } else {
                "" to null
            }
        }
    }

    val language = languageFromTag(codeLangTag)
        ?: filename?.substringAfterLast('.', "")?.let { Language.fromExtension(it) }

    return ParsedAiResponse(plan = plan, code = code, language = language, filename = filename)
}

private fun languageFromTag(tag: String): Language? = when (tag) {
    "python", "py", "python3" -> Language.PYTHON
    "javascript", "js", "node", "nodejs" -> Language.JAVASCRIPT
    "typescript", "ts" -> Language.TYPESCRIPT
    "kotlin", "kt", "kts" -> Language.KOTLIN
    "dart" -> Language.DART
    "java" -> Language.JAVA
    "sql", "sqlite" -> Language.SQL
    "shell", "sh", "bash", "zsh" -> Language.SHELL
    "yaml", "yml" -> Language.YAML
    "css" -> Language.CSS
    "lua" -> Language.LUA
    "html", "htm" -> Language.HTML
    "markdown", "md" -> Language.MARKDOWN
    "json" -> Language.JSON
    else -> null
}
