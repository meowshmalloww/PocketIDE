package com.pocketide.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.pocketide.data.model.Language

private data class SyntaxConfig(
    val keywords: Set<String>,
    val lineComments: List<String> = emptyList(),
    val blockComments: List<Pair<String, String>> = emptyList(),
    val stringDelimiters: List<Char> = listOf('"', '\''),
    val supportsTripleQuote: Boolean = false,
)

private val PYTHON_KEYWORDS = setOf(
    "def", "class", "if", "elif", "else", "for", "while", "try", "except", "finally",
    "with", "as", "import", "from", "return", "yield", "break", "continue", "pass",
    "lambda", "and", "or", "not", "in", "is", "None", "True", "False", "global",
    "nonlocal", "assert", "del", "raise", "async", "await", "self",
)

private val JS_KEYWORDS = setOf(
    "function", "var", "let", "const", "if", "else", "for", "while", "do", "switch",
    "case", "default", "break", "continue", "return", "try", "catch", "finally",
    "throw", "new", "delete", "typeof", "instanceof", "in", "of", "this", "super",
    "class", "extends", "import", "export", "from", "as", "async", "await", "yield",
    "null", "undefined", "true", "false", "void", "static", "get", "set",
)

private val TS_KEYWORDS = JS_KEYWORDS + setOf(
    "interface", "type", "enum", "implements", "public", "private", "protected",
    "readonly", "abstract", "namespace", "declare", "is", "keyof", "infer", "never",
    "unknown", "any", "string", "number", "boolean",
)

private val KOTLIN_KEYWORDS = setOf(
    "fun", "val", "var", "if", "else", "for", "while", "do", "when", "is", "as",
    "in", "return", "break", "continue", "class", "object", "interface", "package",
    "import", "try", "catch", "finally", "throw", "null", "true", "false", "this",
    "super", "override", "private", "public", "protected", "internal", "open",
    "sealed", "data", "companion", "init", "constructor", "lateinit", "by", "get",
    "set", "suspend", "inline", "reified", "vararg", "typealias", "annotation",
    "enum", "abstract", "final", "const", "operator", "infix", "external",
)

private val DART_KEYWORDS = setOf(
    "void", "var", "final", "const", "if", "else", "for", "while", "do", "switch",
    "case", "default", "break", "continue", "return", "try", "catch", "finally",
    "throw", "new", "class", "extends", "implements", "with", "abstract", "static",
    "import", "export", "as", "is", "in", "null", "true", "false", "this", "super",
    "async", "await", "yield", "get", "set", "factory", "enum", "mixin", "late",
    "required", "typedef", "operator",
)

private val JAVA_KEYWORDS = setOf(
    "public", "private", "protected", "class", "interface", "extends", "implements",
    "static", "final", "void", "int", "long", "double", "float", "boolean", "char",
    "byte", "short", "if", "else", "for", "while", "do", "switch", "case", "default",
    "break", "continue", "return", "try", "catch", "finally", "throw", "throws",
    "new", "this", "super", "null", "true", "false", "import", "package", "abstract",
    "synchronized", "volatile", "transient", "enum", "instanceof",
)

private val SQL_KEYWORDS = setOf(
    "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE",
    "CREATE", "TABLE", "ALTER", "DROP", "JOIN", "INNER", "LEFT", "RIGHT", "OUTER",
    "ON", "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "AND", "OR", "NOT", "NULL",
    "IS", "IN", "LIKE", "BETWEEN", "AS", "DISTINCT", "UNION", "ALL", "EXISTS",
    "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "DEFAULT", "INDEX", "VIEW",
)

private val SHELL_KEYWORDS = setOf(
    "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac",
    "function", "return", "break", "continue", "export", "local", "readonly", "echo",
    "exit", "in", "select", "until",
)

private val YAML_KEYWORDS = setOf("true", "false", "null", "yes", "no")

private val CSS_KEYWORDS = setOf(
    "important", "inherit", "initial", "unset", "auto", "none", "solid", "dashed",
)

private val LUA_KEYWORDS = setOf(
    "function", "local", "if", "then", "else", "elseif", "end", "for", "while",
    "do", "repeat", "until", "return", "break", "nil", "true", "false", "and",
    "or", "not", "in", "require",
)

private val HTML_KEYWORDS = emptySet<String>()
private val MARKDOWN_KEYWORDS = emptySet<String>()
private val JSON_KEYWORDS = setOf("true", "false", "null")

private fun configFor(language: Language): SyntaxConfig = when (language) {
    Language.PYTHON -> SyntaxConfig(
        keywords = PYTHON_KEYWORDS,
        lineComments = listOf("#"),
        supportsTripleQuote = true,
    )
    Language.JAVASCRIPT -> SyntaxConfig(
        keywords = JS_KEYWORDS,
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/"),
        stringDelimiters = listOf('"', '\'', '`'),
    )
    Language.TYPESCRIPT -> SyntaxConfig(
        keywords = TS_KEYWORDS,
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/"),
        stringDelimiters = listOf('"', '\'', '`'),
    )
    Language.KOTLIN -> SyntaxConfig(
        keywords = KOTLIN_KEYWORDS,
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/"),
    )
    Language.DART -> SyntaxConfig(
        keywords = DART_KEYWORDS,
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/"),
    )
    Language.JAVA -> SyntaxConfig(
        keywords = JAVA_KEYWORDS,
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/"),
    )
    Language.SQL -> SyntaxConfig(
        keywords = SQL_KEYWORDS,
        lineComments = listOf("--"),
        blockComments = listOf("/*" to "*/"),
    )
    Language.SHELL -> SyntaxConfig(
        keywords = SHELL_KEYWORDS,
        lineComments = listOf("#"),
    )
    Language.YAML -> SyntaxConfig(
        keywords = YAML_KEYWORDS,
        lineComments = listOf("#"),
    )
    Language.CSS -> SyntaxConfig(
        keywords = CSS_KEYWORDS,
        blockComments = listOf("/*" to "*/"),
    )
    Language.LUA -> SyntaxConfig(
        keywords = LUA_KEYWORDS,
        lineComments = listOf("--"),
        blockComments = listOf("--[[" to "]]"),
    )
    Language.HTML -> SyntaxConfig(
        keywords = HTML_KEYWORDS,
        blockComments = listOf("<!--" to "-->"),
    )
    Language.MARKDOWN -> SyntaxConfig(keywords = MARKDOWN_KEYWORDS)
    Language.JSON -> SyntaxConfig(keywords = JSON_KEYWORDS)
}

private object SyntaxColors {
    val Keyword = Color(0xFFCC7832)
    val StringLit = Color(0xFF6A8759)
    val Comment = Color(0xFF808080)
    val NumberLit = Color(0xFF6897BB)
}

private const val TOKEN_NONE = 0
private const val TOKEN_KEYWORD = 1
private const val TOKEN_STRING = 2
private const val TOKEN_COMMENT = 3
private const val TOKEN_NUMBER = 4

private val NUMBER_REGEX = Regex("\\b\\d+(\\.\\d+)?[fFdDlL]?\\b")
private val WORD_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*")

fun highlightCode(code: String, language: Language): AnnotatedString {
    if (code.isEmpty()) return AnnotatedString("")
    val config = configFor(language)
    val tokens = IntArray(code.length)

    markComments(code, config, tokens)
    markStrings(code, config, tokens)
    markKeywords(code, config, tokens)
    markNumbers(code, tokens)

    return buildAnnotatedString {
        append(code)
        var start = 0
        var currentToken = tokens.getOrElse(0) { TOKEN_NONE }
        for (i in 1..code.length) {
            val token = tokens.getOrElse(i) { TOKEN_NONE }
            if (token != currentToken) {
                applyStyle(this, start, i, currentToken)
                start = i
                currentToken = token
            }
        }
        applyStyle(this, start, code.length, currentToken)
    }
}

private fun applyStyle(builder: androidx.compose.ui.text.AnnotatedString.Builder, start: Int, end: Int, token: Int) {
    if (start >= end) return
    val color = when (token) {
        TOKEN_KEYWORD -> SyntaxColors.Keyword
        TOKEN_STRING -> SyntaxColors.StringLit
        TOKEN_COMMENT -> SyntaxColors.Comment
        TOKEN_NUMBER -> SyntaxColors.NumberLit
        else -> null
    } ?: return
    builder.addStyle(SpanStyle(color = color), start, end)
}

private fun markComments(code: String, config: SyntaxConfig, tokens: IntArray) {
    for ((open, close) in config.blockComments) {
        var idx = code.indexOf(open)
        while (idx >= 0) {
            val endIdx = code.indexOf(close, idx + open.length)
            val end = if (endIdx >= 0) endIdx + close.length else code.length
            for (i in idx until end) tokens[i] = TOKEN_COMMENT
            idx = if (endIdx >= 0) code.indexOf(open, end) else -1
        }
    }
    if (config.lineComments.isNotEmpty()) {
        for (prefix in config.lineComments) {
            var idx = code.indexOf(prefix)
            while (idx >= 0) {
                if (tokens[idx] != TOKEN_COMMENT) {
                    var end = code.indexOf('\n', idx)
                    if (end < 0) end = code.length
                    var alreadyCommented = false
                    for (i in idx until end) {
                        if (tokens[i] == TOKEN_COMMENT && i != idx) {
                            alreadyCommented = true
                            break
                        }
                    }
                    if (!alreadyCommented) {
                        for (i in idx until end) tokens[i] = TOKEN_COMMENT
                    }
                }
                idx = code.indexOf(prefix, idx + prefix.length)
            }
        }
    }
}

private fun markStrings(code: String, config: SyntaxConfig, tokens: IntArray) {
    if (config.supportsTripleQuote) {
        for (quote in listOf("\"\"\"", "'''")) {
            var idx = code.indexOf(quote)
            while (idx >= 0) {
                if (tokens[idx] != TOKEN_COMMENT) {
                    val endIdx = code.indexOf(quote, idx + quote.length)
                    val end = if (endIdx >= 0) endIdx + quote.length else code.length
                    for (i in idx until end) if (tokens[i] != TOKEN_COMMENT) tokens[i] = TOKEN_STRING
                    idx = if (endIdx >= 0) code.indexOf(quote, end) else -1
                } else {
                    idx = code.indexOf(quote, idx + quote.length)
                }
            }
        }
    }
    var i = 0
    while (i < code.length) {
        if (tokens[i] == TOKEN_COMMENT || tokens[i] == TOKEN_STRING) {
            i++
            continue
        }
        val c = code[i]
        if (c in config.stringDelimiters) {
            val start = i
            i++
            while (i < code.length && code[i] != c) {
                if (code[i] == '\\' && i + 1 < code.length) i++
                i++
            }
            val end = (i + 1).coerceAtMost(code.length)
            for (j in start until end) tokens[j] = TOKEN_STRING
            i = end
        } else {
            i++
        }
    }
}

private fun markKeywords(code: String, config: SyntaxConfig, tokens: IntArray) {
    if (config.keywords.isEmpty()) return
    for (match in WORD_REGEX.findAll(code)) {
        val range = match.range
        if (tokens[range.first] != TOKEN_NONE) continue
        if (match.value in config.keywords) {
            for (i in range) tokens[i] = TOKEN_KEYWORD
        }
    }
}

private fun markNumbers(code: String, tokens: IntArray) {
    for (match in NUMBER_REGEX.findAll(code)) {
        val range = match.range
        if (tokens[range.first] != TOKEN_NONE) continue
        for (i in range) tokens[i] = TOKEN_NUMBER
    }
}
