package com.pocketide.data.ai

import com.pocketide.data.model.Language

data class ParsedAiResponse(
    val plan: String?,
    val code: String?,
    val language: Language?,
    val filename: String?,
    val files: List<ParsedAiFile> = emptyList(),
)

data class ParsedAiFile(
    val filename: String,
    val code: String,
    val language: Language,
)

private val PLAN_REGEX = Regex("""(?im)^\s*PLAN\s*[:\-]\s*(.+)$""")
private val FILENAME_REGEX = Regex("""(?im)^\s*(?:FILE|FILENAME)\s*[:\-]\s*[`"']?([^`"'\s]+)[`"']?\s*$""")
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
    val parsedFiles = parseFiles(rawContent)
    val firstParsedFile = parsedFiles.firstOrNull()
    val filename = firstParsedFile?.filename ?: FILENAME_REGEX.find(rawContent)?.groupValues?.get(1)?.trim()

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

    return ParsedAiResponse(
        plan = plan,
        code = firstParsedFile?.code ?: code,
        language = firstParsedFile?.language ?: language,
        filename = filename,
        files = parsedFiles,
    )
}

private fun parseFiles(rawContent: String): List<ParsedAiFile> {
    val headers = FILENAME_REGEX.findAll(rawContent).toList()
    return headers.mapIndexedNotNull { index, header ->
        val filename = header.groupValues[1].trim()
        val segmentEnd = headers.getOrNull(index + 1)?.range?.first ?: rawContent.length
        val segment = rawContent.substring(header.range.last + 1, segmentEnd)
        val fence = CODE_BLOCK_REGEX.find(segment) ?: UNCLOSED_CODE_BLOCK_REGEX.find(segment)
        val code = fence?.groupValues?.get(2)?.trimEnd() ?: return@mapIndexedNotNull null
        val tag = fence.groupValues[1].trim().lowercase()
        val language = languageFromTag(tag)
            ?: Language.fromExtension(filename.substringAfterLast('.', ""))
            ?: return@mapIndexedNotNull null
        ParsedAiFile(filename = filename, code = code, language = language)
    }
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
