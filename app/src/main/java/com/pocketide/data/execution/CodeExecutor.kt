package com.pocketide.data.execution

import android.content.Context as AndroidContext
import android.database.sqlite.SQLiteDatabase
import com.pocketide.data.hardware.HardwareBridge
import com.pocketide.data.model.ExecutionResult
import com.pocketide.data.model.ExecutionStatus
import com.pocketide.data.model.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.EvaluatorException
import org.mozilla.javascript.ScriptableObject
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.jse.JsePlatform
import bsh.Interpreter
import java.io.File
import java.io.PrintStream
import java.io.ByteArrayOutputStream

private const val INSTRUCTION_LIMIT = 5_000_000
private const val SHELL_TIMEOUT_MS = 10_000L

/**
 * Executes code on-device where a real, safe interpreter is available.
 * Supported: JavaScript (Rhino), Lua (LuaJ), Shell (ProcessBuilder), SQL (SQLite),
 * Java (BeanShell).
 * Languages without an integrated runtime return a clear "not supported" result.
 */
class CodeExecutor(
    private val context: AndroidContext? = null,
) {

    private val jsContextFactory = object : ContextFactory() {
        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            if (instructionCount > INSTRUCTION_LIMIT) {
                throw EvaluatorException("Script exceeded instruction limit (possible infinite loop)")
            }
        }
    }.also {
        if (!ContextFactory.hasExplicitGlobal()) {
            ContextFactory.initGlobal(it)
        }
    }

    suspend fun execute(code: String, language: Language): ExecutionResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        when (language) {
            Language.JAVASCRIPT -> executeJavaScript(code, startTime)
            Language.LUA -> executeLua(code, startTime)
            Language.SHELL -> executeShell(code, startTime)
            Language.SQL -> executeSql(code, startTime)
            Language.JAVA -> executeJava(code, startTime)
            else -> ExecutionResult(
                status = ExecutionStatus.FAILED,
                stdout = "",
                stderr = "Execution for ${language.displayName} is not yet supported on-device.\n" +
                    "Currently supported: JavaScript, Lua, Shell, SQL, Java.",
                exitCode = -1,
                durationMs = System.currentTimeMillis() - startTime,
            )
        }
    }

    private fun executeJavaScript(code: String, startTime: Long): ExecutionResult {
        val stdout = StringBuilder()

        val rhinoContext = jsContextFactory.enterContext()
        return try {
            rhinoContext.instructionObserverThreshold = 10_000
            rhinoContext.optimizationLevel = -1
            val scope = rhinoContext.initStandardObjects()

            val logger = ConsoleLogger(stdout)
            val consoleObj = rhinoContext.newObject(scope)
            ScriptableObject.putProperty(scope, "console", consoleObj)
            if (consoleObj is ScriptableObject) {
                consoleObj.put("log", consoleObj, logger)
            }

            // Inject hardware bridge if Android context is available
            this.context?.let { ctx ->
                val bridge = HardwareBridge(ctx)
                val hwObj = rhinoContext.newObject(scope)
                ScriptableObject.putProperty(scope, "hardware", hwObj)
                if (hwObj is ScriptableObject) {
                    hwObj.put("toast", hwObj, HardwareToastFunction(bridge, stdout))
                    hwObj.put("flashlight", hwObj, HardwareFlashlightFunction(bridge, stdout))
                    hwObj.put("vibrate", hwObj, HardwareVibrateFunction(bridge, stdout))
                    hwObj.put("deviceInfo", hwObj, HardwareDeviceInfoFunction(bridge, stdout))
                }
            }

            rhinoContext.evaluateString(scope, code, "main.js", 1, null)

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
                errorColumn = e.columnNumber().takeIf { it > 0 },
                errorType = e.javaClass.simpleName,
            )
        } catch (e: Exception) {
            ExecutionResult(
                status = ExecutionStatus.FAILED,
                stdout = stdout.toString(),
                stderr = e.message ?: e.javaClass.simpleName,
                exitCode = 1,
                durationMs = System.currentTimeMillis() - startTime,
                errorType = e.javaClass.simpleName,
            )
        } finally {
            Context.exit()
        }
    }

    private fun executeLua(code: String, startTime: Long): ExecutionResult {
        val stdout = StringBuilder()
        return try {
            val globals: Globals = JsePlatform.standardGlobals()

            // Override print to capture stdout
            globals.set("print", object : org.luaj.vm2.lib.OneArgFunction() {
                override fun call(arg: LuaValue): LuaValue {
                    stdout.append(arg.tojstring()).append('\n')
                    return LuaValue.NIL
                }
            })
            globals.set("println", object : org.luaj.vm2.lib.VarArgFunction() {
                override fun invoke(args: org.luaj.vm2.Varargs): LuaValue {
                    val sb = StringBuilder()
                    for (i in 1..args.narg()) {
                        if (i > 1) sb.append("\t")
                        sb.append(args.arg(i).tojstring())
                    }
                    stdout.append(sb).append('\n')
                    return LuaValue.NIL
                }
            })

            // Inject hardware bridge if context is available
            context?.let { ctx ->
                val bridge = HardwareBridge(ctx)
                val hw = LuaTable()
                hw.set("toast", object : org.luaj.vm2.lib.OneArgFunction() {
                    override fun call(arg: LuaValue): LuaValue {
                        bridge.toast(arg.tojstring())
                        stdout.append("[toast] ").append(arg.tojstring()).append('\n')
                        return LuaValue.NIL
                    }
                })
                hw.set("flashlight", object : org.luaj.vm2.lib.OneArgFunction() {
                    override fun call(arg: LuaValue): LuaValue {
                        val on = arg.toboolean()
                        val ok = bridge.setFlashlight(on)
                        stdout.append("[flashlight] ").append(if (ok) "OK" else "FAILED").append('\n')
                        return LuaValue.valueOf(ok)
                    }
                })
                hw.set("vibrate", object : org.luaj.vm2.lib.OneArgFunction() {
                    override fun call(arg: LuaValue): LuaValue {
                        bridge.vibrate(arg.tolong())
                        stdout.append("[vibrate] ").append(arg.tolong()).append("ms\n")
                        return LuaValue.NIL
                    }
                })
                hw.set("deviceInfo", object : org.luaj.vm2.lib.ZeroArgFunction() {
                    override fun call(): LuaValue {
                        val info = bridge.getDeviceInfo()
                        stdout.append(info)
                        return LuaValue.valueOf(info)
                    }
                })
                globals.set("hardware", hw)
            }

            globals.load(code, "main.lua").call()

            ExecutionResult(
                status = ExecutionStatus.PASSED,
                stdout = stdout.toString(),
                stderr = "",
                exitCode = 0,
                durationMs = System.currentTimeMillis() - startTime,
            )
        } catch (e: LuaError) {
            ExecutionResult(
                status = ExecutionStatus.FAILED,
                stdout = stdout.toString(),
                stderr = e.message ?: "Lua error",
                exitCode = 1,
                durationMs = System.currentTimeMillis() - startTime,
                errorType = e.javaClass.simpleName,
            )
        } catch (e: Exception) {
            ExecutionResult(
                status = ExecutionStatus.FAILED,
                stdout = stdout.toString(),
                stderr = e.message ?: e.javaClass.simpleName,
                exitCode = 1,
                durationMs = System.currentTimeMillis() - startTime,
                errorType = e.javaClass.simpleName,
            )
        }
    }

    private fun executeShell(code: String, startTime: Long): ExecutionResult {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val scriptFile = File.createTempFile("pocketide_script", ".sh")
        return try {
            scriptFile.writeText(code)
            scriptFile.setExecutable(true)

            val process = ProcessBuilder("sh", scriptFile.absolutePath)
                .redirectErrorStream(false)
                .start()

            // Read stdout and stderr concurrently to prevent deadlock on large output
            val stdoutFuture = java.util.concurrent.FutureTask {
                process.inputStream.bufferedReader().use { it.readText() }
            }
            val stderrFuture = java.util.concurrent.FutureTask {
                process.errorStream.bufferedReader().use { it.readText() }
            }
            Thread(stdoutFuture, "shell-stdout").start()
            Thread(stderrFuture, "shell-stderr").start()

            val finished = process.waitFor(SHELL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return ExecutionResult(
                    status = ExecutionStatus.FAILED,
                    stdout = stdout.toString(),
                    stderr = "Shell execution timed out (${SHELL_TIMEOUT_MS}ms)",
                    exitCode = -1,
                    durationMs = System.currentTimeMillis() - startTime,
                )
            }

            stdout.append(stdoutFuture.get())
            stderr.append(stderrFuture.get())
            val exitCode = process.exitValue()

            ExecutionResult(
                status = if (exitCode == 0) ExecutionStatus.PASSED else ExecutionStatus.FAILED,
                stdout = stdout.toString(),
                stderr = stderr.toString(),
                exitCode = exitCode,
                durationMs = System.currentTimeMillis() - startTime,
            )
        } catch (e: Exception) {
            ExecutionResult(
                status = ExecutionStatus.FAILED,
                stdout = stdout.toString(),
                stderr = e.message ?: e.javaClass.simpleName,
                exitCode = 1,
                durationMs = System.currentTimeMillis() - startTime,
                errorType = e.javaClass.simpleName,
            )
        } finally {
            scriptFile.delete()
        }
    }

    private fun executeJava(code: String, startTime: Long): ExecutionResult {
        val stdout = StringBuilder()
        val originalOut = System.out
        return try {
            val baos = ByteArrayOutputStream()
            System.setOut(PrintStream(baos))

            val interpreter = Interpreter()

            // Inject hardware bridge if Android context is available
            context?.let { ctx ->
                val bridge = HardwareBridge(ctx)
                interpreter.set("hardware", bridge)
            }

            interpreter.eval(code)
            stdout.append(baos.toString())

            ExecutionResult(
                status = ExecutionStatus.PASSED,
                stdout = stdout.toString(),
                exitCode = 0,
                durationMs = System.currentTimeMillis() - startTime,
            )
        } catch (e: bsh.EvalError) {
            ExecutionResult(
                status = ExecutionStatus.FAILED,
                stdout = stdout.toString(),
                stderr = e.message ?: "BeanShell evaluation error",
                exitCode = 1,
                durationMs = System.currentTimeMillis() - startTime,
                errorLine = e.errorLineNumber.takeIf { it > 0 },
                errorType = e.javaClass.simpleName,
            )
        } catch (e: Exception) {
            ExecutionResult(
                status = ExecutionStatus.FAILED,
                stdout = stdout.toString(),
                stderr = e.message ?: e.javaClass.simpleName,
                exitCode = 1,
                durationMs = System.currentTimeMillis() - startTime,
                errorType = e.javaClass.simpleName,
            )
        } finally {
            System.setOut(originalOut)
        }
    }

    private fun executeSql(code: String, startTime: Long): ExecutionResult {
        val stdout = StringBuilder()
        val dbFile = File.createTempFile("pocketide_sql", ".db")
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        return try {
            val statements = code.split(";").filter { it.isNotBlank() }
            for (stmt in statements) {
                val trimmed = stmt.trim()
                if (trimmed.isEmpty()) continue

                val upper = trimmed.uppercase()
                if (upper.startsWith("SELECT") || upper.startsWith("PRAGMA") || upper.startsWith("WITH")) {
                    db.rawQuery(trimmed, null).use { cursor ->
                        val columnNames = cursor.columnNames
                        stdout.append(columnNames.joinToString(" | ")).append('\n')
                        stdout.append(columnNames.joinToString(" | ") { "-".repeat(it.length.coerceAtLeast(3)) }).append('\n')
                        while (cursor.moveToNext()) {
                            val row = (0 until cursor.columnCount).joinToString(" | ") { i ->
                                cursor.getString(i) ?: "NULL"
                            }
                            stdout.append(row).append('\n')
                        }
                    }
                } else {
                    db.execSQL(trimmed)
                    stdout.append("[OK] $trimmed\n")
                }
            }

            ExecutionResult(
                status = ExecutionStatus.PASSED,
                stdout = stdout.toString(),
                exitCode = 0,
                durationMs = System.currentTimeMillis() - startTime,
            )
        } catch (e: Exception) {
            ExecutionResult(
                status = ExecutionStatus.FAILED,
                stdout = stdout.toString(),
                stderr = e.message ?: e.javaClass.simpleName,
                exitCode = 1,
                durationMs = System.currentTimeMillis() - startTime,
                errorType = e.javaClass.simpleName,
            )
        } finally {
            db.close()
            dbFile.delete()
        }
    }
}

private class HardwareToastFunction(
    private val bridge: HardwareBridge,
    private val stdout: StringBuilder,
) : org.mozilla.javascript.BaseFunction() {
    override fun call(
        cx: Context?,
        scope: org.mozilla.javascript.Scriptable?,
        thisObj: org.mozilla.javascript.Scriptable?,
        args: Array<out Any?>?,
    ): Any {
        val msg = args?.firstOrNull()?.let { Context.toString(it) } ?: ""
        bridge.toast(msg)
        stdout.append("[toast] ").append(msg).append('\n')
        return Context.getUndefinedValue()
    }
}

private class HardwareFlashlightFunction(
    private val bridge: HardwareBridge,
    private val stdout: StringBuilder,
) : org.mozilla.javascript.BaseFunction() {
    override fun call(
        cx: Context?,
        scope: org.mozilla.javascript.Scriptable?,
        thisObj: org.mozilla.javascript.Scriptable?,
        args: Array<out Any?>?,
    ): Any {
        val on = args?.firstOrNull()?.let { Context.toBoolean(it) } ?: true
        val ok = bridge.setFlashlight(on)
        stdout.append("[flashlight] ").append(if (ok) "OK" else "FAILED").append('\n')
        return ok
    }
}

private class HardwareVibrateFunction(
    private val bridge: HardwareBridge,
    private val stdout: StringBuilder,
) : org.mozilla.javascript.BaseFunction() {
    override fun call(
        cx: Context?,
        scope: org.mozilla.javascript.Scriptable?,
        thisObj: org.mozilla.javascript.Scriptable?,
        args: Array<out Any?>?,
    ): Any {
        val duration = args?.firstOrNull()?.let { Context.toNumber(it).toLong() } ?: 200L
        bridge.vibrate(duration)
        stdout.append("[vibrate] ").append(duration).append("ms\n")
        return Context.getUndefinedValue()
    }
}

private class HardwareDeviceInfoFunction(
    private val bridge: HardwareBridge,
    private val stdout: StringBuilder,
) : org.mozilla.javascript.BaseFunction() {
    override fun call(
        cx: Context?,
        scope: org.mozilla.javascript.Scriptable?,
        thisObj: org.mozilla.javascript.Scriptable?,
        args: Array<out Any?>?,
    ): Any {
        val info = bridge.getDeviceInfo()
        stdout.append(info)
        return Context.getUndefinedValue()
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
