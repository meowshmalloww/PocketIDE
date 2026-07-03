package com.pocketide.data.model

enum class AiMode(val displayName: String, val description: String) {
    CODE("Code", "Writes and edits code files"),
    ASK("Ask", "Reads and explains — does not modify files"),
    PLAN("Plan", "Plans changes before implementing them"),
}

enum class ModelMode(val displayName: String, val description: String) {
    SINGLE("Single Model", "One powerful model handles all tasks"),
    SWARM("Swarm Agent", "3+ specialized models collaborate on tasks"),
}
