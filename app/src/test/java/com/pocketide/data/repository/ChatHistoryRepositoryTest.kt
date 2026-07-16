package com.pocketide.data.repository

import com.pocketide.data.model.AgentStatus
import com.pocketide.data.model.ChatMessage
import com.pocketide.data.model.MessageRole
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatHistoryRepositoryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var repository: ChatHistoryRepository

    @Before
    fun setUp() {
        File(context.filesDir, "chat_history").deleteRecursively()
        repository = ChatHistoryRepository(context)
    }

    @After
    fun tearDown() {
        File(context.filesDir, "chat_history").deleteRecursively()
    }

    @Test
    fun `saves lists reloads and deletes a conversation`() = runBlocking {
        val messages = listOf(
            ChatMessage(role = MessageRole.USER, content = "Build a local notes app"),
            ChatMessage(
                role = MessageRole.ASSISTANT,
                content = "Here is the plan",
                agentStatus = AgentStatus.DONE,
                tokensPerSecond = 7.5f,
            ),
        )

        val summary = repository.save("session-1", "demo", messages)
        assertEquals("Build a local notes app", summary.title)
        assertEquals(1, repository.list().size)

        val loaded = repository.load("session-1")
        assertNotNull(loaded)
        assertEquals(messages.map { it.content }, loaded!!.messages.map { it.content })
        assertEquals(AgentStatus.DONE, loaded.messages[1].agentStatus)

        repository.delete("session-1")
        assertEquals(emptyList<Any>(), repository.list())
    }
}
