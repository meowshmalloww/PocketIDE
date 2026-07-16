package com.pocketide.data.execution

import com.pocketide.data.model.ExecutionStatus
import com.pocketide.data.model.Language
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExecutionConsoleTest {

    @Test
    fun `javascript input blocks until terminal submits a line`() = runBlocking {
        val console = ExecutionConsole()
        val execution = async {
            CodeExecutor().execute(
                ExecutionRequest(
                    code = "var value = input('Value'); console.log(Number(value) + 1);",
                    language = Language.JAVASCRIPT,
                    console = console,
                ),
            )
        }

        withTimeout(2_000) {
            while (!console.waitingForInput) delay(5)
        }
        assertTrue(console.submitInput("4"))
        val result = withTimeout(2_000) { execution.await() }

        assertEquals(ExecutionStatus.PASSED, result.status)
        assertTrue(result.stdout.contains("5"))
        assertFalse(console.waitingForInput)
    }
}
