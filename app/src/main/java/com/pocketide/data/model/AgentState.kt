package com.pocketide.data.model

enum class AgentRole(
    val displayName: String,
    val description: String,
) {
    ARCHITECT("Architect", "Plans the code structure and approach"),
    CODER("Coder", "Generates code from the plan"),
    VALIDATOR("Validator", "Analyzes errors and suggests repairs"),
}

enum class AgentStatus {
    IDLE,
    LOADING,
    GENERATING,
    DONE,
    ERROR,
}

data class AgentState(
    val role: AgentRole,
    val status: AgentStatus = AgentStatus.IDLE,
    val progress: Float = 0f,
    val message: String = "",
)
