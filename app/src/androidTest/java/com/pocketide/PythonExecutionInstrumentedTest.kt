package com.pocketide

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketide.data.execution.CodeExecutor
import com.pocketide.data.execution.ExecutionConsole
import com.pocketide.data.execution.ExecutionRequest
import com.pocketide.data.model.ExecutionStatus
import com.pocketide.data.model.Language
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PythonExecutionInstrumentedTest {

    @Test
    fun cpythonSupportsInputAndSiblingImports() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val project = File(context.cacheDir, "python_execution_test").apply {
            deleteRecursively()
            mkdirs()
        }
        File(project, "calculator.py").writeText("def add(a, b):\n    return a + b\n")
        val console = ExecutionConsole()
        val execution = async {
            CodeExecutor(context).execute(
                ExecutionRequest(
                    code = """
                        from calculator import add
                        value = int(input("Number: "))
                        print(add(value, 2))
                    """.trimIndent(),
                    language = Language.PYTHON,
                    fileName = "main.py",
                    projectDirectory = project,
                    console = console,
                ),
            )
        }

        withTimeout(20_000) {
            while (!console.waitingForInput) delay(20)
        }
        assertTrue(console.submitInput("5"))
        val result = withTimeout(20_000) { execution.await() }

        assertEquals(ExecutionStatus.PASSED, result.status)
        assertTrue(result.stdout.contains("7"))
    }

    @Test
    fun cpythonReturnsTracebackLineAndErrorType() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val result = CodeExecutor(context).execute(
            ExecutionRequest(
                code = "print('before')\nraise ValueError('broken')",
                language = Language.PYTHON,
                fileName = "main.py",
                projectDirectory = context.cacheDir,
            ),
        )

        assertEquals(ExecutionStatus.FAILED, result.status)
        assertEquals(2, result.errorLine)
        assertEquals("ValueError", result.errorType)
        assertTrue(result.stderr.contains("broken"))
    }

    @Test
    fun cpythonExecutesLongerStandardLibraryProgram() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val result = CodeExecutor(context).execute(
            ExecutionRequest(
                code = """
                    import json
                    from dataclasses import dataclass

                    @dataclass
                    class Item:
                        name: str
                        price: float
                        quantity: int

                        def total(self):
                            return self.price * self.quantity

                    def summarize(records):
                        items = [Item(**record) for record in records]
                        totals = {item.name: round(item.total(), 2) for item in items}
                        return {
                            "count": len(items),
                            "totals": totals,
                            "grand_total": round(sum(totals.values()), 2),
                        }

                    source = json.loads('[{"name":"apple","price":1.25,"quantity":4},{"name":"pear","price":2.0,"quantity":3}]')
                    report = summarize(source)
                    assert report["count"] == 2
                    assert report["grand_total"] == 11.0
                    print(json.dumps(report, sort_keys=True))
                """.trimIndent(),
                language = Language.PYTHON,
                fileName = "main.py",
                projectDirectory = context.cacheDir,
            ),
        )

        assertEquals(ExecutionStatus.PASSED, result.status)
        assertTrue(result.stdout.contains("grand_total"))
    }
}
