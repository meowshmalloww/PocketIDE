package com.pocketide.ui.screens.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    val batteryLevel: Int = 100,
    val activeTab: ActivityTab = ActivityTab.EXPLORER,
    val terminalExpanded: Boolean = false,
    val projectName: String = "default",
    val unsavedCount: Int = 0,
)

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = FileRepository(application)
    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    init {
        loadFiles()
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

    fun toggleTerminal() {
        _state.update { it.copy(terminalExpanded = !it.terminalExpanded) }
    }

    fun onInputChange(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    fun sendMessage() {
        val prompt = _state.value.inputText.trim()
        if (prompt.isBlank()) return

        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(role = MessageRole.USER, content = prompt),
                inputText = "",
                isGenerating = true,
                architectStatus = AgentStatus.LOADING,
                activeTab = ActivityTab.AI_CHAT,
            )
        }

        // Placeholder: actual AI inference in Phase 2
        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(
                    role = MessageRole.ARCHITECT,
                    content = "[Architect] Plan: Create a simple Python script.\nFile: main.py\nLanguage: Python",
                    agentStatus = AgentStatus.DONE,
                ),
                architectStatus = AgentStatus.DONE,
                coderStatus = AgentStatus.GENERATING,
            )
        }

        val sampleCode = """def greet(name):
    print(f"Hello, {name}!")

greet("World")"""

        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(
                    role = MessageRole.CODER,
                    content = "[Coder] Generated main.py:\n$sampleCode",
                    agentStatus = AgentStatus.DONE,
                ),
                coderStatus = AgentStatus.DONE,
                files = listOf(
                    CodeFile(name = "main.py", language = Language.PYTHON, content = sampleCode),
                ),
                activeFileIndex = 0,
                activeFileContent = sampleCode,
                isGenerating = false,
            )
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
        _state.update {
            it.copy(
                executionStatus = ExecutionStatus.RUNNING,
                stdout = "",
                stderr = "",
                terminalExpanded = true,
            )
        }
        // Placeholder: actual sandbox execution in Phase 4
        _state.update {
            it.copy(
                executionStatus = ExecutionStatus.PASSED,
                stdout = "Hello, World!\n",
                stderr = "",
            )
        }
    }

    fun retryRepair() {
        _state.update {
            it.copy(
                validatorStatus = AgentStatus.GENERATING,
                executionStatus = ExecutionStatus.IDLE,
            )
        }
        // Placeholder: actual repair loop in Phase 3
        _state.update {
            it.copy(
                validatorStatus = AgentStatus.DONE,
            )
        }
    }
}
