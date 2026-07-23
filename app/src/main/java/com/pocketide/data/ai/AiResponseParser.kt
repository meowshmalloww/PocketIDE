package com.pocketide.data.ai

import com.pocketide.data.model.Language

data class ParsedAiResponse(
    val plan: String?,
    val code: String?,
    val language: Language?,
    val filename: String?,
    val files: List<ParsedAiFile> = emptyList(),
    val isTruncated: Boolean = false,
)

data class ParsedAiFile(
    val filename: String,
    val code: String,
    val language: Language,
)

private val PLAN_REGEX = Regex("""(?im)^\s*PLAN\s*[:\-]\s*(.+)$""")
private val FILENAME_REGEX = Regex("""(?im)^\s*(?:FILE|FILENAME)\s*[:\-]\s*[`"']?([^`"'\s]+)[`"']?\s*$""")
private val CODE_BLOCK_REGEX = Regex("""```[ \t]*([A-Za-z0-9+#-]*)[ \t]*\r?\n([\s\S]*?)```""")
// Fallback: matches an unterminated code fence (AI stopped mid-generation).
private val UNCLOSED_CODE_BLOCK_REGEX = Regex("""```[ \t]*([A-Za-z0-9+#-]*)[ \t]*\r?\n([\s\S]*)$""")

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
    val content = unwrapOuterFenceAroundFileResponse(rawContent)
    val isTruncated = content.windowed(3).count { it == "```" } % 2 != 0
    val plan = PLAN_REGEX.find(content)?.groupValues?.get(1)?.trim()?.trim('`', '"', '\'')
    val parsedFiles = parseFiles(content)
    val firstParsedFile = parsedFiles.firstOrNull()
    val filename = firstParsedFile?.filename ?: FILENAME_REGEX.find(content)?.groupValues?.get(1)?.trim()

    // Try closed fence first, then unclosed fallback.
    val closedMatch = CODE_BLOCK_REGEX.find(content)
    val (codeLangTag, code) = when {
        closedMatch != null -> {
            closedMatch.groupValues[1].trim().lowercase() to closedMatch.groupValues[2].trimEnd()
        }
        else -> {
            val open = UNCLOSED_CODE_BLOCK_REGEX.find(content)
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
        isTruncated = isTruncated,
    )
}

private fun parseFiles(rawContent: String): List<ParsedAiFile> {
    val headers = FILENAME_REGEX.findAll(rawContent).toList()
    return headers.mapIndexedNotNull { index, header ->
        val filename = header.groupValues[1].trim()
        val segmentEnd = headers.getOrNull(index + 1)?.range?.first ?: rawContent.length
        val segment = rawContent.substring(header.range.last + 1, segmentEnd)
        val fence = CODE_BLOCK_REGEX.find(segment) ?: UNCLOSED_CODE_BLOCK_REGEX.find(segment)
        val extensionLanguage = Language.fromExtension(filename.substringAfterLast('.', ""))
        val tag = fence?.groupValues?.get(1)?.trim()?.lowercase().orEmpty()
        val language = languageFromTag(tag) ?: extensionLanguage ?: return@mapIndexedNotNull null
        val code = if (fence != null) {
            fence.groupValues[2].trimEnd()
        } else {
            // Some small models follow FILE correctly but omit Markdown fences. Accept only text
            // that resembles source for the target language; never treat prose as a file.
            segment.trim().removePrefix("CODE:").trim()
                .takeIf { it.isNotBlank() && looksLikeSource(it, language) }
                ?: return@mapIndexedNotNull null
        }
        ParsedAiFile(filename = filename, code = code, language = language)
    }
}

/** Small models sometimes wrap the FILE header and source together in one extra Markdown fence. */
private fun unwrapOuterFenceAroundFileResponse(rawContent: String): String {
    val trimmed = rawContent.trim()
    if (!FILENAME_REGEX.containsMatchIn(trimmed)) return rawContent
    val outer = Regex("""^```[^\r\n]*\r?\n([\s\S]*)\r?\n```$""").matchEntire(trimmed)
        ?: return rawContent
    return outer.groupValues[1]
}

private fun looksLikeSource(text: String, language: Language): Boolean = when (language) {
    Language.PYTHON -> Regex("""(?m)^\s*(?:from\s+\S+\s+import|import\s+|def\s+|class\s+|try\s*:|if\s+|while\s+|for\s+|[A-Za-z_]\w*\s*=|print\s*\()""")
        .containsMatchIn(text)
    Language.HTML -> text.trimStart().startsWith("<")
    Language.CSS -> '{' in text && '}' in text
    Language.JSON -> text.trimStart().let { it.startsWith("{") || it.startsWith("[") }
    Language.SQL -> Regex("""(?i)\b(?:select|insert|update|delete|create|with|pragma)\b""").containsMatchIn(text)
    Language.SHELL -> text.startsWith("#!") || Regex("""(?m)^\s*(?:echo|if|for|while|[A-Za-z_]\w*=)""").containsMatchIn(text)
    else -> '=' in text || '{' in text || '(' in text
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
