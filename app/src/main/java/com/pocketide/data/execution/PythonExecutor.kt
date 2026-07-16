package com.pocketide.data.execution

import android.content.Context
import android.os.SystemClock
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.pocketide.data.hardware.HardwareBridge
import com.pocketide.data.model.ExecutionResult
import com.pocketide.data.model.ExecutionStatus
import java.io.File

/** Executes genuine CPython 3.11 code through Chaquopy, including imports and interactive input(). */
class PythonExecutor(private val context: Context?) {

    fun execute(request: ExecutionRequest, startTime: Long): ExecutionResult {
        val androidContext = context ?: return unavailable(startTime)
        val workingDirectory = request.projectDirectory
            ?: File(androidContext.filesDir, "python_workspace").apply { mkdirs() }

        return try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(androidContext.applicationContext))
            }
            val result = Python.getInstance()
                .getModule("pocketide_runner")
                .callAttr(
                    "run_code",
                    request.code,
                    request.fileName,
                    workingDirectory.absolutePath,
                    request.console,
                    HardwareBridge(androidContext),
                )
                .asList()

            val succeeded = result[0].toBoolean()
            val stderr = result[1].toString()
            val errorLine = result[2].toInt().takeIf { it > 0 }
            val errorType = result[3].toString().ifBlank { null }
            ExecutionResult(
                status = if (succeeded) ExecutionStatus.PASSED else ExecutionStatus.FAILED,
                stdout = request.console.stdoutText(),
                stderr = buildString {
                    append(request.console.stderrText())
                    if (stderr.isNotBlank()) {
                        if (isNotEmpty() && !endsWith("\n")) appendLine()
                        append(stderr)
                    }
                },
                exitCode = if (succeeded) 0 else 1,
                durationMs = SystemClock.elapsedRealtime() - startTime,
                errorLine = errorLine,
                errorType = errorType,
            )
        } catch (error: Throwable) {
            ExecutionResult(
                status = ExecutionStatus.FAILED,
                stderr = error.message ?: error.javaClass.simpleName,
                exitCode = 1,
                durationMs = SystemClock.elapsedRealtime() - startTime,
                errorType = error.javaClass.simpleName,
            )
        }
    }

    private fun unavailable(startTime: Long) = ExecutionResult(
        status = ExecutionStatus.FAILED,
        stderr = "The CPython runtime requires an Android application context.",
        exitCode = -1,
        durationMs = SystemClock.elapsedRealtime() - startTime,
        errorType = "RuntimeUnavailable",
    )
}
