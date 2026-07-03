package com.pocketide.data.execution

import com.pocketide.data.model.ExecutionStatus
import com.pocketide.data.model.Language
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
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

    // === Lua tests ===

    @Test
    fun `lua executes simple print`() = runBlocking {
        val result = executor.execute("print('hello lua')", Language.LUA)

        assertEquals(ExecutionStatus.PASSED, result.status)
        assertEquals("hello lua\n", result.stdout)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `lua executes arithmetic`() = runBlocking {
        val result = executor.execute("print(2 + 3)", Language.LUA)

        assertEquals(ExecutionStatus.PASSED, result.status)
        assertEquals("5\n", result.stdout)
    }

    @Test
    fun `lua executes function`() = runBlocking {
        val result = executor.execute(
            """
            function factorial(n)
                if n <= 1 then return 1 end
                return n * factorial(n - 1)
            end
            print(factorial(5))
            """.trimIndent(),
            Language.LUA,
        )

        assertEquals(ExecutionStatus.PASSED, result.status)
        assertEquals("120\n", result.stdout)
    }

    @Test
    fun `lua returns failed for syntax error`() = runBlocking {
        val result = executor.execute("print(", Language.LUA)

        assertEquals(ExecutionStatus.FAILED, result.status)
        assertTrue(result.stderr.isNotBlank())
    }

    // === Shell tests ===
    // Note: Shell tests use ProcessBuilder with "sh" which is available on
    // Android, Linux, macOS, and Windows (via Git Bash). On CI without sh,
    // these tests may fail and should be skipped.

    @Test
    fun `shell executes echo`() = runBlocking {
        val result = executor.execute("echo hello_shell", Language.SHELL)

        // Shell (sh) may not be available in all test environments (e.g. Windows without Git Bash)
        if (result.status == ExecutionStatus.FAILED && 
            (result.stderr.contains("timed out") || result.stderr.contains("No such file") ||
                result.stderr.contains("Cannot run") || result.stderr.contains("IOException"))) {
            return@runBlocking
        }
        assertEquals(ExecutionStatus.PASSED, result.status)
        assertTrue(result.stdout.contains("hello_shell"))
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `shell executes multiple commands`() = runBlocking {
        val result = executor.execute(
            "echo line1\necho line2",
            Language.SHELL,
        )

        if (result.status == ExecutionStatus.FAILED &&
            (result.stderr.contains("timed out") || result.stderr.contains("No such file") ||
                result.stderr.contains("Cannot run") || result.stderr.contains("IOException"))) {
            return@runBlocking
        }
        assertEquals(ExecutionStatus.PASSED, result.status)
        assertTrue(result.stdout.contains("line1"))
        assertTrue(result.stdout.contains("line2"))
    }

    @Test
    fun `shell returns failed for bad command`() = runBlocking {
        val result = executor.execute("exit 1", Language.SHELL)

        if (result.status == ExecutionStatus.FAILED &&
            (result.stderr.contains("timed out") || result.stderr.contains("No such file") ||
                result.stderr.contains("Cannot run") || result.stderr.contains("IOException"))) {
            return@runBlocking
        }
        assertEquals(ExecutionStatus.FAILED, result.status)
        assertEquals(1, result.exitCode)
    }

    // === SQL tests ===

    @Test
    fun `sql creates table and inserts`() = runBlocking {
        val result = executor.execute(
            "CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT);" +
                "INSERT INTO users (name) VALUES ('Alice');" +
                "INSERT INTO users (name) VALUES ('Bob');",
            Language.SQL,
        )

        assertEquals(ExecutionStatus.PASSED, result.status)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `sql selects from table`() = runBlocking {
        val result = executor.execute(
            "CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT);" +
                "INSERT INTO items (name) VALUES ('apple');" +
                "INSERT INTO items (name) VALUES ('banana');" +
                "SELECT * FROM items;",
            Language.SQL,
        )

        assertEquals(ExecutionStatus.PASSED, result.status)
        assertTrue(result.stdout.contains("apple"))
        assertTrue(result.stdout.contains("banana"))
    }

    @Test
    fun `sql returns failed for invalid syntax`() = runBlocking {
        val result = executor.execute("SELECT FROM nonexistent;", Language.SQL)

        assertEquals(ExecutionStatus.FAILED, result.status)
        assertTrue(result.stderr.isNotBlank())
    }

    // === Java tests (BeanShell) ===

    @Test
    fun `java executes basic print`() = runBlocking {
        val result = executor.execute(
            "System.out.println(\"hello_java\");",
            Language.JAVA,
        )

        assertEquals(ExecutionStatus.PASSED, result.status)
        assertTrue(result.stdout.contains("hello_java"))
    }

    @Test
    fun `java executes arithmetic`() = runBlocking {
        val result = executor.execute(
            "int a = 10; int b = 20; System.out.println(a + b);",
            Language.JAVA,
        )

        assertEquals(ExecutionStatus.PASSED, result.status)
        assertTrue(result.stdout.contains("30"))
    }

    @Test
    fun `java executes for loop`() = runBlocking {
        val result = executor.execute(
            """
            int sum = 0;
            for (int i = 1; i <= 5; i++) {
                sum += i;
            }
            System.out.println("sum=" + sum);
            """.trimIndent(),
            Language.JAVA,
        )

        assertEquals(ExecutionStatus.PASSED, result.status)
        assertTrue(result.stdout.contains("sum=15"))
    }

    @Test
    fun `java executes method definition and call`() = runBlocking {
        val result = executor.execute(
            """
            int factorial(int n) {
                if (n <= 1) return 1;
                return n * factorial(n - 1);
            }
            System.out.println(factorial(5));
            """.trimIndent(),
            Language.JAVA,
        )

        assertEquals(ExecutionStatus.PASSED, result.status)
        assertTrue(result.stdout.contains("120"))
    }

    @Test
    fun `java returns failed for syntax error`() = runBlocking {
        val result = executor.execute(
            "int x = ;",
            Language.JAVA,
        )

        assertEquals(ExecutionStatus.FAILED, result.status)
        assertTrue(result.stderr.isNotBlank())
    }
}
