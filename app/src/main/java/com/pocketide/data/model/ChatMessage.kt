package com.pocketide.data.model

enum class MessageRole {
    USER,
    ASSISTANT,
    ARCHITECT,
    CODER,
    VALIDATOR,
    SYSTEM,
}

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val agentStatus: AgentStatus? = null,
)
