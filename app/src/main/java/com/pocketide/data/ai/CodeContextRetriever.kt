package com.pocketide.data.ai

import com.pocketide.data.model.CodeFile

/**
 * Deterministic, fully local retrieval for repositories larger than the active model context.
 *
 * This does not pretend to enlarge transformer attention. It scans every supplied source file,
 * ranks code chunks using filenames, identifiers, declarations, imports, and the current request,
 * then injects only the best evidence that fits the real native prompt budget.
 */
object CodeContextRetriever {
    data class Result(
        val contextText: String,
        val indexedSourceTokens: Int,
        val retrievedTokens: Int,
        val scannedFiles: Int,
        val selectedChunks: Int,
    )

    fun retrieve(
        files: List<CodeFile>,
        activeFileIndex: Int,
        query: String,
        tokenBudget: Int,
    ): Result {
        val indexedTokens = files.sumOf { estimateTokens(it.content) }
        if (files.isEmpty() || tokenBudget <= MIN_USEFUL_BUDGET) {
            return Result("", indexedTokens, 0, files.size, 0)
        }

        val queryTerms = terms(query)
        val active = files.getOrNull(activeFileIndex)
        val dependencyStems = active?.content.orEmpty().lowercase().let { source ->
            files.asSequence()
                .filterNot { it.id == active?.id }
                .map { it.name.substringBeforeLast('.').lowercase() }
                .filter { it.length >= 2 && Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(source) }
                .toSet()
        }
        val chunks = files.flatMapIndexed { index, file ->
            chunksFor(file, index == activeFileIndex).map { chunk ->
                chunk.copy(score = score(chunk, queryTerms, dependencyStems))
            }
        }

        val evidencePrefix = "PROJECT RETRIEVAL: scanned ${files.size} files " +
            "(~$indexedTokens source tokens). The following chunks were selected locally for " +
            "this request; indexed project size is not the model context size.\n"
        var remaining = tokenBudget - estimateTokens(evidencePrefix)
        if (remaining <= MIN_USEFUL_BUDGET) {
            return Result("", indexedTokens, 0, files.size, 0)
        }

        val selected = mutableListOf<Chunk>()
        val activeWhole = chunks.firstOrNull { it.active && it.wholeFile }
        if (activeWhole != null && activeWhole.tokenCount <= remaining) {
            selected += activeWhole
            remaining -= activeWhole.tokenCount
        } else {
            chunks.filter { it.active }
                .maxWithOrNull(chunkComparator)
                ?.takeIf { it.tokenCount <= remaining }
                ?.let {
                    selected += it
                    remaining -= it.tokenCount
                }
        }

        chunks.asSequence()
            .filterNot { candidate -> selected.any { it.key == candidate.key } }
            .sortedWith(chunkComparator)
            .forEach { candidate ->
                if (candidate.tokenCount <= remaining) {
                    selected += candidate
                    remaining -= candidate.tokenCount
                }
            }

        if (selected.isEmpty()) return Result("", indexedTokens, 0, files.size, 0)
        val text = evidencePrefix + selected.joinToString("\n") { it.render() }
        return Result(
            contextText = text,
            indexedSourceTokens = indexedTokens,
            retrievedTokens = estimateTokens(text),
            scannedFiles = files.size,
            selectedChunks = selected.size,
        )
    }

    private fun chunksFor(file: CodeFile, active: Boolean): List<Chunk> {
        val lines = file.content.lines()
        val full = Chunk(
            fileName = file.name,
            language = file.language.displayName,
            startLine = 1,
            endLine = lines.size.coerceAtLeast(1),
            content = file.content,
            active = active,
            wholeFile = true,
        )
        if (active && full.tokenCount <= ACTIVE_WHOLE_FILE_LIMIT) return listOf(full)

        val result = mutableListOf<Chunk>()
        val outline = lines.mapIndexedNotNull { index, line ->
            line.takeIf(::isStructuralLine)?.let { "${index + 1}: ${it.trim()}" }
        }.take(MAX_OUTLINE_LINES)
        if (outline.isNotEmpty()) {
            result += Chunk(
                fileName = file.name,
                language = file.language.displayName,
                startLine = 1,
                endLine = lines.size.coerceAtLeast(1),
                content = "FILE OUTLINE\n${outline.joinToString("\n")}",
                active = active,
                wholeFile = false,
                outline = true,
            )
        }

        var start = 0
        while (start < lines.size) {
            val end = (start + CHUNK_LINES).coerceAtMost(lines.size)
            result += Chunk(
                fileName = file.name,
                language = file.language.displayName,
                startLine = start + 1,
                endLine = end,
                content = lines.subList(start, end).joinToString("\n"),
                active = active,
                wholeFile = false,
            )
            if (end == lines.size) break
            start = (end - CHUNK_OVERLAP_LINES).coerceAtLeast(start + 1)
        }
        return result.ifEmpty { listOf(full) }
    }

    private fun score(
        chunk: Chunk,
        queryTerms: Set<String>,
        dependencyStems: Set<String>,
    ): Int {
        val fileName = chunk.fileName.lowercase()
        val stem = fileName.substringBeforeLast('.')
        val content = chunk.content.lowercase()
        val contentTerms = terms(content)
        var score = when {
            chunk.active && chunk.wholeFile -> 200
            chunk.active -> 80
            chunk.outline -> 12
            else -> 0
        }
        if (stem in dependencyStems) score += 45
        queryTerms.forEach { term ->
            if (term in fileName) score += 35
            if (term in contentTerms) score += 8
            if (Regex("\\b(class|def|fun|function|interface|object)\\s+${Regex.escape(term)}\\b")
                    .containsMatchIn(content)
            ) {
                score += 30
            }
        }
        if (ERROR_TERMS.any { it in queryTerms } && ERROR_MARKERS.any { it in content }) score += 16
        return score
    }

    private fun isStructuralLine(line: String): Boolean {
        val value = line.trimStart()
        return STRUCTURAL_PREFIXES.any(value::startsWith) ||
            value.contains(" import ") || value.startsWith("import ") || value.startsWith("from ")
    }

    private fun terms(text: String): Set<String> = TERM_REGEX.findAll(text.lowercase())
        .map { it.value }
        .filter { it.length >= 2 && it !in STOP_WORDS }
        .toSet()

    private fun estimateTokens(text: String): Int = (text.length / CHARS_PER_TOKEN) + 1

    private data class Chunk(
        val fileName: String,
        val language: String,
        val startLine: Int,
        val endLine: Int,
        val content: String,
        val active: Boolean,
        val wholeFile: Boolean,
        val outline: Boolean = false,
        val score: Int = 0,
    ) {
        val key: String get() = "$fileName:$startLine:$endLine:$outline"
        val tokenCount: Int get() = estimateTokens(render())

        fun render(): String = buildString {
            append("--- ").append(fileName)
            if (active) append(" (active)")
            append(" [").append(language).append(", lines ")
                .append(startLine).append('-').append(endLine).append("] ---\n")
            append(content).append('\n')
        }
    }

    private val chunkComparator = compareByDescending<Chunk> { it.score }
        .thenByDescending { it.active }
        .thenBy { it.fileName }
        .thenBy { it.startLine }

    private const val CHARS_PER_TOKEN = 4
    private const val CHUNK_LINES = 36
    private const val CHUNK_OVERLAP_LINES = 6
    private const val MAX_OUTLINE_LINES = 60
    private const val ACTIVE_WHOLE_FILE_LIMIT = 1600
    private const val MIN_USEFUL_BUDGET = 48
    private val TERM_REGEX = Regex("[a-z_][a-z0-9_]*")
    private val STRUCTURAL_PREFIXES = listOf(
        "class ", "interface ", "object ", "enum ", "data class ", "sealed class ",
        "fun ", "def ", "function ", "async function ", "const ", "type ", "struct ",
    )
    private val ERROR_TERMS = setOf("error", "exception", "failed", "crash", "fix", "debug")
    private val ERROR_MARKERS = setOf("throw ", "raise ", "catch ", "except ", "error(")
    private val STOP_WORDS = setOf(
        "the", "and", "for", "with", "this", "that", "from", "into", "create", "build",
        "file", "code", "make", "use", "using", "when", "then", "one", "all", "not",
    )
}
