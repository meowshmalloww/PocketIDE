package com.pocketide.data.ai

import com.pocketide.data.model.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiResponseParserTest {

    @Test
    fun `parses full response with plan filename and code block`() {
        val raw = """
            PLAN: Create a hello world script
            FILENAME: main.py
            ```python
            print("Hello, World!")
            ```
        """.trimIndent()

        val parsed = parseAiResponse(raw)

        assertEquals("Create a hello world script", parsed.plan)
        assertEquals("main.py", parsed.filename)
        assertEquals("print(\"Hello, World!\")", parsed.code)
        assertEquals(Language.PYTHON, parsed.language)
    }

    @Test
    fun `parses JavaScript code block with js tag`() {
        val raw = """
            PLAN: Write a JS function
            FILENAME: utils.js
            ```js
            console.log("hi");
            ```
        """.trimIndent()

        val parsed = parseAiResponse(raw)

        assertEquals(Language.JAVASCRIPT, parsed.language)
        assertEquals("console.log(\"hi\");", parsed.code)
    }

    @Test
    fun `parses TypeScript code block with ts tag`() {
        val raw = """
            PLAN: TS type
            FILENAME: types.ts
            ```ts
            const x: number = 42;
            ```
        """.trimIndent()

        val parsed = parseAiResponse(raw)

        assertEquals(Language.TYPESCRIPT, parsed.language)
    }

    @Test
    fun `parses Kotlin code block with kt tag`() {
        val raw = """
            PLAN: Kotlin function
            FILENAME: Main.kt
            ```kt
            fun main() = println("hi")
            ```
        """.trimIndent()

        val parsed = parseAiResponse(raw)

        assertEquals(Language.KOTLIN, parsed.language)
    }

    @Test
    fun `falls back to filename extension when language tag unrecognized`() {
        val raw = """
            PLAN: Create a script
            FILENAME: script.lua
            ```text
            print("hello")
            ```
        """.trimIndent()

        val parsed = parseAiResponse(raw)

        assertEquals(Language.LUA, parsed.language)
        assertEquals("script.lua", parsed.filename)
    }

    @Test
    fun `returns nulls for empty response`() {
        val parsed = parseAiResponse("")

        assertNull(parsed.plan)
        assertNull(parsed.code)
        assertNull(parsed.language)
        assertNull(parsed.filename)
    }

    @Test
    fun `returns null code when no code block present`() {
        val raw = """
            PLAN: Just a plan
            FILENAME: main.py
        """.trimIndent()

        val parsed = parseAiResponse(raw)

        assertEquals("Just a plan", parsed.plan)
        assertEquals("main.py", parsed.filename)
        assertNull(parsed.code)
    }

    @Test
    fun `parses code block without plan or filename`() {
        val raw = """
            ```java
            System.out.println("hi");
            ```
        """.trimIndent()

        val parsed = parseAiResponse(raw)

        assertNull(parsed.plan)
        assertNull(parsed.filename)
        assertEquals(Language.JAVA, parsed.language)
        assertEquals("System.out.println(\"hi\");", parsed.code)
    }

    @Test
    fun `parses shell code block with bash tag`() {
        val raw = """
            PLAN: Shell script
            FILENAME: deploy.sh
            ```bash
            echo "deploying"
            ```
        """.trimIndent()

        val parsed = parseAiResponse(raw)

        assertEquals(Language.SHELL, parsed.language)
    }

    @Test
    fun `parses JSON code block`() {
        val raw = """
            PLAN: Config file
            FILENAME: config.json
            ```json
            {"key": "value"}
            ```
        """.trimIndent()

        val parsed = parseAiResponse(raw)

        assertEquals(Language.JSON, parsed.language)
    }

    @Test
    fun `parses SQL code block`() {
        val raw = """
            PLAN: Query
            FILENAME: query.sql
            ```sql
            SELECT * FROM users;
            ```
        """.trimIndent()

        val parsed = parseAiResponse(raw)

        assertEquals(Language.SQL, parsed.language)
    }

    @Test
    fun `handles multiline code block`() {
        val raw = """
            PLAN: Multi-line function
            FILENAME: app.py
            ```python
            def greet(name):
                print(f"Hello, {name}!")
                return True
            ```
        """.trimIndent()

        val parsed = parseAiResponse(raw)

        assertEquals(Language.PYTHON, parsed.language)
        assertEquals("def greet(name):\n    print(f\"Hello, {name}!\")\n    return True", parsed.code)
    }

    @Test
    fun `handles code block with empty language tag`() {
        val raw = """
            PLAN: Generic code
            FILENAME: script.py
            ```
            x = 1
            ```
        """.trimIndent()

        val parsed = parseAiResponse(raw)

        assertEquals(Language.PYTHON, parsed.language)
        assertEquals("x = 1", parsed.code)
    }

    @Test
    fun `trims whitespace in plan`() {
        val raw = """
            PLAN:    Trimmed plan   
            FILENAME: main.py
            ```python
            pass
            ```
        """.trimIndent()

        val parsed = parseAiResponse(raw)

        assertEquals("Trimmed plan", parsed.plan)
    }
}
