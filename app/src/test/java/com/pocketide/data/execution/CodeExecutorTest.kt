package com.pocketide.data.execution

import com.pocketide.data.model.ExecutionStatus
import com.pocketide.data.model.Language
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeExecutorTest {

    private val executor = CodeExecutor()

    @Test
    fun `executes simple console log`() = runBlocking {
        val result = executor.execute("console.log('hello');", Language.JAVASCRIPT)

        assertEquals(ExecutionStatus.PASSED, result.status)
        assertEquals("hello\n", result.stdout)
        assertEquals("", result.stderr)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `executes multiple console log calls`() = runBlocking {
        val result = executor.execute(
            "console.log('line1');\nconsole.log('line2');\nconsole.log('line3');",
            Language.JAVASCRIPT,
        )

        assertEquals(ExecutionStatus.PASSED, result.status)
        assertEquals("line1\nline2\nline3\n", result.stdout)
    }

    @Test
    fun `console log with multiple arguments`() = runBlocking {
        val result = executor.execute("console.log('a', 'b', 'c');", Language.JAVASCRIPT)

        assertEquals(ExecutionStatus.PASSED, result.status)
        assertEquals("a b c\n", result.stdout)
    }

    @Test
    fun `executes variable declarations and arithmetic`() = runBlocking {
        val result = executor.execute(
            "var x = 10; var y = 20; console.log(x + y);",
            Language.JAVASCRIPT,
        )

        assertEquals(ExecutionStatus.PASSED, result.status)
        assertEquals("30\n", result.stdout)
    }

    @Test
    fun `executes function definitions and calls`() = runBlocking {
        val result = executor.execute(
            """
            function add(a, b) { return a + b; }
            console.log(add(3, 4));
            """.trimIndent(),
            Language.JAVASCRIPT,
        )

        assertEquals(ExecutionStatus.PASSED, result.status)
        assertEquals("7\n", result.stdout)
    }

    @Test
    fun `executes array operations`() = runBlocking {
        val result = executor.execute(
            "var arr = [1, 2, 3]; console.log(arr.length);",
            Language.JAVASCRIPT,
        )

        assertEquals(ExecutionStatus.PASSED, result.status)
        assertEquals("3\n", result.stdout)
    }

    @Test
    fun `executes object literals`() = runBlocking {
        val result = executor.execute(
            "var obj = {name: 'test', value: 42}; console.log(obj.name);",
            Language.JAVASCRIPT,
        )

        assertEquals(ExecutionStatus.PASSED, result.status)
        assertEquals("test\n", result.stdout)
    }

    @Test
    fun `returns failed for syntax error`() = runBlocking {
        val result = executor.execute("console.log(;", Language.JAVASCRIPT)

        assertEquals(ExecutionStatus.FAILED, result.status)
        assertTrue("stderr should not be empty", result.stderr.isNotBlank())
        assertEquals(1, result.exitCode)
    }

    @Test
    fun `returns failed for runtime error`() = runBlocking {
        val result = executor.execute(
            "var x = undefined; x.foo.bar;",
            Language.JAVASCRIPT,
        )

        assertEquals(ExecutionStatus.FAILED, result.status)
        assertTrue("stderr should contain error info", result.stderr.isNotBlank())
    }

    @Test
    fun `returns failed for unsupported language`() = runBlocking {
        val result = executor.execute("print('hello')", Language.PYTHON)

        assertEquals(ExecutionStatus.FAILED, result.status)
        assertTrue("stderr should mention not supported", result.stderr.contains("not yet supported"))
        assertEquals(-1, result.exitCode)
    }

    @Test
    fun `returns failed for TypeScript`() = runBlocking {
        val result = executor.execute("const x: number = 1;", Language.TYPESCRIPT)

        assertEquals(ExecutionStatus.FAILED, result.status)
        assertTrue(result.stderr.contains("not yet supported"))
    }

    @Test
    fun `returns failed for Kotlin`() = runBlocking {
        val result = executor.execute("fun main() {}", Language.KOTLIN)

        assertEquals(ExecutionStatus.FAILED, result.status)
        assertTrue(result.stderr.contains("not yet supported"))
    }

    @Test
    fun `empty code passes with no output`() = runBlocking {
        val result = executor.execute("", Language.JAVASCRIPT)

        assertEquals(ExecutionStatus.PASSED, result.status)
        assertEquals("", result.stdout)
    }

    @Test
    fun `executes while loop within instruction limit`() = runBlocking {
        val result = executor.execute(
            "var i = 0; while (i < 100) { i++; } console.log(i);",
            Language.JAVASCRIPT,
        )

        assertEquals(ExecutionStatus.PASSED, result.status)
        assertEquals("100\n", result.stdout)
    }

    @Test
    fun `executes for loop`() = runBlocking {
        val result = executor.execute(
            """
            var sum = 0;
            for (var i = 1; i <= 10; i++) { sum += i; }
            console.log(sum);
            """.trimIndent(),
            Language.JAVASCRIPT,
        )

        assertEquals(ExecutionStatus.PASSED, result.status)
        assertEquals("55\n", result.stdout)
    }

    @Test
    fun `captures stdout before runtime error`() = runBlocking {
        val result = executor.execute(
            "console.log('before error');\nundefined.foo;",
            Language.JAVASCRIPT,
        )

        assertEquals(ExecutionStatus.FAILED, result.status)
        assertEquals("before error\n", result.stdout)
        assertTrue(result.stderr.isNotBlank())
    }

    @Test
    fun `duration is non-negative`() = runBlocking {
        val result = executor.execute("console.log('hi');", Language.JAVASCRIPT)

        assertTrue("duration should be >= 0", result.durationMs >= 0)
    }
}
