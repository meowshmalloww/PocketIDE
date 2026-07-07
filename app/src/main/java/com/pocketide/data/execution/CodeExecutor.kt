package com.pocketide.data.execution

import android.content.Context as AndroidContext
import android.util.Log
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
 * Supported: Python (transpiled to JS), JavaScript (Rhino), TypeScript (stripped to JS),
 * Lua (LuaJ), Shell (ProcessBuilder), SQL (SQLite), Java (BeanShell).
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
            Language.TYPESCRIPT -> executeTypeScript(code, startTime)
            Language.LUA -> executeLua(code, startTime)
            Language.SHELL -> executeShell(code, startTime)
            Language.SQL -> executeSql(code, startTime)
            Language.JAVA -> executeJava(code, startTime)
            Language.PYTHON -> executePython(code, startTime)
            else -> ExecutionResult(
                status = ExecutionStatus.FAILED,
                stdout = "",
                stderr = "Execution for ${language.displayName} is not yet supported on-device.\n" +
                    "Currently supported: Python, JavaScript, TypeScript, Lua, Shell, SQL, Java.",
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

            // Inject hardware bridge if Android context is available.
            // Expose the whole HardwareBridge object so all its methods
            // (toast, vibrate, flashlight, batteryLevel, clipboardGet, etc.)
            // are directly callable from JS as `hardware.<method>(...)`.
            this.context?.let { ctx ->
                val bridge = HardwareBridge(ctx)
                val wrapped = Context.javaToJS(bridge, scope)
                ScriptableObject.putProperty(scope, "hardware", wrapped)
            }

            // Inject ES6+ polyfills for methods Rhino 1.7 doesn't provide
            rhinoContext.evaluateString(scope, JS_POLYFILLS, "polyfill.js", 1, null)

            val processedCode = preprocessJavaScript(code)
            rhinoContext.evaluateString(scope, processedCode, "main.js", 1, null)

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
            try { Context.exit() } catch (e: Exception) { Log.w("CodeExecutor", "Context.exit failed", e) }
        }
    }

    private fun executeLua(code: String, startTime: Long): ExecutionResult {
        val stdout = StringBuilder()
        return try {
            val globals: Globals = JsePlatform.standardGlobals()

            // Override print to capture stdout
            globals.set("print", object : org.luaj.vm2.lib.VarArgFunction() {
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

            // Override io.write to capture stdout
            val ioTable = globals.get("io") as? LuaTable ?: LuaTable()
            ioTable.set("write", object : org.luaj.vm2.lib.VarArgFunction() {
                override fun invoke(args: org.luaj.vm2.Varargs): LuaValue {
                    for (i in 1..args.narg()) {
                        stdout.append(args.arg(i).tojstring())
                    }
                    return LuaValue.NIL
                }
            })
            globals.set("io", ioTable)

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
                hw.set("batteryLevel", object : org.luaj.vm2.lib.ZeroArgFunction() {
                    override fun call(): LuaValue = LuaValue.valueOf(bridge.batteryLevel())
                })
                hw.set("isCharging", object : org.luaj.vm2.lib.ZeroArgFunction() {
                    override fun call(): LuaValue = LuaValue.valueOf(bridge.isCharging())
                })
                hw.set("clipboardGet", object : org.luaj.vm2.lib.ZeroArgFunction() {
                    override fun call(): LuaValue = LuaValue.valueOf(bridge.clipboardGet())
                })
                hw.set("clipboardSet", object : org.luaj.vm2.lib.OneArgFunction() {
                    override fun call(arg: LuaValue): LuaValue {
                        bridge.clipboardSet(arg.tojstring())
                        return LuaValue.NIL
                    }
                })
                hw.set("screenInfo", object : org.luaj.vm2.lib.ZeroArgFunction() {
                    override fun call(): LuaValue = LuaValue.valueOf(bridge.screenInfo())
                })
                hw.set("screenBrightness", object : org.luaj.vm2.lib.ZeroArgFunction() {
                    override fun call(): LuaValue = LuaValue.valueOf(bridge.screenBrightness())
                })
                hw.set("networkType", object : org.luaj.vm2.lib.ZeroArgFunction() {
                    override fun call(): LuaValue = LuaValue.valueOf(bridge.networkType())
                })
                hw.set("isOnline", object : org.luaj.vm2.lib.ZeroArgFunction() {
                    override fun call(): LuaValue = LuaValue.valueOf(bridge.isOnline())
                })
                hw.set("storageFree", object : org.luaj.vm2.lib.ZeroArgFunction() {
                    override fun call(): LuaValue = LuaValue.valueOf(bridge.storageFree().toDouble())
                })
                hw.set("storageTotal", object : org.luaj.vm2.lib.ZeroArgFunction() {
                    override fun call(): LuaValue = LuaValue.valueOf(bridge.storageTotal().toDouble())
                })
                hw.set("readSensor", object : org.luaj.vm2.lib.VarArgFunction() {
                    override fun invoke(args: org.luaj.vm2.Varargs): org.luaj.vm2.Varargs {
                        val type = args.arg(1).tojstring()
                        val timeout = if (args.narg() >= 2) args.arg(2).tolong() else 1000L
                        return LuaValue.valueOf(bridge.readSensor(type, timeout))
                    }
                })
                hw.set("listSensors", object : org.luaj.vm2.lib.ZeroArgFunction() {
                    override fun call(): LuaValue = LuaValue.valueOf(bridge.listSensors())
                })
                hw.set("openUrl", object : org.luaj.vm2.lib.OneArgFunction() {
                    override fun call(arg: LuaValue): LuaValue =
                        LuaValue.valueOf(bridge.openUrl(arg.tojstring()))
                })
                hw.set("toastLong", object : org.luaj.vm2.lib.OneArgFunction() {
                    override fun call(arg: LuaValue): LuaValue {
                        bridge.toastLong(arg.tojstring())
                        return LuaValue.NIL
                    }
                })
                hw.set("vibratePattern", object : org.luaj.vm2.lib.OneArgFunction() {
                    override fun call(arg: LuaValue): LuaValue {
                        val table = arg as? LuaTable
                        val timings = if (table != null) {
                            LongArray(table.length()) { i -> table.get(i + 1).tolong() }
                        } else {
                            longArrayOf(0, 200)
                        }
                        bridge.vibratePattern(timings)
                        return LuaValue.NIL
                    }
                })
                hw.set("batteryTemperature", object : org.luaj.vm2.lib.ZeroArgFunction() {
                    override fun call(): LuaValue = LuaValue.valueOf(bridge.batteryTemperature().toDouble())
                })
                hw.set("setScreenBrightness", object : org.luaj.vm2.lib.OneArgFunction() {
                    override fun call(arg: LuaValue): LuaValue =
                        LuaValue.valueOf(bridge.setScreenBrightness(arg.toint()))
                })
                hw.set("keepScreenOn", object : org.luaj.vm2.lib.OneArgFunction() {
                    override fun call(arg: LuaValue): LuaValue {
                        bridge.keepScreenOn(arg.toboolean())
                        return LuaValue.NIL
                    }
                })
                hw.set("speak", object : org.luaj.vm2.lib.OneArgFunction() {
                    override fun call(arg: LuaValue): LuaValue =
                        LuaValue.valueOf(bridge.speak(arg.tojstring()))
                })
                hw.set("stopSpeak", object : org.luaj.vm2.lib.ZeroArgFunction() {
                    override fun call(): LuaValue {
                        bridge.stopSpeak()
                        return LuaValue.NIL
                    }
                })
                hw.set("playTone", object : org.luaj.vm2.lib.VarArgFunction() {
                    override fun invoke(args: org.luaj.vm2.Varargs): LuaValue {
                        val freq = if (args.narg() >= 1) args.arg(1).toint() else 440
                        val duration = if (args.narg() >= 2) args.arg(2).toint() else 200
                        bridge.playTone(freq, duration)
                        return LuaValue.NIL
                    }
                })
                hw.set("notify", object : org.luaj.vm2.lib.VarArgFunction() {
                    override fun invoke(args: org.luaj.vm2.Varargs): LuaValue {
                        val title = if (args.narg() >= 1) args.arg(1).tojstring() else "PocketIDE"
                        val text = if (args.narg() >= 2) args.arg(2).tojstring() else ""
                        return LuaValue.valueOf(bridge.notify(title, text))
                    }
                })
                hw.set("getLocation", object : org.luaj.vm2.lib.VarArgFunction() {
                    override fun invoke(args: org.luaj.vm2.Varargs): LuaValue {
                        val timeout = if (args.narg() >= 1) args.arg(1).tolong() else 5000L
                        return LuaValue.valueOf(bridge.getLocation(timeout))
                    }
                })
                hw.set("listBluetooth", object : org.luaj.vm2.lib.ZeroArgFunction() {
                    override fun call(): LuaValue = LuaValue.valueOf(bridge.listBluetooth())
                })
                hw.set("readFile", object : org.luaj.vm2.lib.OneArgFunction() {
                    override fun call(arg: LuaValue): LuaValue =
                        LuaValue.valueOf(bridge.readFile(arg.tojstring()))
                })
                hw.set("writeFile", object : org.luaj.vm2.lib.VarArgFunction() {
                    override fun invoke(args: org.luaj.vm2.Varargs): LuaValue {
                        val path = args.arg(1).tojstring()
                        val content = args.arg(2).tojstring()
                        return LuaValue.valueOf(bridge.writeFile(path, content))
                    }
                })
                hw.set("listFiles", object : org.luaj.vm2.lib.OneArgFunction() {
                    override fun call(arg: LuaValue): LuaValue =
                        LuaValue.valueOf(bridge.listFiles(arg.tojstring()))
                })
                hw.set("deleteFile", object : org.luaj.vm2.lib.OneArgFunction() {
                    override fun call(arg: LuaValue): LuaValue =
                        LuaValue.valueOf(bridge.deleteFile(arg.tojstring()))
                })
                hw.set("sandboxPath", object : org.luaj.vm2.lib.ZeroArgFunction() {
                    override fun call(): LuaValue = LuaValue.valueOf(bridge.sandboxPath())
                })
                hw.set("startServer", object : org.luaj.vm2.lib.VarArgFunction() {
                    override fun invoke(args: org.luaj.vm2.Varargs): LuaValue {
                        val port = if (args.narg() >= 1) args.arg(1).toint() else 8080
                        return LuaValue.valueOf(bridge.startServer(port))
                    }
                })
                hw.set("stopServer", object : org.luaj.vm2.lib.ZeroArgFunction() {
                    override fun call(): LuaValue {
                        bridge.stopServer()
                        return LuaValue.NIL
                    }
                })
                hw.set("isServerRunning", object : org.luaj.vm2.lib.ZeroArgFunction() {
                    override fun call(): LuaValue = LuaValue.valueOf(bridge.isServerRunning())
                })
                hw.set("listCameras", object : org.luaj.vm2.lib.ZeroArgFunction() {
                    override fun call(): LuaValue = LuaValue.valueOf(bridge.listCameras())
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
                try { process.inputStream.close() } catch (e: Exception) { Log.w("CodeExecutor", "close stdin failed", e) }
                try { process.errorStream.close() } catch (e: Exception) { Log.w("CodeExecutor", "close stderr failed", e) }
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
        val baos = ByteArrayOutputStream()
        System.setOut(PrintStream(baos))
        return try {
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

    private fun executePython(code: String, startTime: Long): ExecutionResult {
        val jsCode = transpilePythonToJs(code)
        return executeJavaScript(jsCode, startTime)
    }

    private fun transpilePythonToJs(pyCode: String): String {
        val lines = pyCode.split("\n")
        val output = StringBuilder()
        output.append("(function() {\n")
        output.append("var range = function(start, stop, step) { if (stop === undefined) { stop = start; start = 0; } step = step || 1; var arr = []; for (var i = start; step > 0 ? i < stop : i > stop; i += step) arr.push(i); return arr; };\n")
        output.append("var len = function(x) { return x.length !== undefined ? x.length : Object.keys(x).length; };\n")
        output.append("var str = function(x) { return String(x); };\n")
        output.append("var int = function(x) { return parseInt(x); };\n")
        output.append("var float = function(x) { return parseFloat(x); };\n")
        output.append("var abs = Math.abs;\n")
        output.append("var min = Math.min;\n")
        output.append("var max = Math.max;\n")
        output.append("var round = Math.round;\n")
        output.append("var sum = function(arr) { var s = 0; for (var i = 0; i < arr.length; i++) s += arr[i]; return s; };\n")
        output.append("var sorted = function(arr) { return arr.slice().sort(function(a, b) { return a - b; }); };\n")
        output.append("var pyRepr = function(x) { if (x instanceof Array) { return '[' + x.map(function(e) { return pyRepr(e); }).join(', ') + ']'; } if (typeof x === 'string') { return \"'\" + x + \"'\"; } return String(x); };\n")
        output.append("var __pyprint = function() { var args = Array.prototype.slice.call(arguments); console.log(args.map(function(a) { return a instanceof Array ? pyRepr(a) : String(a); }).join(' ')); };\n")

        val indentStack = ArrayDeque<Int>()
        indentStack.addLast(0)

        for (line in lines) {
            val trimmed = line.trimStart()
            if (trimmed.isEmpty()) {
                output.append("\n")
                continue
            }
            if (trimmed.startsWith("#")) {
                output.append("//").append(trimmed.substring(1)).append("\n")
                continue
            }

            val indent = line.length - trimmed.length

            while (indentStack.last() > indent) {
                indentStack.removeLast()
                output.append("  ".repeat(indentStack.size - 1)).append("}\n")
            }

            if (indent > indentStack.last()) {
                indentStack.addLast(indent)
            }

            val jsLine = convertPythonLineToJs(trimmed)
            output.append("  ".repeat(indentStack.size - 1)).append(jsLine).append("\n")
        }

        while (indentStack.size > 1) {
            indentStack.removeLast()
            output.append("  ".repeat(indentStack.size - 1)).append("}\n")
        }

        output.append("})();\n")
        return output.toString()
    }

    private fun convertPythonLineToJs(line: String): String {
        var l = line

        // def -> function
        l = l.replace(Regex("^def\\s+(\\w+)\\((.*?)\\)\\s*:"), "function $1($2) {")
        // if/elif/else/while
        l = l.replace(Regex("^if\\s+(.+):$"), "if ($1) {")
        l = l.replace(Regex("^elif\\s+(.+):$"), "} else if ($1) {")
        l = l.replace(Regex("^else\\s*:$"), "} else {")
        l = l.replace(Regex("^while\\s+(.+):$"), "while ($1) {")
        // for x in range(...) -> C-style for loop (Rhino doesn't support for...of)
        val forRangeMatch = Regex("^for\\s+(\\w+)\\s+in\\s+range\\(([^)]+)\\)\\s*:").find(l)
        if (forRangeMatch != null) {
            val varName = forRangeMatch.groupValues[1]
            val args = forRangeMatch.groupValues[2].split(",").map { it.trim() }
            return when (args.size) {
                1 -> "for (var $varName = 0; $varName < ${args[0]}; $varName++) {"
                2 -> "for (var $varName = ${args[0]}; $varName < ${args[1]}; $varName++) {"
                3 -> "for (var $varName = ${args[0]}; $varName < ${args[1]}; $varName += ${args[2]}) {"
                else -> "for (var $varName = 0; $varName < ${args.joinToString(",")}; $varName++) {"
            }
        }
        // for x in iterable -> index-based for loop
        val forIterMatch = Regex("^for\\s+(\\w+)\\s+in\\s+(.+):$").find(l)
        if (forIterMatch != null) {
            val varName = forIterMatch.groupValues[1]
            val iterable = forIterMatch.groupValues[2]
            return "for (var __idx = 0, $varName; __idx < ($iterable).length; __idx++) { $varName = ($iterable)[__idx];"
        }

        // pass/break/continue
        if (l == "pass") return ";"
        if (l == "break") return "break;"
        if (l == "continue") return "continue;"

        // return
        l = l.replace(Regex("^return\\s+(.+)"), "return $1;")
        l = l.replace(Regex("^return$"), "return;")

        // import -> ignore
        if (l.startsWith("import ") || l.startsWith("from ")) return "// $l"

        // print(...) -> console.log(pyRepr(...)) for arrays, console.log(...) for others
        l = l.replace(Regex("^print\\("), "__pyprint(")
        // print x (without parens) -> __pyprint(x)
        l = l.replace(Regex("^print\\s+(.+)"), "__pyprint($1)")

        // True/False/None/and/or/not
        l = l.replace("\\bTrue\\b".toRegex(), "true")
        l = l.replace("\\bFalse\\b".toRegex(), "false")
        l = l.replace("\\bNone\\b".toRegex(), "null")
        l = l.replace("\\band\\b".toRegex(), "&&")
        l = l.replace("\\bor\\b".toRegex(), "||")
        l = l.replace("\\bnot\\b".toRegex(), "!")

        // a**b -> Math.pow(a, b)
        l = l.replace(Regex("(\\w+)\\*\\*(\\w+)"), "Math.pow($1, $2)")

        // f-strings -> template literals
        l = l.replace(Regex("f\"([^\"]*)\""), { m ->
            val content = m.groupValues[1]
            "`" + content.replace(Regex("\\{(\\w+)\\}"), "\${$1}") + "`"
        })
        l = l.replace(Regex("f'([^']*)'"), { m ->
            val content = m.groupValues[1]
            "`" + content.replace(Regex("\\{(\\w+)\\}"), "\${$1}") + "`"
        })

        // Variable assignment: add var prefix and semicolon
        if (l.matches(Regex("^[a-zA-Z_]\\w*\\s*=")) && !l.contains("==") && !l.startsWith("function")) {
            val varName = l.substringBefore("=").trim()
            val rest = l.substringAfter("=")
            l = "var $varName = $rest;"
        } else if (!l.endsWith(";") && !l.endsWith("{") && !l.endsWith("}") && !l.startsWith("//") && !l.startsWith("function") && !l.startsWith("if") && !l.startsWith("while") && !l.startsWith("for") && !l.startsWith("return") && !l.startsWith("var ")) {
            l = "$l;"
        }

        return l
    }

    private fun executeTypeScript(code: String, startTime: Long): ExecutionResult {
        // TypeScript is transpiled to JavaScript by stripping type annotations.
        // This handles simple cases: type annotations, interfaces, generics.
        // executeJavaScript will further preprocess ES6 features for Rhino.
        val jsCode = stripTypeScriptTypes(code)
        return executeJavaScript(jsCode, startTime)
    }

    private fun preprocessJavaScript(code: String): String {
        // Protect strings (single/double quoted) and comments from regex corruption.
        // Backtick strings (template literals) are NOT protected — they need conversion.
        val placeholders = mutableListOf<String>()
        var protectedCode = code

        // Protect block comments /* ... */
        protectedCode = protectRegions(protectedCode, Regex("/\\*[\\s\\S]*?\\*/"), placeholders)
        // Protect line comments // ...
        protectedCode = protectRegions(protectedCode, Regex("//[^\\n]*"), placeholders)
        // Protect single-quoted strings
        protectedCode = protectRegions(protectedCode, Regex("'(?:[^'\\\\]|\\\\.)*'"), placeholders)
        // Protect double-quoted strings
        protectedCode = protectRegions(protectedCode, Regex("\"(?:[^\"\\\\]|\\\\.)*\""), placeholders)

        var result = protectedCode
        // Replace let/const with var (Rhino doesn't support let/const)
        result = result.replace(Regex("\\blet\\b"), "var")
        result = result.replace(Regex("\\bconst\\b"), "var")
        // Convert arrow functions: (params) => expr  ->  function(params) { return expr; }
        result = result.replace(Regex("\\(([^)]*)\\)\\s*=>\\s*([^{\\n]+)")) { m ->
            val params = m.groupValues[1]
            val body = m.groupValues[2].trim().trimEnd(';')
            "function($params) { return $body; }"
        }
        // Arrow with no parens: x => expr
        result = result.replace(Regex("\\b([a-zA-Z_]\\w*)\\s*=>\\s*([^{\\n]+)")) { m ->
            val param = m.groupValues[1]
            val body = m.groupValues[2].trim().trimEnd(';')
            "function($param) { return $body; }"
        }
        // Arrow with block body: (params) => { ... }
        result = result.replace(Regex("\\(([^)]*)\\)\\s*=>\\s*\\{"), "function($1) {")
        // Arrow with no parens and block body: x => { ... }
        result = result.replace(Regex("\\b([a-zA-Z_]\\w*)\\s*=>\\s*\\{"), "function($1) {")
        // Convert for...of loops: for (var x of arr) { -> for (var __i = 0; __i < (arr).length; __i++) { var x = (arr)[__i];
        result = result.replace(Regex("for\\s*\\(\\s*(?:var|let|const)\\s+(\\w+)\\s+of\\s+(.+?)\\s*\\)\\s*\\{")) { m ->
            val varName = m.groupValues[1]
            val iterable = m.groupValues[2]
            "for (var __i = 0; __i < ($iterable).length; __i++) { var $varName = ($iterable)[__i];"
        }
        // Convert default parameters: function foo(a = 1, b = 2) -> function foo(a, b) { if (a === undefined) a = 1; if (b === undefined) b = 2; }
        result = convertDefaultParams(result)
        // Convert template literals: `Hello ${name}!` -> "Hello " + name + "!"
        result = convertTemplateLiterals(result)

        // Restore protected strings and comments
        return restoreRegions(result, placeholders)
    }

    private fun protectRegions(code: String, pattern: Regex, placeholders: MutableList<String>): String {
        return pattern.replace(code) { match ->
            placeholders.add(match.value)
            "@@P${placeholders.size - 1}P@@"
        }
    }

    private fun restoreRegions(code: String, placeholders: List<String>): String {
        return Regex("@@P(\\d+)P@@").replace(code) { match ->
            placeholders[match.groupValues[1].toInt()]
        }
    }

    private fun convertDefaultParams(code: String): String {
        // Match function definitions with default parameters
        return code.replace(Regex("(function\\s*\\w*\\s*\\()([^)]*)\\)")) { m ->
            val prefix = m.groupValues[1]
            val params = m.groupValues[2]
            val parts = params.split(",").map { it.trim() }
            val cleanParams = mutableListOf<String>()
            val defaults = mutableListOf<Pair<String, String>>()
            for (part in parts) {
                if (part.isEmpty()) continue
                val eqIdx = part.indexOf('=')
                if (eqIdx > 0) {
                    val name = part.substring(0, eqIdx).trim()
                    val defaultVal = part.substring(eqIdx + 1).trim()
                    cleanParams.add(name)
                    defaults.add(name to defaultVal)
                } else {
                    cleanParams.add(part)
                }
            }
            val paramStr = cleanParams.joinToString(", ")
            if (defaults.isEmpty()) {
                "$prefix$paramStr)"
            } else {
                val defaultChecks = defaults.joinToString(" ") { (name, defaultVal) ->
                    "if ($name === undefined) $name = $defaultVal;"
                }
                "$prefix$paramStr) { $defaultChecks "
            }
        }
    }

    private fun convertTemplateLiterals(code: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < code.length) {
            val c = code[i]
            if (c == '`') {
                // Find matching backtick
                val start = i + 1
                var end = start
                var depth = 0
                while (end < code.length) {
                    val ch = code[end]
                    if (ch == '\\') { end += 2; continue }
                    if (ch == '$' && end + 1 < code.length && code[end + 1] == '{') { depth++; end += 2; continue }
                    if (ch == '{' && depth > 0) { depth++; end++; continue }
                    if (ch == '}' && depth > 0) { depth--; end++; continue }
                    if (ch == '`' && depth == 0) break
                    end++
                }
                val template = code.substring(start, end)
                sb.append('"')
                var j = 0
                while (j < template.length) {
                    val tc = template[j]
                    if (tc == '$' && j + 1 < template.length && template[j + 1] == '{') {
                        sb.append("\" + (")
                        j += 2
                        var braceDepth = 1
                        val exprStart = j
                        while (j < template.length && braceDepth > 0) {
                            if (template[j] == '{') braceDepth++
                            if (template[j] == '}') braceDepth--
                            if (braceDepth > 0) j++
                        }
                        sb.append(template.substring(exprStart, j))
                        sb.append(") + \"")
                        j++ // skip closing }
                    } else if (tc == '"') {
                        sb.append("\\\"")
                        j++
                    } else if (tc == '\\') {
                        sb.append("\\\\")
                        j++
                    } else if (tc == '\n') {
                        sb.append("\\n")
                        j++
                    } else {
                        sb.append(tc)
                        j++
                    }
                }
                sb.append('"')
                i = end + 1
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun stripTypeScriptTypes(tsCode: String): String {
        var code = tsCode
        // Remove import type / export type statements
        code = code.replace(Regex("^(?:import|export)\\s+type\\s+.*$", RegexOption.MULTILINE), "")
        // Remove interface declarations
        code = code.replace(Regex("^interface\\s+\\w+\\s*(?:<[^>]+>)?\\s*\\{[^}]*\\}", RegexOption.MULTILINE), "")
        // Remove type alias declarations
        code = code.replace(Regex("^type\\s+\\w+\\s*(?:<[^>]+>)?\\s*=.*$", RegexOption.MULTILINE), "")
        // Remove type annotations on variables: let x: Type = ... -> let x = ...
        code = code.replace(Regex("(:\\s*[A-Za-z_][A-Za-z0-9_<>,\\s|\\[\\]]+)(?=\\s*[=,)])"), "")
        // Remove function return type annotations: function foo(): Type { -> function foo() {
        code = code.replace(Regex("(function\\s+\\w+\\([^)]*\\)):\\s*[A-Za-z_][A-Za-z0-9_<>,\\s|\\[\\]]+\\s*\\{"), "$1 {")
        // Remove generic type parameters: function foo<T>(...) -> function foo(...)
        code = code.replace(Regex("<[A-Za-z_][A-Za-z0-9_,\\s]+>(?=\\()"), "")
        // Remove 'as Type' assertions
        code = code.replace(Regex("\\bas\\s+[A-Za-z_][A-Za-z0-9_<>,\\s|\\[\\]]+"), "")
        // Remove access modifiers: public, private, protected, readonly
        code = code.replace(Regex("\\b(?:public|private|protected|readonly)\\s+"), "")
        // Remove non-null assertion operator (!)
        code = code.replace(Regex("!(?=[.\\[;,)])"), "")
        // Replace let/const with var (Rhino doesn't support let/const)
        code = code.replace(Regex("\\blet\\b", RegexOption.MULTILINE), "var")
        code = code.replace(Regex("\\bconst\\b", RegexOption.MULTILINE), "var")
        return code
    }

    private fun executeSql(code: String, startTime: Long): ExecutionResult {
        val stdout = StringBuilder()
        val dbFile = File.createTempFile("pocketide_sql", ".db")
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
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
            try { db?.close() } catch (e: Exception) { Log.w("CodeExecutor", "db close failed", e) }
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

private const val JS_POLYFILLS = """
if (typeof Object.assign !== 'function') {
  Object.assign = function(target) {
    if (target == null) throw new TypeError('Cannot convert undefined or null to object');
    for (var i = 1; i < arguments.length; i++) {
      var source = arguments[i];
      if (source != null) for (var key in source) {
        if (Object.prototype.hasOwnProperty.call(source, key)) target[key] = source[key];
      }
    }
    return target;
  };
}
if (typeof Object.values !== 'function') {
  Object.values = function(obj) {
    var keys = Object.keys(obj);
    var result = [];
    for (var i = 0; i < keys.length; i++) result.push(obj[keys[i]]);
    return result;
  };
}
if (typeof Object.entries !== 'function') {
  Object.entries = function(obj) {
    var keys = Object.keys(obj);
    var result = [];
    for (var i = 0; i < keys.length; i++) result.push([keys[i], obj[keys[i]]]);
    return result;
  };
}
if (typeof Array.prototype.find !== 'function') {
  Array.prototype.find = function(callback) {
    for (var i = 0; i < this.length; i++) {
      if (callback(this[i], i, this)) return this[i];
    }
    return undefined;
  };
}
if (typeof Array.prototype.findIndex !== 'function') {
  Array.prototype.findIndex = function(callback) {
    for (var i = 0; i < this.length; i++) {
      if (callback(this[i], i, this)) return i;
    }
    return -1;
  };
}
if (typeof Array.prototype.includes !== 'function') {
  Array.prototype.includes = function(item) {
    return this.indexOf(item) !== -1;
  };
}
if (typeof Array.prototype.flat !== 'function') {
  Array.prototype.flat = function(depth) {
    depth = depth || 1;
    var result = [];
    var flatten = function(arr, d) {
      for (var i = 0; i < arr.length; i++) {
        if (Array.isArray(arr[i]) && d > 0) flatten(arr[i], d - 1);
        else result.push(arr[i]);
      }
    };
    flatten(this, depth);
    return result;
  };
}
if (typeof String.prototype.includes !== 'function') {
  String.prototype.includes = function(str, start) {
    return this.indexOf(str, start || 0) !== -1;
  };
}
if (typeof String.prototype.startsWith !== 'function') {
  String.prototype.startsWith = function(str, start) {
    return this.substring(0, (start || 0) + str.length) === str;
  };
}
if (typeof String.prototype.endsWith !== 'function') {
  String.prototype.endsWith = function(str) {
    return this.indexOf(str, this.length - str.length) !== -1;
  };
}
if (typeof String.prototype.repeat !== 'function') {
  String.prototype.repeat = function(n) {
    var result = '';
    for (var i = 0; i < n; i++) result += this;
    return result;
  };
}
if (typeof String.prototype.padStart !== 'function') {
  String.prototype.padStart = function(targetLength, padString) {
    padString = padString || ' ';
    var str = String(this);
    while (str.length < targetLength) str = padString + str;
    return str.substring(str.length - targetLength);
  };
}
if (typeof String.prototype.padEnd !== 'function') {
  String.prototype.padEnd = function(targetLength, padString) {
    padString = padString || ' ';
    var str = String(this);
    while (str.length < targetLength) str = str + padString;
    return str.substring(0, targetLength);
  };
}
if (typeof Number.isInteger !== 'function') {
  Number.isInteger = function(val) {
    return typeof val === 'number' && isFinite(val) && Math.floor(val) === val;
  };
}
if (typeof Number.isNaN !== 'function') {
  Number.isNaN = function(val) {
    return typeof val === 'number' && val !== val;
  };
}
"""
