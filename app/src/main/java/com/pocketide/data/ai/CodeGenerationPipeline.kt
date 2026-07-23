package com.pocketide.data.ai

import com.pocketide.data.model.CodeFile
import com.pocketide.data.model.Language

/** A single file that PocketIDE asks the local model to complete in one bounded generation. */
data class CodeFileTask(
    val filename: String,
    val language: Language,
)

/** Information supplied to one independent local-model file generation. */
data class CodeFileAttempt(
    val originalRequest: String,
    val target: CodeFileTask,
    val allTargets: List<CodeFileTask>,
    val completedFiles: List<ParsedAiFile>,
    val attempt: Int,
    val maxAttempts: Int,
    val outputTokenLimit: Int,
    val previousProblems: List<String> = emptyList(),
) {
    val lineBudget: Int
        get() {
            val maximum = when (target.language) {
                Language.HTML, Language.CSS -> 72
                Language.JAVASCRIPT, Language.TYPESCRIPT -> 64
                else -> 56
            }
            // A physical code line often expands to many model tokens. Leave enough room for
            // closing syntax and make a retry materially smaller instead of shaving off two lines.
            val initial = (outputTokenLimit / 16).coerceIn(20, maximum)
            return (initial / attempt.coerceAtLeast(1)).coerceAtLeast(16)
        }
}

sealed class CodeModelReply {
    data class Success(
        val content: String,
        val tokensPerSecond: Float? = null,
        val hitOutputLimit: Boolean = false,
    ) : CodeModelReply()

    data class Error(val message: String) : CodeModelReply()
}

sealed class CodeProjectGenerationResult {
    data class Success(
        val plan: String,
        val files: List<ParsedAiFile>,
        val averageTokensPerSecond: Float?,
        val totalModelCalls: Int,
    ) : CodeProjectGenerationResult()

    data class Error(
        val message: String,
        val failedFilename: String,
        val completedFiles: List<ParsedAiFile>,
        val totalModelCalls: Int,
    ) : CodeProjectGenerationResult()
}

/**
 * Splits a project request into bounded, independent file generations.
 *
 * A 1B to 2B model on a low-memory phone often has only a few hundred safe output tokens.
 * Asking it for an entire multi-file project in one completion therefore cuts the final code
 * fence and makes the whole response unusable. This coordinator gives every file a clean output
 * budget and retries only that file with a stricter compactness target when it is incomplete.
 */
object CodeGenerationPipeline {

    private const val MAX_PROJECT_FILES = 6
    private const val DEFAULT_MAX_ATTEMPTS = 3
    private const val MAX_CONSECUTIVE_OUTPUT_LIMIT_FAILURES = 2

    private val filenameRegex = Regex(
        """(?i)(?<![A-Za-z0-9_.-])([A-Za-z0-9][A-Za-z0-9_.-]*\.(?:py|js|ts|html?|css|lua|sql|sh|java|json|ya?ml|md|kt|dart))(?![A-Za-z0-9_-]|\.[A-Za-z0-9])""",
    )
    private val fileActionSegmentRegex = Regex(
        """(?is)\b(?:create|write|generate|add|build|make|implement|modify|update|fix|repair|refactor)\b.{0,320}?(?=(?:[!?]|\.(?=\s+(?:[*#]*[A-Z]|$))|\r?\n\r?\n|$))""",
    )
    private val editIntentRegex = Regex(
        """(?i)\b(?:modify|update|fix|repair|refactor|debug|change|edit|add|remove|improve|simplify|make|continue|finish|complete)\b""",
    )

    /** Resolve explicit requested filenames without mistaking runtime data such as inventory.json for source. */
    fun resolveTargets(request: String, activeFile: CodeFile?): List<CodeFileTask> {
        val normalized = request.replace("\\_", "_")
        val explicit = fileActionSegmentRegex.findAll(normalized)
            .flatMap { filenameRegex.findAll(it.value).map { match -> match.groupValues[1] } }
            .mapNotNull(::toTask)
            .distinctBy { it.filename.lowercase() }
            .take(MAX_PROJECT_FILES)
            .toList()
        if (explicit.isNotEmpty()) return explicit

        val referenced = filenameRegex.findAll(normalized)
            .mapNotNull { toTask(it.groupValues[1]) }
            .distinctBy { it.filename.lowercase() }
            .take(MAX_PROJECT_FILES)
            .toList()
        if (referenced.isNotEmpty()) return referenced

        if (activeFile != null && editIntentRegex.containsMatchIn(normalized)) {
            return listOf(CodeFileTask(activeFile.name, activeFile.language))
        }

        return listOf(inferDefaultTarget(normalized))
    }

    fun buildPlan(targets: List<CodeFileTask>): String = when (targets.size) {
        0 -> "No runnable project files were identified."
        1 -> "Create a compact, complete ${targets.first().filename} that satisfies the request."
        else -> "Create ${targets.joinToString { it.filename }} as a complete, working project."
    }

    fun systemPrompt(attempt: CodeFileAttempt): String = buildString {
        appendLine("You are PocketIDE's local file implementation agent.")
        appendLine("Write exactly one complete file: ${attempt.target.filename}")
        appendLine("FILE RESPONSIBILITY: ${responsibilityFor(attempt.target, attempt.allTargets)}")
        appendLine("Implement only that responsibility. Never duplicate work assigned to sibling files.")
        appendLine("Return only the raw contents of ${attempt.target.filename}.")
        appendLine("Do not write a FILE header, Markdown fence, PLAN, explanation, placeholder, TODO, test, or second file.")
        appendLine(
            "HARD SIZE CONTRACT: at most ${attempt.lineBudget} physical code lines. No comments or blank lines. " +
                "The runtime hard-stops at ${attempt.outputTokenLimit} output tokens, so finish the file early.",
        )
        appendLine("Use one compact implementation instead of many helper functions. Close every string and block.")
        requiredApiGuidance(attempt)?.let { appendLine(it) }
        GenerationContractValidator.promptGuidance(
            attempt.originalRequest,
            attempt.target,
            attempt.allTargets,
        ).forEach { appendLine("REQUIRED: $it") }
        append(runtimeGuidance(attempt.target.language, attempt.allTargets))
    }

    fun userPrompt(attempt: CodeFileAttempt): String = buildString {
        appendLine("PROJECT REQUEST")
        appendLine(attempt.originalRequest.trim())
        appendLine()
        appendLine("PROJECT FILES: ${attempt.allTargets.joinToString { it.filename }}")
        appendLine("WRITE NOW: ${attempt.target.filename}")
        appendLine("Apply only this file's FILE RESPONSIBILITY while preserving its part of the project requirements.")
        if (attempt.completedFiles.isNotEmpty()) {
            appendLine("Already completed sibling files are provided in CURRENT PROJECT FILES. Match their names and APIs.")
        }
        if (attempt.previousProblems.isNotEmpty()) {
            appendLine()
            appendLine("LOCAL VALIDATOR REJECTED THE PREVIOUS FILE:")
            attempt.previousProblems.forEach { appendLine("* $it") }
            if (attempt.previousProblems.any { "output limit" in it }) {
                appendLine(
                    "The previous answer was too large. Use at most ${attempt.lineBudget} short " +
                        "physical lines, keep labels and styling compact, and finish all closing syntax first.",
                )
            }
            appendLine("Rewrite the whole file from the beginning and fix every listed problem.")
        } else if (attempt.attempt > 1) {
            appendLine()
            appendLine("The previous attempt was incomplete. Rewrite the whole file more compactly from the beginning.")
        }
    }

    suspend fun generateProject(
        originalRequest: String,
        targets: List<CodeFileTask>,
        outputTokenLimit: Int,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        generate: suspend (CodeFileAttempt) -> CodeModelReply,
    ): CodeProjectGenerationResult {
        if (targets.isEmpty()) {
            return CodeProjectGenerationResult.Error(
                message = "No supported project file could be identified.",
                failedFilename = "",
                completedFiles = emptyList(),
                totalModelCalls = 0,
            )
        }

        val completed = mutableListOf<ParsedAiFile>()
        val measuredSpeeds = mutableListOf<Float>()
        var totalCalls = 0

        val generatedByName = linkedMapOf<String, ParsedAiFile>()
        for (target in generationOrder(targets)) {
            var lastProblem = "The model did not return complete code."
            var completedTarget: ParsedAiFile? = null
            var previousProblems = emptyList<String>()
            var attemptsUsed = 0
            var consecutiveOutputLimitFailures = 0

            for (attemptNumber in 1..maxAttempts.coerceAtLeast(1)) {
                val attempt = CodeFileAttempt(
                    originalRequest = originalRequest,
                    target = target,
                    allTargets = targets,
                    completedFiles = completed.toList(),
                    attempt = attemptNumber,
                    maxAttempts = maxAttempts,
                    outputTokenLimit = outputTokenLimit,
                    previousProblems = previousProblems,
                )
                totalCalls++
                attemptsUsed++
                when (val reply = generate(attempt)) {
                    is CodeModelReply.Error -> {
                        return CodeProjectGenerationResult.Error(
                            message = "Could not generate ${target.filename}: ${reply.message} " +
                                "No existing project files were replaced.",
                            failedFilename = target.filename,
                            completedFiles = completed.toList(),
                            totalModelCalls = totalCalls,
                        )
                    }
                    is CodeModelReply.Success -> {
                        reply.tokensPerSecond?.takeIf { it > 0f }?.let(measuredSpeeds::add)
                        val parsed = parseAiResponse(reply.content)
                        val code = selectTargetCode(parsed, target, reply.content)
                        val missingApi = code?.let { missingRequiredApiSymbols(target, completed, it) }.orEmpty()
                        val contractProblems = code?.let {
                            GenerationContractValidator.validate(
                                request = originalRequest,
                                target = target,
                                allTargets = targets,
                                completedFiles = completed,
                                code = it,
                            )
                        }.orEmpty()
                        val truncated = parsed.isTruncated || reply.hitOutputLimit
                        if (!truncated && !code.isNullOrBlank() && missingApi.isEmpty() && contractProblems.isEmpty()) {
                            completedTarget = ParsedAiFile(
                                filename = target.filename,
                                code = code.trimEnd(),
                                language = target.language,
                            )
                            break
                        }
                        consecutiveOutputLimitFailures = if (truncated) {
                            consecutiveOutputLimitFailures + 1
                        } else {
                            0
                        }
                        previousProblems = buildList {
                            if (truncated) add("The response reached the safe $outputTokenLimit token output limit before completing the file.")
                            if (code.isNullOrBlank()) add("The response did not contain raw source for ${target.filename}.")
                            if (missingApi.isNotEmpty()) {
                                add("The file omitted APIs required by sibling code: ${missingApi.joinToString()}.")
                            }
                            addAll(contractProblems)
                        }
                        lastProblem = previousProblems.joinToString(" ")
                        // A third full rewrite after two hard cutoffs is both slow and unlikely to
                        // help. Preserve three attempts for validator repairs that are not length failures.
                        if (consecutiveOutputLimitFailures >= MAX_CONSECUTIVE_OUTPUT_LIMIT_FAILURES) break
                    }
                }
            }

            if (completedTarget == null) {
                return CodeProjectGenerationResult.Error(
                    message = "Could not finish ${target.filename} after $attemptsUsed compact attempts. " +
                        "$lastProblem No existing project files were replaced.",
                    failedFilename = target.filename,
                    completedFiles = completed.toList(),
                    totalModelCalls = totalCalls,
                )
            }
            completed += completedTarget
            generatedByName[completedTarget.filename.lowercase()] = completedTarget
        }

        return CodeProjectGenerationResult.Success(
            plan = buildPlan(targets),
            files = targets.mapNotNull { generatedByName[it.filename.lowercase()] },
            averageTokensPerSecond = measuredSpeeds.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            totalModelCalls = totalCalls,
        )
    }

    private fun selectTargetCode(
        parsed: ParsedAiResponse,
        target: CodeFileTask,
        rawContent: String,
    ): String? {
        val exact = parsed.files.firstOrNull { it.filename.equals(target.filename, ignoreCase = true) }
        if (exact != null) return exact.code
        if (parsed.files.size == 1) return parsed.files.first().code
        parsed.code?.let { return it }
        val raw = rawContent.trim()
        if ("```" in raw || Regex("""(?im)^\s*(?:PLAN|FILE|FILENAME)\s*[:\-]""").containsMatchIn(raw)) {
            return null
        }
        return raw.takeIf { GenerationContractValidator.looksLikeSource(it, target.language) }
    }

    private fun generationOrder(targets: List<CodeFileTask>): List<CodeFileTask> {
        val containsWebPage = targets.any { it.language == Language.HTML }
        return targets.withIndex()
            .sortedWith(compareBy<IndexedValue<CodeFileTask>> {
                val stem = it.value.filename.substringBeforeLast('.').lowercase()
                when {
                    // Markup owns the DOM contract. Style can then use its classes and the
                    // browser script can be checked against completed element IDs/attributes.
                    containsWebPage && it.value.language == Language.HTML -> 0
                    containsWebPage && it.value.language == Language.CSS -> 1
                    containsWebPage && it.value.language in
                        setOf(Language.JAVASCRIPT, Language.TYPESCRIPT) -> 2
                    containsWebPage -> 3
                    stem == "main" -> 0
                    else -> 1
                }
            }.thenBy { it.index })
            .map { it.value }
    }

    private fun responsibilityFor(target: CodeFileTask, allTargets: List<CodeFileTask>): String {
        val stem = target.filename.substringBeforeLast('.').lowercase()
        val hasEntrypointSibling = allTargets.any {
            it != target && it.filename.substringBeforeLast('.').lowercase() in setOf("main", "app", "index")
        }
        val hasStorageSibling = allTargets.any {
            it != target && it.filename.substringBeforeLast('.').lowercase() in
                setOf("storage", "store", "database", "db", "repository", "persistence")
        }
        return when {
            allTargets.size == 1 ->
                "Own the complete self-contained runnable program. Implement every requested behavior in this file and do not wait for sibling code."
            target.language == Language.HTML ->
                "Own semantic page markup and stable element IDs. Reference requested CSS and script siblings by exact filename; include no embedded CSS or script."
            target.language == Language.CSS ->
                "Own responsive visual styling only. Style the elements defined by the HTML sibling; include no markup or script."
            target.language in setOf(Language.JAVASCRIPT, Language.TYPESCRIPT) &&
                allTargets.any { it.language == Language.HTML } ->
                "Own browser interaction, state, validation, and persistence. Use the exact IDs in the completed HTML sibling; include no markup or CSS."
            stem in setOf("storage", "store", "database", "db", "repository", "persistence") ->
                "Own persistence and serialization only. Export the APIs used by completed siblings; include no terminal, UI, or domain operations."
            stem in setOf("main", "app", "cli", "run", "index") ->
                "Own the runnable entrypoint and user interaction. Import and coordinate sibling APIs; do not duplicate persistence or reusable domain logic."
            hasEntrypointSibling -> buildString {
                append("Own reusable $stem domain logic and export the exact APIs used by the completed entrypoint. Include no input loop or UI")
                if (hasStorageSibling) append(" and no file persistence")
                append('.')
            }
            else -> "Own the reusable $stem logic suggested by the request and filename. Export a compact API for sibling files; include no unrelated features."
        }
    }

    private fun requiredApiGuidance(attempt: CodeFileAttempt): String? {
        val required = requiredApiSymbols(attempt.target, attempt.completedFiles)
        if (required.isEmpty()) return null
        return "REQUIRED API CONTRACT FROM COMPLETED SIBLINGS: define/export exactly ${required.joinToString()}. " +
            "Do not rename or omit these symbols."
    }

    private fun missingRequiredApiSymbols(
        target: CodeFileTask,
        completedFiles: List<ParsedAiFile>,
        candidateCode: String,
    ): Set<String> {
        val required = requiredApiSymbols(target, completedFiles)
        if (required.isEmpty()) return emptySet()
        return required.filterNotTo(linkedSetOf()) { symbol ->
            val owner = symbol.substringBefore('.')
            val member = symbol.substringAfter('.', "")
            if (member.isNotEmpty()) {
                Regex("""(?m)^\s*class\s+${Regex.escape(owner)}\b""").containsMatchIn(candidateCode) &&
                    Regex("""(?m)^\s*def\s+${Regex.escape(member)}\b""").containsMatchIn(candidateCode)
            } else {
                Regex("""(?m)^\s*(?:def|class)\s+${Regex.escape(symbol)}\b""").containsMatchIn(candidateCode) ||
                    Regex("""(?m)^\s*(?:var\s+|let\s+|const\s+)?${Regex.escape(symbol)}\s*=""")
                        .containsMatchIn(candidateCode)
            }
        }
    }

    private fun requiredApiSymbols(
        target: CodeFileTask,
        completedFiles: List<ParsedAiFile>,
    ): Set<String> {
        if (target.language != Language.PYTHON) return emptySet()
        val module = target.filename.substringBeforeLast('.')
        val symbols = linkedSetOf<String>()
        val directImport = Regex("""(?m)^\s*from\s+${Regex.escape(module)}\s+import\s+([^\n#]+)""")
        val moduleImport = Regex(
            """(?m)^\s*import\s+${Regex.escape(module)}(?:\s+as\s+([A-Za-z_]\w*))?\s*$""",
        )
        completedFiles.forEach { file ->
            directImport.findAll(file.code).forEach { match ->
                match.groupValues[1].split(',').forEach { imported ->
                    val symbol = imported.trim().substringBefore(" as ")
                        .takeIf { it.matches(Regex("""[A-Za-z_]\w*""")) }
                        ?: return@forEach
                    symbols += symbol
                    Regex("""\b${Regex.escape(symbol)}\.([A-Za-z_]\w*)\s*\(""")
                        .findAll(file.code)
                        .forEach { call -> symbols += "$symbol.${call.groupValues[1]}" }
                    Regex("""\b([A-Za-z_]\w*)\s*=\s*${Regex.escape(symbol)}\s*\(""")
                        .findAll(file.code)
                        .forEach { construction ->
                            val variable = construction.groupValues[1]
                            Regex("""\b${Regex.escape(variable)}\.([A-Za-z_]\w*)\s*\(""")
                                .findAll(file.code)
                                .forEach { call -> symbols += "$symbol.${call.groupValues[1]}" }
                        }
                }
            }
            moduleImport.findAll(file.code).forEach { match ->
                val alias = match.groupValues[1].ifBlank { module }
                Regex("""\b${Regex.escape(alias)}\.([A-Za-z_]\w*)\s*\(""")
                    .findAll(file.code)
                    .forEach { call -> symbols += call.groupValues[1] }
            }
        }
        return symbols
    }

    private fun toTask(rawFilename: String): CodeFileTask? {
        val filename = rawFilename
            .replace("\\_", "_")
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim('.', ' ', '`', '"', '\'')
        if (filename.isBlank() || filename.length > 80 || !filename.matches(Regex("""[A-Za-z0-9][A-Za-z0-9_.-]*"""))) {
            return null
        }
        val language = languageForExtension(filename.substringAfterLast('.', "")) ?: return null
        return CodeFileTask(filename, language)
    }

    private fun inferDefaultTarget(request: String): CodeFileTask {
        val lower = request.lowercase()
        return when {
            "html" in lower || "web page" in lower || "website" in lower || "browser" in lower ->
                CodeFileTask("index.html", Language.HTML)
            "typescript" in lower -> CodeFileTask("app.ts", Language.TYPESCRIPT)
            "javascript" in lower -> CodeFileTask("main.js", Language.JAVASCRIPT)
            Regex("""\blua\b""").containsMatchIn(lower) -> CodeFileTask("main.lua", Language.LUA)
            Regex("""\bsql\b|sqlite""").containsMatchIn(lower) -> CodeFileTask("main.sql", Language.SQL)
            Regex("""\bshell\b|\bbash\b""").containsMatchIn(lower) -> CodeFileTask("main.sh", Language.SHELL)
            Regex("""\bjava\b""").containsMatchIn(lower) -> CodeFileTask("main.java", Language.JAVA)
            else -> CodeFileTask("main.py", Language.PYTHON)
        }
    }

    private fun languageForExtension(extension: String): Language? = when (extension.lowercase()) {
        "htm" -> Language.HTML
        "yml" -> Language.YAML
        else -> Language.fromExtension(extension)
    }

    private fun runtimeGuidance(language: Language, allTargets: List<CodeFileTask>): String = when (language) {
        Language.PYTHON -> "Python is CPython 3.11. input(), sibling imports, and the standard library work. " +
            "Avoid type hints and docstrings to save output. A global hardware object is injected for Android calls; never import it."
        Language.JAVASCRIPT -> if (allTargets.any { it.language == Language.HTML }) {
            "This JavaScript runs in PocketIDE's loopback browser preview. Use only local browser APIs and no network resources."
        } else {
            "PocketIDE script execution uses Rhino ES5: use var and function, not let, const, arrows, classes, " +
                "async, fetch, modules, or template literals. A global hardware object is available."
        }
        Language.TYPESCRIPT -> "Use only simple variable and function-parameter annotations. PocketIDE strips types " +
            "and runs ES5-compatible JavaScript. Browser preview serves .ts as JavaScript."
        Language.HTML -> "This file runs in PocketIDE's loopback browser preview. Reference requested sibling CSS and " +
            "JavaScript or TypeScript files by their exact relative names."
        Language.CSS -> "Write plain responsive CSS for a phone browser with no imports or network resources."
        Language.LUA -> "PocketIDE runs Lua through LuaJ and injects a global hardware object."
        Language.SQL -> "PocketIDE executes this file with Android SQLite."
        Language.SHELL -> "PocketIDE runs Android POSIX sh locally. Do not require root or downloaded commands."
        Language.JAVA -> "PocketIDE runs BeanShell-compatible Java scripting, not a full javac project."
        else -> "Produce valid ${language.displayName} content supported by PocketIDE."
    }
}
