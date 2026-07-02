package com.pocketide.ui.screens.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketide.data.ai.AiConfigRepository
import com.pocketide.data.ai.AiResult
import com.pocketide.data.ai.AiService
import com.pocketide.data.ai.ChatTurn
import com.pocketide.data.ai.parseAiResponse
import com.pocketide.data.execution.CodeExecutor
import com.pocketide.data.model.AgentStatus
import com.pocketide.data.model.ChatMessage
import com.pocketide.data.model.CodeFile
import com.pocketide.data.model.ExecutionStatus
import com.pocketide.data.model.Language
import com.pocketide.data.model.MessageRole
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
    val executionStatus: ExecutionStatus = ExecutionStatus.IDLE,
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
)

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = FileRepository(application)
    private val codeExecutor = CodeExecutor()
    private val aiConfigRepository = AiConfigRepository(application)
    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

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

    fun onInputChange(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    fun sendMessage() {
        val prompt = _state.value.inputText.trim()
        if (prompt.isBlank()) return

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
                architectStatus = AgentStatus.LOADING,
                coderStatus = AgentStatus.IDLE,
                activeTab = ActivityTab.AI_CHAT,
            )
        }

        viewModelScope.launch {
            val config = aiConfigRepository.load()
            val service = AiService(config)
            val result = service.chatCompletion(
                systemPrompt = AI_SYSTEM_PROMPT,
                history = historyForRequest,
                userMessage = prompt,
            )

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
                    )
                }

                is AiResult.Success -> applyAiResponse(result.content)
            }
        }
    }

    private fun applyAiResponse(rawContent: String) {
        val parsed = parseAiResponse(rawContent)

        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(
                    role = MessageRole.ARCHITECT,
                    content = parsed.plan ?: rawContent,
                    agentStatus = AgentStatus.DONE,
                ),
                architectStatus = AgentStatus.DONE,
                coderStatus = if (parsed.code != null) AgentStatus.GENERATING else AgentStatus.IDLE,
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
                ),
                coderStatus = AgentStatus.DONE,
                files = newFiles,
                activeFileIndex = targetIndex,
                activeFileContent = code,
                isGenerating = false,
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

    fun createNewFile(name: String? = null, language: Language? = null) {
        val currentFiles = _state.value.files
        val resolvedName = name ?: "untitled${currentFiles.size + 1}.${language?.fileExtension ?: "py"}"
        val resolvedLang = language ?: Language.fromExtension(resolvedName.substringAfterLast('.', "")) ?: Language.PYTHON

        val newFile = CodeFile(
            name = resolvedName,
            language = resolvedLang,
        )
        _state.update {
            it.copy(
                files = it.files + newFile,
                activeFileIndex = it.files.size,
                activeFileContent = "",
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
            val service = AiService(config)
            val repairPrompt = "The following ${activeFile.language.displayName} code failed with this error:\n" +
                "${s.stderr}\n\nCode:\n${activeFile.content}\n\n" +
                "Fix the code. Respond using the same PLAN/FILENAME/code block format."

            val result = service.chatCompletion(
                systemPrompt = AI_SYSTEM_PROMPT,
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

private const val AI_SYSTEM_PROMPT = """You are the coding assistant inside PocketIDE, an on-device Android IDE.
When the user asks you to write or modify code, respond in exactly this format:

PLAN: <one-sentence description of what you will do>
FILENAME: <filename with extension, e.g. main.py>
```<language>
<the full file content>
```

Only include one code block. Keep the plan to a single line. Do not add extra commentary outside this format."""
