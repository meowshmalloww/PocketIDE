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

/** Executes every runtime PocketIDE claims on an actual Android test process. */
@RunWith(AndroidJUnit4::class)
class RuntimeMatrixInstrumentedTest {

    @Test
    fun allDeclaredRuntimeAndCompatibilityLanguagesExecuteOnAndroid() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val executor = CodeExecutor(context)
        val project = File(context.cacheDir, "runtime_matrix").apply {
            deleteRecursively()
            mkdirs()
        }
        val cases = listOf(
            Case(Language.JAVASCRIPT, "console.log(6 * 7);", "42"),
            Case(Language.TYPESCRIPT, "const answer: number = 6 * 7; console.log(answer);", "42"),
            Case(Language.LUA, "print(6 * 7)", "42"),
            Case(
                Language.SQL,
                "CREATE TABLE values_table (n INTEGER); INSERT INTO values_table VALUES (42); SELECT n FROM values_table;",
                "42",
            ),
            Case(Language.JAVA, "int answer = 6 * 7; System.out.println(answer);", "42"),
            Case(Language.SHELL, "value=42\necho \$value", "42"),
        )

        cases.forEach { case ->
            val result = withTimeout(20_000) {
                executor.execute(
                    ExecutionRequest(
                        code = case.code,
                        language = case.language,
                        fileName = "main.${case.language.fileExtension}",
                        projectDirectory = project,
                    ),
                )
            }
            assertEquals("${case.language.displayName}: ${result.stderr}", ExecutionStatus.PASSED, result.status)
            assertTrue("${case.language.displayName} output was ${result.stdout}", result.stdout.contains(case.expected))
        }
    }

    @Test
    fun hardwareBridgeIsReachableFromEveryClaimedRuntime() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val executor = CodeExecutor(context)
        val cases = listOf(
            Case(Language.PYTHON, "print(len(hardware.getDeviceInfo()) > 0)", "True"),
            Case(Language.JAVASCRIPT, "console.log(hardware.getDeviceInfo().length() > 0);", "true"),
            Case(
                Language.TYPESCRIPT,
                "const info: string = String(hardware.getDeviceInfo()); console.log(info.length > 0);",
                "true",
            ),
            Case(Language.LUA, "print(#hardware.getDeviceInfo() > 0)", "true"),
            Case(Language.JAVA, "System.out.println(hardware.getDeviceInfo().length() > 0);", "true"),
        )

        cases.forEach { case ->
            val result = withTimeout(30_000) {
                executor.execute(
                    ExecutionRequest(
                        code = case.code,
                        language = case.language,
                        fileName = "hardware.${case.language.fileExtension}",
                        projectDirectory = context.cacheDir,
                    ),
                )
            }
            assertEquals("${case.language.displayName}: ${result.stderr}", ExecutionStatus.PASSED, result.status)
            assertTrue("${case.language.displayName} output was ${result.stdout}", result.stdout.contains(case.expected))
        }
    }

    @Test
    fun interactiveEmbeddedRuntimesReceiveTerminalInput() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val executor = CodeExecutor(context)
        val cases = listOf(
            Case(Language.JAVASCRIPT, "var value = input('Value'); console.log(value);", "PocketIDE"),
            Case(Language.TYPESCRIPT, "const value: string = input('Value'); console.log(value);", "PocketIDE"),
            Case(Language.LUA, "local value = io.read(); print(value)", "PocketIDE"),
            Case(Language.JAVA, "String value = input(\"Value\"); System.out.println(value);", "PocketIDE"),
        )

        cases.forEach { case ->
            val console = ExecutionConsole()
            val execution = async {
                executor.execute(
                    ExecutionRequest(
                        code = case.code,
                        language = case.language,
                        fileName = "interactive.${case.language.fileExtension}",
                        projectDirectory = context.cacheDir,
                        console = console,
                    ),
                )
            }
            withTimeout(10_000) {
                while (!console.waitingForInput) delay(20)
            }
            assertTrue(console.submitInput("PocketIDE"))
            val result = withTimeout(20_000) { execution.await() }
            assertEquals("${case.language.displayName}: ${result.stderr}", ExecutionStatus.PASSED, result.status)
            assertTrue("${case.language.displayName} output was ${result.stdout}", result.stdout.contains("PocketIDE"))
        }
    }

    private data class Case(
        val language: Language,
        val code: String,
        val expected: String,
    )
}
