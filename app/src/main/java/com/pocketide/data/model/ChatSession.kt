package com.pocketide.data.model

data class ChatSessionSummary(
    val id: String,
    val title: String,
    val projectName: String,
    val updatedAt: Long,
    val messageCount: Int,
)

data class ChatSession(
    val summary: ChatSessionSummary,
    val messages: List<ChatMessage>,
)
