package com.pocketide.data.model

enum class ExecutionStatus {
    IDLE,
    RUNNING,
    PASSED,
    FAILED,
}

data class ExecutionResult(
    val status: ExecutionStatus,
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = 0,
    val durationMs: Long = 0,
    val errorLine: Int? = null,
)
