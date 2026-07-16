package com.pocketide.data.execution

import java.util.concurrent.LinkedBlockingQueue

/** Thread-safe stdin/stdout bridge shared by the embedded runtimes and Terminal UI. */
class ExecutionConsole(
    private val onStdout: (String) -> Unit = {},
    private val onStderr: (String) -> Unit = {},
    private val onInputRequested: (String) -> Unit = {},
) {
    private val input = LinkedBlockingQueue<String>()
    private val stdout = StringBuilder()
    private val stderr = StringBuilder()

    @Volatile
    var waitingForInput: Boolean = false
        private set

    @Volatile
    var inputPrompt: String = ""
        private set

    @Volatile
    private var cancelled = false

    @Synchronized
    fun writeStdout(value: String?) {
        if (!value.isNullOrEmpty()) {
            stdout.append(value)
            onStdout(value)
        }
    }

    @Synchronized
    fun writeStderr(value: String?) {
        if (!value.isNullOrEmpty()) {
            stderr.append(value)
            onStderr(value)
        }
    }

    @Synchronized
    fun stdoutText(): String = stdout.toString()

    @Synchronized
    fun stderrText(): String = stderr.toString()

    /** Called from a runtime worker thread and intentionally blocks until Terminal submits a line. */
    fun readLine(prompt: String?): String {
        if (cancelled) throw IllegalStateException("Execution cancelled")
        inputPrompt = prompt.orEmpty()
        waitingForInput = true
        onInputRequested(inputPrompt)
        return try {
            val value = input.take()
            if (cancelled) throw IllegalStateException("Execution cancelled")
            value
        } finally {
            waitingForInput = false
            inputPrompt = ""
            onInputRequested("")
        }
    }

    fun submitInput(value: String): Boolean {
        if (!waitingForInput || cancelled) return false
        writeStdout("$value\n")
        input.put(value)
        return true
    }

    fun cancel() {
        cancelled = true
        input.offer("")
    }
}
