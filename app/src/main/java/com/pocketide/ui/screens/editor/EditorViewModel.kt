package com.pocketide.ui.screens.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketide.data.ai.AiConfigRepository
import com.pocketide.data.ai.AiResult
import com.pocketide.data.ai.AiService
import com.pocketide.data.ai.ChatTurn
import com.pocketide.data.ai.ExecutorchLlmRunner
import com.pocketide.data.ai.LlamaCppRunner
import com.pocketide.data.ai.parseAiResponse
import com.pocketide.data.execution.CodeExecutor
import com.pocketide.data.model.AgentStatus
import com.pocketide.data.model.AiMode
import com.pocketide.data.model.ChatMessage
import com.pocketide.data.model.CodeFile
import com.pocketide.data.model.ExecutionStatus
import com.pocketide.data.model.Language
import com.pocketide.data.model.MessageRole
import com.pocketide.data.model.ModelMode
import com.pocketide.data.repository.FileRepository
import com.pocketide.ui.components.ActivityTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditorUiState(
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isGenerating: Boolean = false,
    val files: List<CodeFile> = emptyList(),
    val activeFileIndex: Int = 0,
    val activeFileContent: String = "",
    val stdout: String = "",
    val stderr: String = "",
    val warnings: String = "",
    val executionStatus: ExecutionStatus = ExecutionStatus.IDLE,
    val errorLine: Int? = null,
    val errorColumn: Int? = null,
    val errorType: String? = null,
    val executionDurationMs: Long = 0,
    val architectStatus: AgentStatus = AgentStatus.IDLE,
    val coderStatus: AgentStatus = AgentStatus.IDLE,
    val validatorStatus: AgentStatus = AgentStatus.IDLE,
    val activeTab: ActivityTab = ActivityTab.EDITOR,
    val terminalExpanded: Boolean = true,
    val projectName: String = "default",
    val unsavedCount: Int = 0,
    val projects: List<String> = emptyList(),
    val showProjectDialog: Boolean = false,
    val showExplorer: Boolean = true,
    val isAiTabActive: Boolean = false,
    val showAiOverlay: Boolean = false,
    val aiMode: AiMode = AiMode.CODE,
    val modelMode: ModelMode = ModelMode.SINGLE,
    val isThinking: Boolean = false,
    val lastTokensPerSecond: Float? = null,
)

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = FileRepository(application)
    private val codeExecutor = CodeExecutor(application)
    private val aiConfigRepository = AiConfigRepository(application)
    private val executorchRunner = ExecutorchLlmRunner()
    private val llamaCppRunner = LlamaCppRunner(application)
    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    override fun onCleared() {
        super.onCleared()
        // Release native LLM resources when the ViewModel is destroyed.
        viewModelScope.launch {
            executorchRunner.release()
            llamaCppRunner.release()
        }
    }

    init {
        loadProjects()
        loadFiles()
    }

    private fun loadProjects() {
        viewModelScope.launch {
            val projects = repository.listProjects()
            val currentName = _state.value.projectName
            val updated = if (projects.contains(currentName)) projects else projects + currentName
            _state.update { it.copy(projects = updated) }
        }
    }

    fun showProjectDialog() {
        loadProjects()
        _state.update { it.copy(showProjectDialog = true) }
    }

    fun hideProjectDialog() {
        _state.update { it.copy(showProjectDialog = false) }
    }

    fun createProject(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            repository.createProject(trimmed)
            val updated = repository.listProjects()
            _state.update {
                it.copy(
                    projects = updated,
                    projectName = trimmed,
                    showProjectDialog = false,
                    files = emptyList(),
                    activeFileIndex = 0,
                    activeFileContent = "",
                    messages = emptyList(),
                    stdout = "",
                    stderr = "",
                    executionStatus = ExecutionStatus.IDLE,
                    unsavedCount = 0,
                )
            }
            loadFiles()
        }
    }

    fun switchProject(name: String) {
        if (name == _state.value.projectName) {
            _state.update { it.copy(showProjectDialog = false) }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    projectName = name,
                    showProjectDialog = false,
                    files = emptyList(),
                    activeFileIndex = 0,
                    activeFileContent = "",
                    messages = emptyList(),
                    stdout = "",
                    stderr = "",
                    executionStatus = ExecutionStatus.IDLE,
                    unsavedCount = 0,
                )
            }
            loadFiles()
        }
    }

    fun deleteProject(name: String) {
        val current = _state.value
        if (current.projects.size <= 1 && name == current.projectName) return
        viewModelScope.launch {
            repository.deleteProject(name)
            val updated = repository.listProjects()
            if (name == current.projectName && updated.isNotEmpty()) {
                _state.update {
                    it.copy(
                        projects = updated,
                        projectName = updated.first(),
                        files = emptyList(),
                        activeFileIndex = 0,
                        activeFileContent = "",
                        messages = emptyList(),
                        stdout = "",
                        stderr = "",
                        executionStatus = ExecutionStatus.IDLE,
                        unsavedCount = 0,
                    )
                }
                loadFiles()
            } else {
                _state.update { it.copy(projects = updated) }
            }
        }
    }

    private fun loadFiles() {
        viewModelScope.launch {
            val files = repository.listFiles(_state.value.projectName)
            if (files.isNotEmpty()) {
                _state.update {
                    it.copy(
                        files = files,
                        activeFileIndex = 0,
                        activeFileContent = files[0].content,
                    )
                }
            } else {
                createNewFile("main.py", Language.PYTHON)
            }
        }
    }

    fun setActiveTab(tab: ActivityTab) {
        _state.update { it.copy(activeTab = tab) }
    }

    fun toggleExplorer() {
        _state.update { it.copy(showExplorer = !it.showExplorer) }
    }

    fun setAiTabActive(active: Boolean) {
        _state.update { it.copy(isAiTabActive = active) }
    }

    fun toggleTerminal() {
        _state.update { it.copy(terminalExpanded = !it.terminalExpanded) }
    }

    fun toggleAiOverlay() {
        _state.update { it.copy(showAiOverlay = !it.showAiOverlay) }
    }

    fun setAiMode(mode: AiMode) {
        _state.update { it.copy(aiMode = mode) }
    }

    fun setModelMode(mode: ModelMode) {
        _state.update { it.copy(modelMode = mode) }
    }

    fun onInputChange(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    fun newChat() {
        _state.update {
            it.copy(
                messages = emptyList(),
                inputText = "",
                isGenerating = false,
                isThinking = false,
                lastTokensPerSecond = null,
                architectStatus = AgentStatus.IDLE,
                coderStatus = AgentStatus.IDLE,
                validatorStatus = AgentStatus.IDLE,
            )
        }
    }

    fun sendMessage() {
        val prompt = _state.value.inputText.trim()
        if (prompt.isBlank()) return

        val currentMode = _state.value.aiMode
        val historyForRequest = _state.value.messages.mapNotNull { message ->
            when (message.role) {
                MessageRole.USER -> ChatTurn(role = "user", content = message.content)
                MessageRole.ARCHITECT, MessageRole.CODER, MessageRole.VALIDATOR, MessageRole.ASSISTANT ->
                    ChatTurn(role = "assistant", content = message.content)
                MessageRole.SYSTEM -> null
            }
        }

        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(role = MessageRole.USER, content = prompt),
                inputText = "",
                isGenerating = true,
                isThinking = true,
                architectStatus = AgentStatus.LOADING,
                coderStatus = AgentStatus.IDLE,
                activeTab = ActivityTab.AI_CHAT,
            )
        }

        viewModelScope.launch {
            val config = aiConfigRepository.load()
            val service = AiService(executorchRunner, llamaCppRunner, config)
            val systemPrompt = when (currentMode) {
                AiMode.CODE -> CODE_MODE_PROMPT
                AiMode.ASK -> ASK_MODE_PROMPT
                AiMode.PLAN -> PLAN_MODE_PROMPT
            }

            val startTime = System.currentTimeMillis()
            var tokenCount = 0
            val tokenInterval = System.currentTimeMillis()

            val result = service.chatCompletion(
                systemPrompt = systemPrompt,
                history = historyForRequest,
                userMessage = prompt,
                onToken = { _ ->
                    if (_state.value.isThinking) {
                        _state.update { it.copy(isThinking = false) }
                    }
                    tokenCount++
                },
            )

            val elapsedSec = (System.currentTimeMillis() - startTime) / 1000f
            val tps = if (elapsedSec > 0f) tokenCount / elapsedSec else null

            when (result) {
                is AiResult.Error -> _state.update {
                    it.copy(
                        messages = it.messages + ChatMessage(
                            role = MessageRole.SYSTEM,
                            content = "Error: ${result.message}",
                            agentStatus = AgentStatus.ERROR,
                        ),
                        architectStatus = AgentStatus.ERROR,
                        isGenerating = false,
                        isThinking = false,
                    )
                }

                is AiResult.Success -> {
                    when (currentMode) {
                        AiMode.CODE -> applyAiResponse(result.content, tps)
                        AiMode.ASK -> _state.update {
                            it.copy(
                                messages = it.messages + ChatMessage(
                                    role = MessageRole.ASSISTANT,
                                    content = result.content,
                                    agentStatus = AgentStatus.DONE,
                                    tokensPerSecond = tps,
                                ),
                                architectStatus = AgentStatus.DONE,
                                isGenerating = false,
                                isThinking = false,
                                lastTokensPerSecond = tps,
                            )
                        }
                        AiMode.PLAN -> _state.update {
                            it.copy(
                                messages = it.messages + ChatMessage(
                                    role = MessageRole.ARCHITECT,
                                    content = result.content,
                                    agentStatus = AgentStatus.DONE,
                                    tokensPerSecond = tps,
                                ),
                                architectStatus = AgentStatus.DONE,
                                isGenerating = false,
                                isThinking = false,
                                lastTokensPerSecond = tps,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun applyAiResponse(rawContent: String, tokensPerSecond: Float? = null) {
        val parsed = parseAiResponse(rawContent)

        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(
                    role = MessageRole.ARCHITECT,
                    content = parsed.plan ?: rawContent,
                    agentStatus = AgentStatus.DONE,
                    tokensPerSecond = tokensPerSecond,
                ),
                architectStatus = AgentStatus.DONE,
                coderStatus = if (parsed.code != null) AgentStatus.GENERATING else AgentStatus.IDLE,
                isThinking = false,
            )
        }

        val code = parsed.code
        val language = parsed.language
        if (code == null || language == null) {
            _state.update { it.copy(isGenerating = false) }
            return
        }

        val filename = parsed.filename ?: "main.${language.fileExtension}"
        val s = _state.value
        val existingIndex = s.files.indexOfFirst { it.name == filename }
        val newFiles = s.files.toMutableList()
        val targetIndex: Int
        if (existingIndex >= 0) {
            newFiles[existingIndex] = newFiles[existingIndex].copy(content = code, isModified = true)
            targetIndex = existingIndex
        } else {
            newFiles.add(CodeFile(name = filename, language = language, content = code, isModified = true))
            targetIndex = newFiles.size - 1
        }

        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(
                    role = MessageRole.CODER,
                    content = "Generated $filename:\n```${language.fileExtension}\n$code\n```",
                    agentStatus = AgentStatus.DONE,
                    tokensPerSecond = tokensPerSecond,
                ),
                coderStatus = AgentStatus.DONE,
                files = newFiles,
                activeFileIndex = targetIndex,
                activeFileContent = code,
                isGenerating = false,
                isThinking = false,
                lastTokensPerSecond = tokensPerSecond,
                unsavedCount = newFiles.count { f -> f.isModified },
            )
        }

        viewModelScope.launch {
            repository.saveFile(s.projectName, newFiles[targetIndex])
        }
    }

    fun selectFile(index: Int) {
        _state.update {
            val file = it.files.getOrNull(index) ?: return@update it
            it.copy(
                activeFileIndex = index,
                activeFileContent = file.content,
            )
        }
    }

    fun closeFile(index: Int) {
        _state.update {
            if (index !in it.files.indices) return@update it
            val newFiles = it.files.toMutableList()
            newFiles.removeAt(index)
            val newIndex = if (index <= it.activeFileIndex) {
                (it.activeFileIndex - 1).coerceAtLeast(0)
            } else {
                it.activeFileIndex
            }
            val newContent = newFiles.getOrNull(newIndex)?.content ?: ""
            it.copy(
                files = newFiles,
                activeFileIndex = newIndex,
                activeFileContent = newContent,
            )
        }
    }

    fun createNewFile(name: String? = null, language: Language? = null, content: String = "") {
        val currentFiles = _state.value.files
        val resolvedName = name ?: "untitled${currentFiles.size + 1}.${language?.fileExtension ?: "py"}"
        val resolvedLang = language ?: Language.fromExtension(resolvedName.substringAfterLast('.', "")) ?: Language.PYTHON

        val newFile = CodeFile(
            name = resolvedName,
            language = resolvedLang,
            content = content,
        )
        _state.update {
            it.copy(
                files = it.files + newFile,
                activeFileIndex = it.files.size,
                activeFileContent = content,
            )
        }
        val projectName = _state.value.projectName
        viewModelScope.launch {
            repository.saveFile(projectName, newFile)
        }
    }

    fun saveActiveFile() {
        val s = _state.value
        val file = s.files.getOrNull(s.activeFileIndex) ?: return
        viewModelScope.launch {
            repository.saveFile(s.projectName, file)
            _state.update {
                val newFiles = it.files.toMutableList()
                if (it.activeFileIndex in newFiles.indices) {
                    newFiles[it.activeFileIndex] = newFiles[it.activeFileIndex].copy(isModified = false)
                }
                it.copy(
                    files = newFiles,
                    unsavedCount = newFiles.count { f -> f.isModified },
                )
            }
        }
    }

    fun saveAllFiles() {
        val s = _state.value
        viewModelScope.launch {
            s.files.forEach { file ->
                repository.saveFile(s.projectName, file)
            }
            _state.update {
                val newFiles = it.files.map { f -> f.copy(isModified = false) }
                it.copy(files = newFiles, unsavedCount = 0)
            }
        }
    }

    fun deleteFile(index: Int) {
        val s = _state.value
        val file = s.files.getOrNull(index) ?: return
        viewModelScope.launch {
            repository.deleteFile(s.projectName, file.name)
            _state.update {
                if (index !in it.files.indices) return@update it
                val newFiles = it.files.toMutableList()
                newFiles.removeAt(index)
                val newIndex = if (index <= it.activeFileIndex) {
                    (it.activeFileIndex - 1).coerceAtLeast(0)
                } else {
                    it.activeFileIndex
                }
                val newContent = newFiles.getOrNull(newIndex)?.content ?: ""
                it.copy(
                    files = newFiles,
                    activeFileIndex = newIndex,
                    activeFileContent = newContent,
                )
            }
        }
    }

    fun onCodeChange(newContent: String) {
        _state.update {
            val newFiles = it.files.toMutableList()
            if (it.activeFileIndex in newFiles.indices) {
                newFiles[it.activeFileIndex] = newFiles[it.activeFileIndex].copy(
                    content = newContent,
                    isModified = true,
                )
            }
            it.copy(
                files = newFiles,
                activeFileContent = newContent,
                unsavedCount = newFiles.count { f -> f.isModified },
            )
        }
    }

    fun runCode() {
        val s = _state.value
        val activeFile = s.files.getOrNull(s.activeFileIndex)
        if (activeFile == null) return

        _state.update {
            it.copy(
                executionStatus = ExecutionStatus.RUNNING,
                stdout = "",
                stderr = "",
                warnings = "",
                errorLine = null,
                errorColumn = null,
                errorType = null,
                terminalExpanded = true,
            )
        }

        viewModelScope.launch {
            val result = codeExecutor.execute(activeFile.content, activeFile.language)
            _state.update {
                it.copy(
                    executionStatus = result.status,
                    stdout = result.stdout,
                    stderr = result.stderr,
                    warnings = result.warnings,
                    errorLine = result.errorLine,
                    errorColumn = result.errorColumn,
                    errorType = result.errorType,
                    executionDurationMs = result.durationMs,
                )
            }
        }
    }

    fun retryRepair() {
        val s = _state.value
        val activeFile = s.files.getOrNull(s.activeFileIndex) ?: return
        if (s.stderr.isBlank()) return

        _state.update {
            it.copy(
                validatorStatus = AgentStatus.GENERATING,
                executionStatus = ExecutionStatus.IDLE,
            )
        }

        viewModelScope.launch {
            val config = aiConfigRepository.load()
            val service = AiService(executorchRunner, llamaCppRunner, config)
            val repairPrompt = buildString {
                appendLine("The following ${activeFile.language.displayName} code failed with this error:")
                appendLine("Error type: ${s.errorType ?: "Unknown"}")
                if (s.errorLine != null) {
                    appendLine("Error at line: ${s.errorLine}${if (s.errorColumn != null) ", column: ${s.errorColumn}" else ""}")
                }
                if (s.warnings.isNotBlank()) {
                    appendLine("Warnings: ${s.warnings}")
                }
                appendLine("Error message: ${s.stderr}")
                appendLine("Execution time: ${s.executionDurationMs}ms")
                appendLine()
                appendLine("Code:")
                appendLine(activeFile.content)
                appendLine()
                appendLine("Fix the code. Respond using the same PLAN/FILENAME/code block format.")
            }

            val result = service.chatCompletion(
                systemPrompt = CODE_MODE_PROMPT,
                history = emptyList(),
                userMessage = repairPrompt,
            )

            when (result) {
                is AiResult.Error -> _state.update {
                    it.copy(
                        validatorStatus = AgentStatus.ERROR,
                        messages = it.messages + ChatMessage(
                            role = MessageRole.SYSTEM,
                            content = "Repair failed: ${result.message}",
                            agentStatus = AgentStatus.ERROR,
                        ),
                    )
                }

                is AiResult.Success -> {
                    applyAiResponse(result.content)
                    _state.update { it.copy(validatorStatus = AgentStatus.DONE) }
                    runCode()
                }
            }
        }
    }
}

private const val CODE_MODE_PROMPT = """You are the coding assistant inside PocketIDE, an on-device Android IDE that runs code directly on the phone.

When the user asks you to write or modify code, respond in EXACTLY this format:

PLAN: <one-sentence description of what you will do>
FILENAME: <filename with extension, e.g. main.py>
```<language>
<the full file content>
```

Only include ONE code block. Keep the plan to a single line. Do not add commentary outside this format.

SUPPORTED LANGUAGES (pick the best fit): python, javascript, typescript, lua, sql, java, shell.

HARDWARE BRIDGE — available in javascript, lua, java via a global `hardware` object:
  hardware.toast(msg)             show a short toast
  hardware.toastLong(msg)         show a long toast
  hardware.vibrate(ms)            vibrate for N milliseconds (default 200)
  hardware.setFlashlight(bool)    turn torch on/off, returns true on success
  hardware.batteryLevel()         battery percent 0..100, or -1
  hardware.isCharging()           true if charging
  hardware.clipboardGet()         read clipboard text
  hardware.clipboardSet(text)     write clipboard
  hardware.screenInfo()           "WxH, density"
  hardware.networkType()          "wifi" | "cellular" | "ethernet" | "none"
  hardware.isOnline()             boolean
  hardware.storageFree()          free bytes
  hardware.storageTotal()         total bytes
  hardware.readSensor(type, ms)   type: accelerometer|gyroscope|light|pressure|proximity|magnetic
  hardware.getDeviceInfo()        multi-line device summary
  hardware.openUrl(url)           opens in the default browser

Use `hardware` freely when the user asks for anything device-related (flashlight, vibrate, battery, sensors, etc.).

JAVASCRIPT / TYPESCRIPT RULES — the runtime is Mozilla Rhino (ES5 only). ALWAYS:
- Use var (never let/const)
- Use function expressions (never arrow =>)
- Use string concatenation with + (never backtick template literals)
- Use index-based for loops (never for...of, for...in on arrays)
- Avoid destructuring, spread, default parameters, classes, async/await, Promise, fetch
- Output via console.log(...)

PYTHON RULES — transpiled to JS. Use simple Python 3:
- print(x), for x in range(n), if/elif/else, while, def, return
- Basic strings, lists, dicts, arithmetic. Avoid f-strings, comprehensions, decorators, imports.

LUA — full Lua 5.2 standard library. Use print(...) for output.
SQL — standard SQLite. Use CREATE TABLE, INSERT, SELECT.
SHELL — POSIX sh, echo for output.
JAVA — BeanShell scripting (no class boilerplate needed). Use System.out.println(...)."""

private const val ASK_MODE_PROMPT = """You are a knowledgeable assistant inside PocketIDE, an on-device Android IDE.
The user is in ASK mode — they want explanations, not file modifications.
Answer questions about code, explain concepts, review snippets, and provide guidance.
Do NOT write code blocks or attempt to create files. Respond in plain text.
If the user asks you to write code, suggest they switch to CODE mode."""

private const val PLAN_MODE_PROMPT = """You are a planning assistant inside PocketIDE, an on-device Android IDE.
The user is in PLAN mode — they want a detailed plan before any code is written.
Analyze the request and respond with a structured implementation plan:

1. Break down the task into steps
2. List files that need to be created or modified
3. Describe the approach for each file
4. Note any potential issues or edge cases

Do NOT write code blocks. The user will switch to CODE mode to implement the plan."""
