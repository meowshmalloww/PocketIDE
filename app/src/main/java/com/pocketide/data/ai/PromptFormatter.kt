package com.pocketide.data.ai

/**
 * Formats system prompt + chat history + user message into the chat template
 * expected by the loaded on-device model.
 *
 * Templates are model-family specific. Using the wrong template will produce
 * degraded outputs (repetition, no stop token, format drift).
 *
 * References:
 *  - Llama 3 / 3.2 Instruct:
 *    https://llama.meta.com/docs/model-cards-and-prompt-formats/llama3_2
 *  - Qwen 2.5 / Qwen 3 ChatML:
 *    https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct#quickstart
 */
object PromptFormatter {

    fun format(
        template: PromptTemplate,
        systemPrompt: String,
        history: List<ChatTurn>,
        userMessage: String,
    ): String = when (template) {
        PromptTemplate.AUTO -> error("AUTO prompt template must be resolved before formatting")
        PromptTemplate.LLAMA3 -> formatLlama3(systemPrompt, history, userMessage)
        PromptTemplate.QWEN -> formatQwen(systemPrompt, history, userMessage)
        PromptTemplate.PLAIN -> formatPlain(systemPrompt, history, userMessage)
    }

    private fun formatLlama3(
        systemPrompt: String,
        history: List<ChatTurn>,
        userMessage: String,
    ): String {
        val sb = StringBuilder()
        sb.append("<|begin_of_text|>")
        if (systemPrompt.isNotBlank()) {
            sb.append("<|start_header_id|>system<|end_header_id|>\n\n")
            sb.append(systemPrompt.trim())
            sb.append("<|eot_id|>")
        }
        for (turn in history) {
            val header = if (turn.role == "user") "user" else "assistant"
            sb.append("<|start_header_id|>").append(header).append("<|end_header_id|>\n\n")
            sb.append(turn.content.trim())
            sb.append("<|eot_id|>")
        }
        sb.append("<|start_header_id|>user<|end_header_id|>\n\n")
        sb.append(userMessage.trim())
        sb.append("<|eot_id|>")
        sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        return sb.toString()
    }

    private fun formatQwen(
        systemPrompt: String,
        history: List<ChatTurn>,
        userMessage: String,
    ): String {
        val sb = StringBuilder()
        if (systemPrompt.isNotBlank()) {
            sb.append("<|im_start|>system\n").append(systemPrompt.trim()).append("<|im_end|>\n")
        }
        for (turn in history) {
            val role = if (turn.role == "user") "user" else "assistant"
            sb.append("<|im_start|>").append(role).append('\n')
            sb.append(turn.content.trim())
            sb.append("<|im_end|>\n")
        }
        sb.append("<|im_start|>user\n").append(userMessage.trim()).append("<|im_end|>\n")
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    private fun formatPlain(
        systemPrompt: String,
        history: List<ChatTurn>,
        userMessage: String,
    ): String {
        val sb = StringBuilder()
        if (systemPrompt.isNotBlank()) {
            sb.append(systemPrompt.trim()).append("\n\n")
        }
        for (turn in history) {
            val prefix = if (turn.role == "user") "User: " else "Assistant: "
            sb.append(prefix).append(turn.content.trim()).append("\n\n")
        }
        sb.append("User: ").append(userMessage.trim()).append("\n\n")
        sb.append("Assistant: ")
        return sb.toString()
    }
}
