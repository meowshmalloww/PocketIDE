package com.pocketide.data.repository

import android.content.Context
import com.pocketide.data.model.AgentStatus
import com.pocketide.data.model.ChatMessage
import com.pocketide.data.model.ChatSession
import com.pocketide.data.model.ChatSessionSummary
import com.pocketide.data.model.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ChatHistoryRepository(context: Context) {

    private val historyDir = File(context.filesDir, "chat_history").apply { mkdirs() }

    suspend fun save(
        id: String,
        projectName: String,
        messages: List<ChatMessage>,
    ): ChatSessionSummary = withContext(Dispatchers.IO) {
        val updatedAt = System.currentTimeMillis()
        val title = messages.firstOrNull { it.role == MessageRole.USER }
            ?.content
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            ?.take(56)
            ?.ifBlank { null }
            ?: "New conversation"
        val summary = ChatSessionSummary(id, title, projectName, updatedAt, messages.size)
        val root = JSONObject()
            .put("id", id)
            .put("title", title)
            .put("projectName", projectName)
            .put("updatedAt", updatedAt)
            .put("messages", JSONArray().apply { messages.forEach { put(it.toJson()) } })
        val target = File(historyDir, "$id.json")
        val temporary = File(historyDir, "$id.json.tmp")
        temporary.writeText(root.toString())
        runCatching {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        prune()
        summary
    }

    suspend fun list(): List<ChatSessionSummary> = withContext(Dispatchers.IO) {
        historyDir.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { file -> runCatching { readSummary(file) }.getOrNull() }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }

    suspend fun load(id: String): ChatSession? = withContext(Dispatchers.IO) {
        val file = File(historyDir, "$id.json")
        if (!file.exists()) return@withContext null
        runCatching {
            val root = JSONObject(file.readText())
            val array = root.getJSONArray("messages")
            val messages = buildList {
                for (index in 0 until array.length()) add(array.getJSONObject(index).toMessage())
            }
            ChatSession(
                summary = ChatSessionSummary(
                    id = root.getString("id"),
                    title = root.optString("title", "Conversation"),
                    projectName = root.optString("projectName", "default"),
                    updatedAt = root.optLong("updatedAt", file.lastModified()),
                    messageCount = messages.size,
                ),
                messages = messages,
            )
        }.getOrNull()
    }

    suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        File(historyDir, "$id.json").delete()
    }

    private fun readSummary(file: File): ChatSessionSummary {
        val root = JSONObject(file.readText())
        return ChatSessionSummary(
            id = root.getString("id"),
            title = root.optString("title", "Conversation"),
            projectName = root.optString("projectName", "default"),
            updatedAt = root.optLong("updatedAt", file.lastModified()),
            messageCount = root.optJSONArray("messages")?.length() ?: 0,
        )
    }

    private fun prune() {
        historyDir.listFiles { file -> file.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_SESSIONS)
            ?.forEach { it.delete() }
    }

    private fun ChatMessage.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("role", role.name)
        .put("content", content)
        .put("timestamp", timestamp)
        .put("agentStatus", agentStatus?.name ?: JSONObject.NULL)
        .put("tokensPerSecond", tokensPerSecond ?: JSONObject.NULL)
        .put("isThinking", isThinking)
        .put("ttftMs", ttftMs ?: JSONObject.NULL)
        .put("memoryDeltaMb", memoryDeltaMb ?: JSONObject.NULL)
        .put("strategy", strategy ?: JSONObject.NULL)

    private fun JSONObject.toMessage() = ChatMessage(
        id = optString("id", java.util.UUID.randomUUID().toString()),
        role = runCatching { MessageRole.valueOf(getString("role")) }.getOrDefault(MessageRole.ASSISTANT),
        content = optString("content", ""),
        timestamp = optLong("timestamp", System.currentTimeMillis()),
        agentStatus = nullableString("agentStatus")?.let { runCatching { AgentStatus.valueOf(it) }.getOrNull() },
        tokensPerSecond = nullableDouble("tokensPerSecond")?.toFloat(),
        isThinking = optBoolean("isThinking", false),
        ttftMs = nullableLong("ttftMs"),
        memoryDeltaMb = nullableDouble("memoryDeltaMb")?.toFloat(),
        strategy = nullableString("strategy"),
    )

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)

    private fun JSONObject.nullableDouble(key: String): Double? =
        if (!has(key) || isNull(key)) null else getDouble(key)

    private fun JSONObject.nullableLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else getLong(key)

    companion object {
        private const val MAX_SESSIONS = 30
    }
}
