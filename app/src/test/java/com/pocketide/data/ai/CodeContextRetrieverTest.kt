package com.pocketide.data.ai

import com.pocketide.data.model.CodeFile
import com.pocketide.data.model.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeContextRetrieverTest {
    @Test
    fun `query retrieves a definition near the end of another file`() {
        val files = listOf(
            CodeFile(
                name = "main.py",
                language = Language.PYTHON,
                content = "from storage import save_inventory\nprint('ready')",
            ),
            CodeFile(
                name = "storage.py",
                language = Language.PYTHON,
                content = (1..240).joinToString("\n") { "padding_$it = $it" } +
                    "\n\ndef save_inventory(items):\n    return len(items)",
            ),
        )

        val result = CodeContextRetriever.retrieve(
            files = files,
            activeFileIndex = 0,
            query = "Fix save_inventory in storage.py",
            tokenBudget = 900,
        )

        assertTrue(result.contextText.contains("def save_inventory"))
        assertTrue(result.contextText.contains("main.py (active)"))
        assertEquals(2, result.scannedFiles)
        assertTrue(result.indexedSourceTokens > result.retrievedTokens)
    }

    @Test
    fun `retrieval is deterministic and respects token budget`() {
        val files = (1..12).map { index ->
            CodeFile(
                name = "module$index.kt",
                language = Language.KOTLIN,
                content = "fun operation$index(value: Int) = value + $index\n".repeat(80),
            )
        }

        val first = CodeContextRetriever.retrieve(files, 0, "operation11", 500)
        val second = CodeContextRetriever.retrieve(files, 0, "operation11", 500)

        assertEquals(first, second)
        assertTrue(first.retrievedTokens <= 500)
        assertTrue(first.contextText.contains("module11.kt"))
        assertFalse(first.contextText.contains("indexed project size is the model context size"))
    }

    @Test
    fun `large indexed project is reported separately from selected chunks`() {
        val files = (1..20).map { index ->
            CodeFile(
                name = "file$index.py",
                language = Language.PYTHON,
                content = "value_$index = $index\n".repeat(400),
            )
        }

        val result = CodeContextRetriever.retrieve(files, 0, "value_19", 700)

        assertTrue(result.indexedSourceTokens > 20_000)
        assertTrue(result.retrievedTokens <= 700)
        assertTrue(result.selectedChunks > 0)
        assertTrue(result.contextText.contains("PROJECT RETRIEVAL"))
    }
}
