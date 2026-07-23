package com.pocketide.data.ai

import com.pocketide.data.model.CodeFile

/**
 * Manages the limited context window of small on-device LLMs (0.5B–7B).
 *
 * Small models typically support 2K–8K tokens of context. When a user is
 * building a complex multi-file app, the conversation history + code context
 * can easily exceed this budget. [ContextManager] solves this by:
 *
 * 1. **Token estimation** — rough heuristic (~4 chars/token) to budget context.
 * 2. **Sliding window** — keeps the most recent N messages intact, drops older ones.
 * 3. **Conversation summarization** — compresses dropped messages into a single
 *    summary line so the AI retains context without consuming the full token budget.
 * 4. **Code context injection** — includes relevant file snippets (not entire files)
 *    so the AI knows what already exists in the project.
 * 5. **Token budget allocation** — divides the context window across system prompt,
 *    code context, conversation history, and the new user message.
 *
 * [AiConfig.contextWindowSize] is only a request. The resource planner clamps it to
 * the selected model's declared limit and the device's measured memory headroom.
 */
object ContextManager {

    private const val CHARS_PER_TOKEN = 4
    private const val MIN_HISTORY_TOKENS = 512
    private const val MIN_CODE_CONTEXT_TOKENS = 256
    private const val FORMATTER_HEADROOM_TOKENS = 64
    private const val TRUNCATION_SUFFIX = "\n... [truncated to fit context window]"
    private const val CODE_CONTEXT_PREFIX = "\n\nCURRENT PROJECT FILES:\n"

    /**
     * Rough token estimate. Not exact (no tokenizer loaded), but sufficient
     * for budgeting. Over-estimates slightly, which is safer than under-estimating.
     */
    fun estimateTokens(text: String): Int {
        return (text.length / CHARS_PER_TOKEN) + 1
    }

    /**
     * Builds the final prompt components that fit within the model's context window.
     *
     * @param systemPrompt the base system prompt for the current mode
     * @param history full conversation history
     * @param userMessage the new user message
     * @param files currently open files (for code context injection)
     * @param contextWindowSize max tokens the model can handle
     * @param responseTokenBudget output tokens reserved from the same native context
     * @param activeFileIndex which file is currently active (gets priority in code context)
     * @return [ManagedContext] with trimmed history, augmented system prompt, and user message
     */
    fun buildContext(
        systemPrompt: String,
        history: List<ChatTurn>,
        userMessage: String,
        files: List<CodeFile>,
        contextWindowSize: Int,
        responseTokenBudget: Int? = null,
        activeFileIndex: Int,
        enableCodeContext: Boolean = true,
        enableHistorySummary: Boolean = true,
    ): ManagedContext {
        val systemTokens = estimateTokens(systemPrompt)
        val userTokens = estimateTokens(userMessage)

        // The resource planner owns the real output cap. Reserving that same amount here keeps
        // prompt pruning and native n_predict within one context instead of silently letting the
        // input consume space that was intended for generated code.
        val responseBudget = (responseTokenBudget ?: (contextWindowSize / 4).coerceIn(256, 2048))
            .coerceIn(64, (contextWindowSize - FORMATTER_HEADROOM_TOKENS).coerceAtLeast(64))

        // Available budget for system + code context + history + user
        val available = (contextWindowSize - responseBudget - FORMATTER_HEADROOM_TOKENS)
            .coerceAtLeast(0)

        // Extremely long user messages must not produce a negative truncation length. Preserve
        // room for both the runtime instructions and the request instead of letting formatting
        // overflow the native context before generation begins.
        val safeUserMessage = if (userTokens >= available) {
            truncateText(
                userMessage,
                (available / 3).coerceAtLeast(0) * CHARS_PER_TOKEN,
            )
        } else {
            userMessage
        }
        val safeUserTokens = estimateTokens(safeUserMessage)

        // The user message is kept in full unless it alone would overflow the safe input budget.
        val remainingAfterUser = available - safeUserTokens

        // System prompt is always included in full
        val remainingAfterSystem = remainingAfterUser - systemTokens

        if (remainingAfterSystem <= 0) {
            // System prompt + user message alone exceed the window.
            // Return just the user message with a truncated system prompt.
            val truncatedSystem = truncateText(
                systemPrompt,
                (available - safeUserTokens).coerceAtLeast(0) * CHARS_PER_TOKEN,
            )
            return ManagedContext(
                systemPrompt = truncatedSystem,
                history = emptyList(),
                userMessage = safeUserMessage,
                codeContext = "",
            )
        }

        // Allocate remaining budget: 40% for code context, rest for history
        val codePrefixTokens = if (enableCodeContext && files.isNotEmpty()) {
            estimateTokens(CODE_CONTEXT_PREFIX)
        } else {
            0
        }
        val remainingForCodeAndHistory = (remainingAfterSystem - codePrefixTokens)
            .coerceAtLeast(0)
        val codeContextBudget = if (enableCodeContext && files.isNotEmpty()) {
            (remainingForCodeAndHistory * 0.4).toInt()
                .coerceAtLeast(MIN_CODE_CONTEXT_TOKENS)
                .coerceAtMost(remainingForCodeAndHistory)
        } else {
            0
        }

        // Build code context from open files (if enabled)
        val codeContext = if (enableCodeContext && files.isNotEmpty()) {
            CodeContextRetriever.retrieve(
                files = files,
                activeFileIndex = activeFileIndex,
                query = safeUserMessage,
                tokenBudget = codeContextBudget,
            ).contextText
        } else {
            ""
        }

        // Adjust history budget after code context
        val codeContextTokens = if (codeContext.isNotBlank()) {
            codePrefixTokens + estimateTokens(codeContext)
        } else {
            0
        }
        val adjustedHistoryBudget = (remainingAfterSystem - codeContextTokens).coerceAtLeast(0)

        // Trim history to fit (with or without summarization)
        val trimmedHistory = trimHistory(history, adjustedHistoryBudget, enableHistorySummary)

        // Augment system prompt with code context
        val augmentedSystem = if (codeContext.isNotBlank()) {
            "$systemPrompt$CODE_CONTEXT_PREFIX$codeContext"
        } else {
            systemPrompt
        }

        return ManagedContext(
            systemPrompt = augmentedSystem,
            history = trimmedHistory,
            userMessage = safeUserMessage,
            codeContext = codeContext,
        )
    }

    /**
     * Trims conversation history to fit within the token budget.
     * Keeps the most recent messages. If older messages are dropped,
     * creates a summary of what was discussed.
     */
    private fun trimHistory(
        history: List<ChatTurn>,
        tokenBudget: Int,
        enableSummary: Boolean = true,
    ): List<ChatTurn> {
        if (history.isEmpty()) return emptyList()

        val totalTokens = history.sumOf { estimateTokens(it.content) + 4 } // +4 for role tags
        if (totalTokens <= tokenBudget) return history

        // Walk from most recent backwards, accumulating until budget is hit
        val kept = mutableListOf<ChatTurn>()
        var usedTokens = 0

        for (i in history.indices.reversed()) {
            val turn = history[i]
            val turnTokens = estimateTokens(turn.content) + 4
            if (usedTokens + turnTokens > tokenBudget) break
            kept.add(0, turn)
            usedTokens += turnTokens
        }

        // If we dropped messages, optionally create a summary of the dropped portion
        val droppedCount = history.size - kept.size
        if (droppedCount > 0 && kept.isNotEmpty() && enableSummary) {
            val summary = buildHistorySummary(history, kept.size)
            val summaryTokens = estimateTokens(summary) + 4
            if (usedTokens + summaryTokens <= tokenBudget) {
                val summaryTurn = ChatTurn(
                    role = "system",
                    content = summary,
                )
                return listOf(summaryTurn) + kept
            }
        }

        return kept
    }

    /**
     * Builds a compact summary of the dropped conversation history.
     * Instead of sending full messages, we note what was discussed.
     */
    private fun buildHistorySummary(history: List<ChatTurn>, keptCount: Int): String {
        val dropped = history.dropLast(keptCount)
        val userMessages = dropped.filter { it.role == "user" }
        val assistantMessages = dropped.filter { it.role == "assistant" }

        val sb = StringBuilder()
        sb.append("[Earlier conversation summary — ")
        sb.append("${dropped.size} messages, ${userMessages.size} user, ${assistantMessages.size} assistant]\n")

        // Summarize user requests (first 100 chars each)
        userMessages.take(5).forEach { turn ->
            val snippet = turn.content.take(100)
            sb.append("User asked: $snippet")
            if (turn.content.length > 100) sb.append("...")
            sb.append("\n")
        }
        if (userMessages.size > 5) {
            sb.append("... and ${userMessages.size - 5} more user messages\n")
        }

        // Note assistant responses briefly
        if (assistantMessages.isNotEmpty()) {
            sb.append("Assistant provided ${assistantMessages.size} response(s) with code/explanations.\n")
        }

        return sb.toString()
    }

    private fun truncateText(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (text.length <= maxChars) return text
        if (maxChars <= TRUNCATION_SUFFIX.length) return text.take(maxChars)
        return text.take(maxChars - TRUNCATION_SUFFIX.length) + TRUNCATION_SUFFIX
    }
}

/**
 * Result of context management — the trimmed components ready for prompt formatting.
 */
data class ManagedContext(
    val systemPrompt: String,
    val history: List<ChatTurn>,
    val userMessage: String,
    val codeContext: String,
)
