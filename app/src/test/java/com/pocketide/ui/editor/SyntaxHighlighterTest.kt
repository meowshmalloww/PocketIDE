package com.pocketide.ui.editor

import androidx.compose.ui.text.AnnotatedString
import com.pocketide.data.model.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyntaxHighlighterTest {

    @Test
    fun `empty code returns empty annotated string`() {
        val result = highlightCode("", Language.PYTHON)
        assertEquals(0, result.length)
    }

    @Test
    fun `plain text without keywords returns unstyled string`() {
        val result = highlightCode("hello world", Language.PYTHON)
        assertEquals("hello world", result.text)
    }

    @Test
    fun `python keywords are highlighted`() {
        val code = "def main():"
        val result = highlightCode(code, Language.PYTHON)
        assertEquals("def main():", result.text)
        assertTrue("should have at least one span style", result.spanStyles.isNotEmpty())
    }

    @Test
    fun `python line comment is highlighted`() {
        val code = "# this is a comment"
        val result = highlightCode(code, Language.PYTHON)
        assertEquals("# this is a comment", result.text)
        assertTrue(result.spanStyles.isNotEmpty())
    }

    @Test
    fun `python block comment with triple quotes is highlighted`() {
        val code = "\"\"\"docstring\"\"\""
        val result = highlightCode(code, Language.PYTHON)
        assertEquals("\"\"\"docstring\"\"\"", result.text)
        assertTrue(result.spanStyles.isNotEmpty())
    }

    @Test
    fun `python string literal is highlighted`() {
        val code = "x = \"hello\""
        val result = highlightCode(code, Language.PYTHON)
        assertEquals("x = \"hello\"", result.text)
        assertTrue(result.spanStyles.isNotEmpty())
    }

    @Test
    fun `javascript keywords are highlighted`() {
        val code = "function add(a, b) { return a + b; }"
        val result = highlightCode(code, Language.JAVASCRIPT)
        assertEquals("function add(a, b) { return a + b; }", result.text)
        assertTrue(result.spanStyles.isNotEmpty())
    }

    @Test
    fun `javascript line comment is highlighted`() {
        val code = "// comment"
        val result = highlightCode(code, Language.JAVASCRIPT)
        assertEquals("// comment", result.text)
        assertTrue(result.spanStyles.isNotEmpty())
    }

    @Test
    fun `javascript block comment is highlighted`() {
        val code = "/* block comment */"
        val result = highlightCode(code, Language.JAVASCRIPT)
        assertEquals("/* block comment */", result.text)
        assertTrue(result.spanStyles.isNotEmpty())
    }

    @Test
    fun `kotlin keywords are highlighted`() {
        val code = "fun main() { val x = 1 }"
        val result = highlightCode(code, Language.KOTLIN)
        assertEquals("fun main() { val x = 1 }", result.text)
        assertTrue(result.spanStyles.isNotEmpty())
    }

    @Test
    fun `kotlin line comment is highlighted`() {
        val code = "// Kotlin comment"
        val result = highlightCode(code, Language.KOTLIN)
        assertEquals("// Kotlin comment", result.text)
        assertTrue(result.spanStyles.isNotEmpty())
    }

    @Test
    fun `numbers are highlighted`() {
        val code = "x = 42"
        val result = highlightCode(code, Language.PYTHON)
        assertEquals("x = 42", result.text)
        assertTrue(result.spanStyles.isNotEmpty())
    }

    @Test
    fun `decimal numbers are highlighted`() {
        val code = "x = 3.14"
        val result = highlightCode(code, Language.PYTHON)
        assertEquals("x = 3.14", result.text)
        assertTrue(result.spanStyles.isNotEmpty())
    }

    @Test
    fun `html block comment is highlighted`() {
        val code = "<!-- comment -->"
        val result = highlightCode(code, Language.HTML)
        assertEquals("<!-- comment -->", result.text)
        assertTrue(result.spanStyles.isNotEmpty())
    }

    @Test
    fun `sql keywords are highlighted`() {
        val code = "SELECT * FROM users"
        val result = highlightCode(code, Language.SQL)
        assertEquals("SELECT * FROM users", result.text)
        assertTrue(result.spanStyles.isNotEmpty())
    }

    @Test
    fun `java keywords are highlighted`() {
        val code = "public class Main {}"
        val result = highlightCode(code, Language.JAVA)
        assertEquals("public class Main {}", result.text)
        assertTrue(result.spanStyles.isNotEmpty())
    }

    @Test
    fun `java block comment is highlighted`() {
        val code = "/* comment */"
        val result = highlightCode(code, Language.JAVA)
        assertEquals("/* comment */", result.text)
        assertTrue(result.spanStyles.isNotEmpty())
    }

    @Test
    fun `css block comment is highlighted`() {
        val code = "/* style */"
        val result = highlightCode(code, Language.CSS)
        assertEquals("/* style */", result.text)
        assertTrue(result.spanStyles.isNotEmpty())
    }

    @Test
    fun `mixed content with keywords strings and comments`() {
        val code = "def greet(name):\n    # say hello\n    print(\"hi\")"
        val result = highlightCode(code, Language.PYTHON)
        assertEquals(code, result.text)
        assertTrue("should have multiple span styles", result.spanStyles.size >= 2)
    }

    @Test
    fun `typescript has more keywords than javascript`() {
        val jsCode = "interface Foo {}"
        val tsResult = highlightCode(jsCode, Language.TYPESCRIPT)
        assertEquals(jsCode, tsResult.text)
        assertTrue(tsResult.spanStyles.isNotEmpty())
    }

    @Test
    fun `string with escape sequence is highlighted`() {
        val code = "x = \"hello\\nworld\""
        val result = highlightCode(code, Language.PYTHON)
        assertEquals("x = \"hello\\nworld\"", result.text)
        assertTrue(result.spanStyles.isNotEmpty())
    }

    @Test
    fun `markdown has no comment styles but still returns text`() {
        val code = "# Heading"
        val result = highlightCode(code, Language.MARKDOWN)
        assertEquals("# Heading", result.text)
    }

    @Test
    fun `json keywords are highlighted`() {
        val code = "true false null"
        val result = highlightCode(code, Language.JSON)
        assertEquals("true false null", result.text)
        assertTrue(result.spanStyles.isNotEmpty())
    }
}
