package com.pocketide.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 60_000

sealed class AiResult {
    data class Success(val content: String) : AiResult()
    data class Error(val message: String) : AiResult()
}

data class ChatTurn(val role: String, val content: String)

/**
 * Talks to any OpenAI-compatible chat completions endpoint. Requires the
 * user to configure a base URL, API key, and model in Settings. No output
 * is fabricated: network or parsing failures surface as [AiResult.Error].
 */
class AiService(private val config: AiConfig) {

    suspend fun chatCompletion(systemPrompt: String, history: List<ChatTurn>, userMessage: String): AiResult =
        withContext(Dispatchers.IO) {
            if (!config.isConfigured) {
                return@withContext AiResult.Error(
                    "AI is not configured. Set the API base URL, key, and model in Settings.",
                )
            }

            val url = try {
                URL("${config.baseUrl.trimEnd('/')}/chat/completions")
            } catch (e: Exception) {
                return@withContext AiResult.Error("Invalid base URL: ${e.message}")
            }

            var connection: HttpURLConnection? = null
            try {
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                }

                val messages = JSONArray()
                messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
                for (turn in history) {
                    messages.put(JSONObject().put("role", turn.role).put("content", turn.content))
                }
                messages.put(JSONObject().put("role", "user").put("content", userMessage))

                val body = JSONObject()
                    .put("model", config.model)
                    .put("messages", messages)
                    .put("temperature", 0.3)

                OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
                    writer.write(body.toString())
                }

                val statusCode = connection.responseCode
                val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
                val responseText = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }

                if (statusCode !in 200..299) {
                    val errorMessage = try {
                        JSONObject(responseText).optJSONObject("error")?.optString("message")
                    } catch (_: Exception) {
                        null
                    } ?: responseText
                    return@withContext AiResult.Error("API error ($statusCode): $errorMessage")
                }

                val json = JSONObject(responseText)
                val content = json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")

                if (content.isNullOrBlank()) {
                    AiResult.Error("Received an empty response from the model.")
                } else {
                    AiResult.Success(content)
                }
            } catch (e: Exception) {
                AiResult.Error(e.message ?: "Network request failed: ${e.javaClass.simpleName}")
            } finally {
                connection?.disconnect()
            }
        }
}
