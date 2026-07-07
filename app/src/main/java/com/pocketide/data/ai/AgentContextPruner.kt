package com.pocketide.data.ai

import com.pocketide.data.model.CodeFile

/**
 * Role-specific context pruning for SWARM pipeline agents.
 *
 * Each agent (Architect, Coder, Validator) has different information needs:
 * - **Architect**: needs high-level project overview, file names, languages. Does NOT need
 *   full code — only signatures and structure.
 * - **Coder**: needs the active file's full content and relevant file summaries. Focuses on
 *   implementation details.
 * - **Validator**: needs the failing code and error output. Does NOT need project context
 *   unless the error is cross-file.
 *
 * By tailoring context per agent, we reduce token consumption and improve generation quality
 * since the model sees only what's relevant to its role.
 */
object AgentContextPruner {

    private const val CHARS_PER_TOKEN = 4

    /**
     * Prunes context for the Architect agent.
     * Keeps: file names, languages, line counts, first 5 lines per file.
     * Drops: full file contents, conversation history beyond the last 2 turns.
     */
    fun pruneForArchitect(
        systemPrompt: String,
        history: List<ChatTurn>,
        userMessage: String,
        files: List<CodeFile>,
        contextWindowSize: Int,
    ): ManagedContext {
        val tokenBudget = contextWindowSize - estimateTokens(systemPrompt) - estimateTokens(userMessage) - 512

        val fileSummaries = buildString {
            files.forEach { file ->
                val lines = file.content.lines()
                val preview = lines.take(5).joinToString("\n")
                append("--- ${file.name} (${file.language.displayName}, ${lines.size} lines) ---\n")
                append(preview)
                if (lines.size > 5) append("\n... [${lines.size - 5} more lines]")
                append("\n\n")
            }
        }

        val fileSummaryTokens = estimateTokens(fileSummaries)
        val historyBudget = (tokenBudget - fileSummaryTokens).coerceAtLeast(0)

        val trimmedHistory = pruneHistory(history, historyBudget, maxTurns = 4)
        val augmentedSystem = "$systemPrompt\n\nPROJECT FILES (summaries only):\n$fileSummaries"

        return ManagedContext(
            systemPrompt = augmentedSystem,
            history = trimmedHistory,
            userMessage = userMessage,
            codeContext = fileSummaries,
        )
    }

    /**
     * Prunes context for the Coder agent.
     * Keeps: full active file content, summaries of other files, architect's plan.
     * Drops: older conversation history beyond the last 3 turns.
     */
    fun pruneForCoder(
        systemPrompt: String,
        history: List<ChatTurn>,
        userMessage: String,
        files: List<CodeFile>,
        contextWindowSize: Int,
        activeFileIndex: Int,
    ): ManagedContext {
        val tokenBudget = contextWindowSize - estimateTokens(systemPrompt) - estimateTokens(userMessage) - 512

        val activeFile = files.getOrNull(activeFileIndex)
        val activeContent = if (activeFile != null) {
            "--- ${activeFile.name} (active, ${activeFile.language.displayName}) ---\n${activeFile.content}\n\n"
        } else {
            ""
        }

        val otherSummaries = buildString {
            files.forEachIndexed { index, file ->
                if (index == activeFileIndex) return@forEachIndexed
                val lines = file.content.lines()
                val preview = lines.take(10).joinToString("\n")
                append("--- ${file.name} (${file.language.displayName}, ${lines.size} lines) ---\n")
                append(preview)
                if (lines.size > 10) append("\n... [${lines.size - 10} more lines]")
                append("\n\n")
            }
        }

        val codeContext = activeContent + otherSummaries
        val codeTokens = estimateTokens(codeContext)
        val historyBudget = (tokenBudget - codeTokens).coerceAtLeast(0)

        val trimmedHistory = pruneHistory(history, historyBudget, maxTurns = 3)
        val augmentedSystem = "$systemPrompt\n\nCURRENT PROJECT FILES:\n$codeContext"

        return ManagedContext(
            systemPrompt = augmentedSystem,
            history = trimmedHistory,
            userMessage = userMessage,
            codeContext = codeContext,
        )
    }

    /**
     * Prunes context for the Validator agent.
     * Keeps: the failing code (from user message or last assistant turn), error output.
     * Drops: project file context (unless error references other files), most history.
     */
    fun pruneForValidator(
        systemPrompt: String,
        history: List<ChatTurn>,
        userMessage: String,
        files: List<CodeFile>,
        contextWindowSize: Int,
        errorOutput: String,
    ): ManagedContext {
        val tokenBudget = contextWindowSize - estimateTokens(systemPrompt) - 512

        // Include error output in the system prompt
        val errorContext = "VALIDATION ERROR:\n$errorOutput\n\n"
        val errorTokens = estimateTokens(errorContext)

        // Only include file context if the error references a file
        val codeContext = if (files.isNotEmpty()) {
            val activeContent = files.joinToString("\n\n") { file ->
                val lines = file.content.lines()
                val preview = lines.take(20).joinToString("\n")
                "--- ${file.name} (${file.language.displayName}, ${lines.size} lines) ---\n$preview" +
                    if (lines.size > 20) "\n... [${lines.size - 20} more lines]" else ""
            }
            val codeTokens = estimateTokens(activeContent)
            if (errorTokens + codeTokens < tokenBudget) activeContent else ""
        } else {
            ""
        }

        val historyBudget = (tokenBudget - errorTokens - estimateTokens(codeContext)).coerceAtLeast(0)
        val trimmedHistory = pruneHistory(history, historyBudget, maxTurns = 2)

        val augmentedSystem = "$systemPrompt\n\n$errorContext$codeContext"

        return ManagedContext(
            systemPrompt = augmentedSystem,
            history = trimmedHistory,
            userMessage = userMessage,
            codeContext = codeContext,
        )
    }

    private fun pruneHistory(
        history: List<ChatTurn>,
        tokenBudget: Int,
        maxTurns: Int,
    ): List<ChatTurn> {
        if (history.isEmpty()) return emptyList()

        val recent = history.takeLast(maxTurns)
        val totalTokens = recent.sumOf { estimateTokens(it.content) + 4 }

        if (totalTokens <= tokenBudget) return recent

        // Walk backwards from most recent, keeping what fits
        val kept = mutableListOf<ChatTurn>()
        var usedTokens = 0
        for (i in recent.indices.reversed()) {
            val turn = recent[i]
            val turnTokens = estimateTokens(turn.content) + 4
            if (usedTokens + turnTokens > tokenBudget) break
            kept.add(0, turn)
            usedTokens += turnTokens
        }

        return kept
    }

    private fun estimateTokens(text: String): Int =
        (text.length / CHARS_PER_TOKEN) + 1
}
