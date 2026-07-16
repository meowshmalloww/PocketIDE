package com.pocketide.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageCapabilityTest {

    @Test
    fun `every declared language has an honest capability`() {
        val expected = mapOf(
            Language.PYTHON to ExecutionSupport.RUNTIME,
            Language.JAVASCRIPT to ExecutionSupport.RUNTIME,
            Language.TYPESCRIPT to ExecutionSupport.COMPATIBILITY,
            Language.KOTLIN to ExecutionSupport.EDITOR_ONLY,
            Language.DART to ExecutionSupport.EDITOR_ONLY,
            Language.SQL to ExecutionSupport.RUNTIME,
            Language.HTML to ExecutionSupport.PREVIEW,
            Language.CSS to ExecutionSupport.PREVIEW,
            Language.JAVA to ExecutionSupport.COMPATIBILITY,
            Language.LUA to ExecutionSupport.RUNTIME,
            Language.SHELL to ExecutionSupport.RUNTIME,
            Language.YAML to ExecutionSupport.EDITOR_ONLY,
            Language.MARKDOWN to ExecutionSupport.EDITOR_ONLY,
            Language.JSON to ExecutionSupport.EDITOR_ONLY,
        )

        assertEquals(Language.entries.size, expected.size)
        Language.entries.forEach { language ->
            assertEquals("Incorrect capability for ${language.displayName}", expected[language], language.executionSupport)
        }
    }

    @Test
    fun `web and hardware flags match implemented bridges`() {
        assertEquals(
            setOf(Language.JAVASCRIPT, Language.TYPESCRIPT, Language.HTML, Language.CSS),
            Language.entries.filter { it.supportsWebPreview }.toSet(),
        )
        assertEquals(
            setOf(Language.PYTHON, Language.JAVASCRIPT, Language.TYPESCRIPT, Language.JAVA, Language.LUA),
            Language.entries.filter { it.supportsHardwareBridge }.toSet(),
        )
        assertTrue(Language.HTML.executionSupport == ExecutionSupport.PREVIEW)
        assertFalse(Language.KOTLIN.supportsWebPreview)
    }
}
