package com.pocketide.data.execution

import com.pocketide.data.model.ExecutionResult
import com.pocketide.data.model.ExecutionStatus
import com.pocketide.data.model.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.EvaluatorException
import org.mozilla.javascript.ScriptableObject

private const val INSTRUCTION_LIMIT = 5_000_000

/**
 * Executes code on-device where a real, safe interpreter is available.
 * Languages without an integrated runtime return a clear "not supported"
 * result rather than fabricating output.
 */
class CodeExecutor {

    suspend fun execute(code: String, language: Language): ExecutionResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        when (language) {
            Language.JAVASCRIPT -> executeJavaScript(code, startTime)
            else -> ExecutionResult(
                status = ExecutionStatus.FAILED,
                stdout = "",
                stderr = "Execution for ${language.displayName} is not yet supported on-device.\n" +
                    "Currently supported: JavaScript.",
                exitCode = -1,
                durationMs = System.currentTimeMillis() - startTime,
            )
        }
    }

    private fun executeJavaScript(code: String, startTime: Long): ExecutionResult {
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val factory = object : ContextFactory() {
            override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                if (instructionCount > INSTRUCTION_LIMIT) {
                    throw EvaluatorException("Script exceeded instruction limit (possible infinite loop)")
                }
            }
        }
        if (!ContextFactory.hasExplicitGlobal()) {
            ContextFactory.initGlobal(factory)
        }

        val context = factory.enterContext()
        return try {
            context.instructionObserverThreshold = 10_000
            context.optimizationLevel = -1
            val scope = context.initStandardObjects()

            val logger = ConsoleLogger(stdout)
            val consoleObj = context.newObject(scope)
            ScriptableObject.putProperty(scope, "console", consoleObj)
            if (consoleObj is ScriptableObject) {
                consoleObj.put("log", consoleObj, logger)
            }

            context.evaluateString(scope, code, "main.js", 1, null)

            ExecutionResult(
                status = ExecutionStatus.PASSED,
                stdout = stdout.toString(),
                stderr = "",
                exitCode = 0,
                durationMs = System.currentTimeMillis() - startTime,
            )
        } catch (e: EvaluatorException) {
            ExecutionResult(
                status = ExecutionStatus.FAILED,
                stdout = stdout.toString(),
                stderr = "${e.message}",
                exitCode = 1,
                durationMs = System.currentTimeMillis() - startTime,
                errorLine = e.lineNumber().takeIf { it > 0 },
            )
        } catch (e: Exception) {
            ExecutionResult(
                status = ExecutionStatus.FAILED,
                stdout = stdout.toString(),
                stderr = e.message ?: e.javaClass.simpleName,
                exitCode = 1,
                durationMs = System.currentTimeMillis() - startTime,
            )
        } finally {
            Context.exit()
        }
    }
}

private class ConsoleLogger(private val stdout: StringBuilder) : org.mozilla.javascript.BaseFunction() {
    override fun call(
        cx: Context?,
        scope: org.mozilla.javascript.Scriptable?,
        thisObj: org.mozilla.javascript.Scriptable?,
        args: Array<out Any?>?,
    ): Any {
        val line = args?.joinToString(" ") { Context.toString(it) } ?: ""
        stdout.append(line).append('\n')
        return Context.getUndefinedValue()
    }
}
