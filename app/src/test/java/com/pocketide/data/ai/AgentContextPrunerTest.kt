package com.pocketide.data.ai

import com.pocketide.data.model.CodeFile
import com.pocketide.data.model.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextPrunerTest {

    private val testFiles = listOf(
        CodeFile(name = "main.py", language = Language.PYTHON, content = "print('hello')\n".repeat(30)),
        CodeFile(name = "utils.lua", language = Language.LUA, content = "local x = 1\n".repeat(20)),
    )

    private val testHistory = listOf(
        ChatTurn(role = "user", content = "Create a hello world app"),
        ChatTurn(role = "assistant", content = "I will create main.py with a print statement."),
        ChatTurn(role = "user", content = "Now add a utility file"),
        ChatTurn(role = "assistant", content = "I will create utils.lua."),
        ChatTurn(role = "user", content = "Make it print numbers 1-10"),
    )

    @Test
    fun `pruneForArchitect includes file summaries but not full content`() {
        val managed = AgentContextPruner.pruneForArchitect(
            systemPrompt = "You are the Architect",
            history = testHistory,
            userMessage = "Add a feature",
            files = testFiles,
            contextWindowSize = 4096,
        )

        assertTrue(managed.systemPrompt.contains("PROJECT FILES"))
        assertTrue(managed.systemPrompt.contains("main.py"))
        assertTrue(managed.systemPrompt.contains("utils.lua"))
        assertTrue(managed.codeContext.contains("more lines"))
        // History should be limited to 4 turns
        assertTrue(managed.history.size <= 4)
    }

    @Test
    fun `pruneForCoder includes active file full content`() {
        val managed = AgentContextPruner.pruneForCoder(
            systemPrompt = "You are the Coder",
            history = testHistory,
            userMessage = "Implement the feature",
            files = testFiles,
            contextWindowSize = 4096,
            activeFileIndex = 0,
        )

        assertTrue(managed.systemPrompt.contains("CURRENT PROJECT FILES"))
        assertTrue(managed.systemPrompt.contains("main.py"))
        assertTrue(managed.systemPrompt.contains("active"))
        // History should be limited to 3 turns
        assertTrue(managed.history.size <= 3)
    }

    @Test
    fun `pruneForValidator includes error output in system prompt`() {
        val errorOutput = "SyntaxError: invalid syntax at line 5"
        val managed = AgentContextPruner.pruneForValidator(
            systemPrompt = "You are the Validator",
            history = testHistory,
            userMessage = "Fix the error",
            files = testFiles,
            contextWindowSize = 4096,
            errorOutput = errorOutput,
        )

        assertTrue(managed.systemPrompt.contains("VALIDATION ERROR"))
        assertTrue(managed.systemPrompt.contains(errorOutput))
        // History should be limited to 2 turns
        assertTrue(managed.history.size <= 2)
    }

    @Test
    fun `pruneForArchitect with empty files returns empty code context`() {
        val managed = AgentContextPruner.pruneForArchitect(
            systemPrompt = "You are the Architect",
            history = emptyList(),
            userMessage = "Plan something",
            files = emptyList(),
            contextWindowSize = 4096,
        )

        assertTrue(managed.codeContext.isEmpty())
        assertEquals(0, managed.history.size)
    }

    @Test
    fun `pruneForCoder with empty files returns empty code context`() {
        val managed = AgentContextPruner.pruneForCoder(
            systemPrompt = "You are the Coder",
            history = emptyList(),
            userMessage = "Write something",
            files = emptyList(),
            contextWindowSize = 4096,
            activeFileIndex = 0,
        )

        assertTrue(managed.codeContext.isEmpty())
        assertEquals(0, managed.history.size)
    }
}
