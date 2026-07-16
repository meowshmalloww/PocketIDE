package com.pocketide.ui.screens.editor

import android.app.Application
import android.content.Intent
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketide.data.ai.AiConfigRepository
import com.pocketide.data.ai.AiResult
import com.pocketide.data.ai.AiService
import com.pocketide.data.ai.BackendInfo
import com.pocketide.data.ai.BenchmarkRunOptions
import com.pocketide.data.ai.ChatTurn
import com.pocketide.data.ai.ContextManager
import com.pocketide.data.ai.ExecutorchLlmRunner
import com.pocketide.data.ai.LlamaCppRunner
import com.pocketide.data.ai.ModelFormat
import com.pocketide.data.ai.ThreadCalibrationRepository
import com.pocketide.data.ai.ThreadProfileSample
import com.pocketide.data.ai.ThreadProfileSelector
import com.pocketide.data.ai.parseAiResponse
import com.pocketide.data.execution.CodeExecutor
import com.pocketide.data.execution.ExecutionConsole
import com.pocketide.data.execution.ExecutionRequest
import com.pocketide.data.execution.ProjectPreviewServer
import com.pocketide.data.model.AgentStatus
import com.pocketide.data.model.AiMode
import com.pocketide.data.model.ChatMessage
import com.pocketide.data.model.ChatSessionSummary
import com.pocketide.data.model.CodeFile
import com.pocketide.data.model.ExecutionStatus
import com.pocketide.data.model.Language
import com.pocketide.data.model.MessageRole
import com.pocketide.data.model.ModelMode
import com.pocketide.data.repository.FileRepository
import com.pocketide.data.repository.ChatHistoryRepository
import com.pocketide.ui.components.ActivityTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.UUID
import java.util.Locale

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
    val lastTtftMs: Long? = null,
    val lastMemoryDeltaMb: Float? = null,
    val lastStrategy: String? = null,
    val lastPipelineLog: String? = null,
    val benchmarkReport: String? = null,
    val benchmarkJson: String? = null,
    val benchmarkRunning: Boolean = false,
    val benchmarkCompletedRuns: Int = 0,
    val benchmarkTotalRuns: Int = 0,
    val benchmarkSummary: String? = null,
    val benchmarkError: String? = null,
    val chatSessions: List<ChatSessionSummary> = emptyList(),
    val activeChatSessionId: String = UUID.randomUUID().toString(),
    val previewUrl: String? = null,
    val waitingForInput: Boolean = false,
    val inputPrompt: String = "",
)

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = FileRepository(application)
    private val codeExecutor = CodeExecutor(application)
    private val previewServer = ProjectPreviewServer(codeExecutor)
    private val aiConfigRepository = AiConfigRepository(application)
    private val threadCalibrationRepository = ThreadCalibrationRepository(application)
    private val chatHistoryRepository = ChatHistoryRepository(application)
    private val executorchRunner = ExecutorchLlmRunner()
    private val llamaCppRunner = LlamaCppRunner(application)
    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()
    private var cachedService: AiService? = null
    private var cachedConfigHash: Int = 0
    private var activeExecutionConsole: ExecutionConsole? = null

    override fun onCleared() {
        activeExecutionConsole?.cancel()
        // viewModelScope is already being cancelled during clear, so native cleanup uses a short-lived independent scope.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            executorchRunner.release()
            llamaCppRunner.release()
        }
        previewServer.stop()
        super.onCleared()
    }

    init {
        loadProjects()
        loadFiles()
        loadChatHistory()
        observeChatHistory()
    }

    private data class PersistedChatSnapshot(
        val id: String,
        val projectName: String,
        val messages: List<ChatMessage>,
    )

    private fun observeChatHistory() {
        viewModelScope.launch {
            state.map { PersistedChatSnapshot(it.activeChatSessionId, it.projectName, it.messages) }
                .distinctUntilChanged()
                .collect { snapshot ->
                    if (snapshot.messages.isNotEmpty()) {
                        chatHistoryRepository.save(snapshot.id, snapshot.projectName, snapshot.messages)
                        val sessions = chatHistoryRepository.list()
                        _state.update { it.copy(chatSessions = sessions) }
                    }
                }
        }
    }

    private fun loadChatHistory() {
        viewModelScope.launch {
            _state.update { it.copy(chatSessions = chatHistoryRepository.list()) }
        }
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
                    activeChatSessionId = UUID.randomUUID().toString(),
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
                    activeChatSessionId = UUID.randomUUID().toString(),
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
                        activeChatSessionId = UUID.randomUUID().toString(),
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
                activeChatSessionId = UUID.randomUUID().toString(),
                inputText = "",
                isGenerating = false,
                isThinking = false,
                lastTokensPerSecond = null,
                lastTtftMs = null,
                lastMemoryDeltaMb = null,
                lastStrategy = null,
                lastPipelineLog = null,
                architectStatus = AgentStatus.IDLE,
                coderStatus = AgentStatus.IDLE,
                validatorStatus = AgentStatus.IDLE,
            )
        }
    }

    fun openChatSession(id: String) {
        viewModelScope.launch {
            val session = chatHistoryRepository.load(id) ?: return@launch
            _state.update {
                it.copy(
                    messages = session.messages,
                    activeChatSessionId = session.summary.id,
                    inputText = "",
                    isGenerating = false,
                    isThinking = false,
                )
            }
        }
    }

    fun deleteChatSession(id: String) {
        viewModelScope.launch {
            chatHistoryRepository.delete(id)
            val sessions = chatHistoryRepository.list()
            _state.update {
                if (it.activeChatSessionId == id) {
                    it.copy(
                        messages = emptyList(),
                        activeChatSessionId = UUID.randomUUID().toString(),
                        chatSessions = sessions,
                    )
                } else {
                    it.copy(chatSessions = sessions)
                }
            }
        }
    }

    private fun getService(config: com.pocketide.data.ai.AiConfig): AiService {
        val hash = config.hashCode()
        if (cachedService != null && hash == cachedConfigHash) return cachedService!!
        val service = AiService(executorchRunner, llamaCppRunner, config, getApplication())
        cachedService = service
        cachedConfigHash = hash
        return service
    }

    fun exportBenchmarkReport(): String {
        val service = cachedService ?: return "No AI session active. Generate some code first."
        return service.session.exportReport()
    }

    fun exportBenchmarkJson(): String {
        val service = cachedService ?: return "{}"
        return service.session.exportJson()
    }

    fun copyBenchmarkReportToClipboard() {
        val report = exportBenchmarkReport()
        val clipboard = getApplication<Application>().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PocketIDE Benchmark", report))
        android.widget.Toast.makeText(
            getApplication(),
            "Benchmark report copied to clipboard (${report.length} chars)",
            android.widget.Toast.LENGTH_LONG,
        ).show()
        _state.update { it.copy(benchmarkReport = report) }
    }

    fun copyBenchmarkJsonToClipboard() {
        val json = exportBenchmarkJson()
        val clipboard = getApplication<Application>().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PocketIDE Benchmark JSON", json))
        android.widget.Toast.makeText(getApplication(), "Benchmark JSON copied", android.widget.Toast.LENGTH_SHORT).show()
        _state.update { it.copy(benchmarkJson = json) }
    }

    /** Measures real decode profiles and saves the best thread count for this device and GGUF model. */
    fun runBenchmarkSuite() {
        if (_state.value.benchmarkRunning) return
        if (_state.value.isGenerating) {
            _state.update { it.copy(benchmarkError = "Wait for the current AI response to finish.") }
            return
        }
        _state.update {
            it.copy(
                benchmarkRunning = true,
                benchmarkCompletedRuns = 0,
                benchmarkTotalRuns = 0,
                benchmarkError = null,
                benchmarkSummary = null,
            )
        }
        viewModelScope.launch {
            val config = aiConfigRepository.load()
            if (!config.isConfigured) {
                _state.update {
                    it.copy(benchmarkRunning = false, benchmarkError = "Add an on-device model in Settings first.")
                }
                return@launch
            }
            val activeModel = config.activeModel
            if (activeModel?.format != ModelFormat.GGUF) {
                _state.update {
                    it.copy(
                        benchmarkRunning = false,
                        benchmarkError = "Thread calibration currently requires a GGUF llama.cpp model.",
                    )
                }
                return@launch
            }
            val service = getService(config)
            service.session.clear()
            val cpuCores = Runtime.getRuntime().availableProcessors()
            val heuristicThreads = BackendInfo.optimalThreadCount.coerceIn(1, cpuCores)
            val candidateThreads = buildSet {
                addAll(1..minOf(4, cpuCores))
                add(heuristicThreads)
            }.sorted()
            val plan = buildList {
                candidateThreads.forEach { threads ->
                    add(BenchmarkPlan("T$threads", threads, true))
                    repeat(3) { add(BenchmarkPlan("T$threads", threads, false)) }
                }
            }
            _state.update { it.copy(benchmarkTotalRuns = plan.size) }
            val measured = mutableListOf<ThreadProfileSample>()
            plan.forEachIndexed { index, run ->
                val result = service.chatCompletion(
                    systemPrompt = BENCHMARK_SYSTEM_PROMPT,
                    history = emptyList(),
                    userMessage = BENCHMARK_FIXED_PROMPT,
                    benchmarkOptions = BenchmarkRunOptions(
                        profile = run.profile,
                        threadCount = run.threads,
                        isWarmup = run.warmup,
                    ),
                )
                if (result is AiResult.Error) {
                    _state.update {
                        it.copy(
                            benchmarkRunning = false,
                            benchmarkError = "Run ${index + 1} failed: ${result.message}",
                        )
                    }
                    return@launch
                }
                val benchmark = (result as AiResult.Success).benchmark
                if (!run.warmup && benchmark != null) {
                    measured += ThreadProfileSample(
                        threadCount = run.threads,
                        tokensPerSecond = benchmark.tokensPerSecond,
                        ttftMs = benchmark.ttftMs,
                        peakProcessPssBytes = benchmark.peakProcessPssBytes,
                    )
                }
                _state.update { it.copy(benchmarkCompletedRuns = index + 1) }
            }

            val calibration = ThreadProfileSelector.select(measured)
            val summary = if (calibration == null) {
                "No valid measured generations were recorded."
            } else {
                threadCalibrationRepository.save(activeModel.modelPath, calibration)
                service.session.setThreadCalibration(heuristicThreads, calibration)
                val heuristicProfile = ThreadProfileSelector.select(
                    measured.filter { it.threadCount == heuristicThreads },
                )
                val heuristicTps = heuristicProfile?.medianTokensPerSecond ?: 0f
                val delta = if (heuristicTps > 0f) {
                    (calibration.medianTokensPerSecond / heuristicTps - 1f) * 100f
                } else {
                    0f
                }
                String.format(
                    Locale.US,
                    "Selected %d thread(s): %.2f tok/s median (%+.1f%% vs %d-thread heuristic) · TTFT %d ms · saved for normal chat",
                    calibration.threadCount,
                    calibration.medianTokensPerSecond,
                    delta,
                    heuristicThreads,
                    calibration.averageTtftMs,
                )
            }
            val report = service.session.exportReport()
            val json = service.session.exportJson()
            _state.update {
                it.copy(
                    benchmarkRunning = false,
                    benchmarkSummary = summary,
                    benchmarkReport = report,
                    benchmarkJson = json,
                )
            }
        }
    }

    fun clearBenchmark() {
        cachedService?.session?.clear()
        _state.update {
            it.copy(
                benchmarkCompletedRuns = 0,
                benchmarkSummary = null,
                benchmarkError = null,
                benchmarkReport = null,
                benchmarkJson = null,
            )
        }
    }

    fun previewWebProject() {
        val snapshot = _state.value
        val activeFile = snapshot.files.getOrNull(snapshot.activeFileIndex)
        if (snapshot.files.none { it.language.supportsWebPreview }) {
            _state.update {
                it.copy(
                    executionStatus = ExecutionStatus.FAILED,
                    stderr = "Web preview needs an HTML, CSS, JavaScript, or TypeScript file.",
                    terminalExpanded = true,
                )
            }
            return
        }
        previewServer.start(snapshot.files, activeFile)
            .onSuccess { url ->
                val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { getApplication<Application>().startActivity(intent) }
                    .onSuccess {
                        _state.update {
                            it.copy(
                                previewUrl = url,
                                executionStatus = ExecutionStatus.PASSED,
                                stdout = "Preview running at $url\nThe server is restricted to this device.",
                                stderr = "",
                                warnings = "",
                                errorLine = null,
                                errorColumn = null,
                                errorType = null,
                                executionDurationMs = 0,
                                terminalExpanded = true,
                            )
                        }
                    }
                    .onFailure { error -> showPreviewError(error.message ?: "No browser is available") }
            }
            .onFailure { error -> showPreviewError(error.message ?: "Could not start preview") }
    }

    private fun showPreviewError(message: String) {
        _state.update {
            it.copy(
                executionStatus = ExecutionStatus.FAILED,
                stderr = "Web preview failed: $message",
                warnings = "",
                errorLine = null,
                errorColumn = null,
                errorType = "PreviewError",
                executionDurationMs = 0,
                terminalExpanded = true,
            )
        }
    }

    fun sendMessage() {
        if (_state.value.benchmarkRunning || _state.value.isGenerating) return
        val prompt = _state.value.inputText.trim()
        if (prompt.isBlank()) return

        val currentMode = _state.value.aiMode
        val currentModelMode = _state.value.modelMode
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
                validatorStatus = AgentStatus.IDLE,
                activeTab = ActivityTab.AI_CHAT,
            )
        }

        viewModelScope.launch {
            val config = aiConfigRepository.load()
            val service = getService(config)
            val systemPrompt = when (currentMode) {
                AiMode.CODE -> if (currentModelMode == ModelMode.SWARM) ARCHITECT_SYSTEM_PROMPT else CODE_MODE_PROMPT_V2
                AiMode.ASK -> ASK_MODE_PROMPT
                AiMode.PLAN -> PLAN_MODE_PROMPT
            }

            // Build managed context to fit within the model's context window
            val managed = ContextManager.buildContext(
                systemPrompt = systemPrompt,
                history = historyForRequest,
                userMessage = prompt,
                files = _state.value.files,
                contextWindowSize = config.contextWindowSize,
                activeFileIndex = _state.value.activeFileIndex,
                enableCodeContext = config.enableCodeContext,
                enableHistorySummary = config.enableHistorySummary,
            )

            val startTime = System.currentTimeMillis()
            var tokenCount = 0

            val result = service.chatCompletion(
                systemPrompt = managed.systemPrompt,
                history = managed.history,
                userMessage = managed.userMessage,
                onToken = { _ ->
                    if (_state.value.isThinking) {
                        _state.update { it.copy(isThinking = false) }
                    }
                    tokenCount++
                },
            )

            val elapsedSec = (System.currentTimeMillis() - startTime) / 1000f
            val callbackTps = if (elapsedSec > 0f) tokenCount / elapsedSec else null
            val tps = (result as? AiResult.Success)?.benchmark?.tokensPerSecond
                ?.takeIf { it > 0f }
                ?: callbackTps
            val benchTtft = (result as? AiResult.Success)?.benchmark?.ttftMs?.takeIf { it >= 0 }
            val benchMemDelta = (result as? AiResult.Success)?.benchmark?.let {
                (it.memoryDeltaBytes / (1024f * 1024f))
            }
            val benchStrategy = (result as? AiResult.Success)?.tuning?.strategy?.displayName
            val benchPipelineLog = (result as? AiResult.Success)?.pipelineLog

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
                        AiMode.CODE -> {
                            if (currentModelMode == ModelMode.SWARM) {
                                runSwarmPipeline(service, config, result.content, tps)
                            } else {
                                applyAiResponse(result.content, tps)
                            }
                        }
                        AiMode.ASK -> _state.update {
                            it.copy(
                                messages = it.messages + ChatMessage(
                                    role = MessageRole.ASSISTANT,
                                    content = result.content,
                                    agentStatus = AgentStatus.DONE,
                                    tokensPerSecond = tps,
                                    ttftMs = benchTtft,
                                    memoryDeltaMb = benchMemDelta,
                                    strategy = benchStrategy,
                                ),
                                architectStatus = AgentStatus.DONE,
                                isGenerating = false,
                                isThinking = false,
                                lastTokensPerSecond = tps,
                                lastTtftMs = benchTtft,
                                lastMemoryDeltaMb = benchMemDelta,
                                lastStrategy = benchStrategy,
                                lastPipelineLog = benchPipelineLog,
                            )
                        }
                        AiMode.PLAN -> _state.update {
                            it.copy(
                                messages = it.messages + ChatMessage(
                                    role = MessageRole.ARCHITECT,
                                    content = result.content,
                                    agentStatus = AgentStatus.DONE,
                                    tokensPerSecond = tps,
                                    ttftMs = benchTtft,
                                    memoryDeltaMb = benchMemDelta,
                                    strategy = benchStrategy,
                                ),
                                architectStatus = AgentStatus.DONE,
                                isGenerating = false,
                                isThinking = false,
                                lastTokensPerSecond = tps,
                                lastTtftMs = benchTtft,
                                lastMemoryDeltaMb = benchMemDelta,
                                lastStrategy = benchStrategy,
                                lastPipelineLog = benchPipelineLog,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * SWARM pipeline: Architect (plan) → Coder (generate) → Validator (auto-repair loop).
     * The initial AI response is treated as the Architect's plan. The Coder then
     * generates code from that plan. If execution fails, the Validator repairs
     * automatically up to [AiConfig.maxRepairIterations] times.
     */
    private suspend fun runSwarmPipeline(
        service: AiService,
        config: com.pocketide.data.ai.AiConfig,
        architectResponse: String,
        architectTps: Float?,
    ) {
        // Step 1: Display Architect's plan
        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(
                    role = MessageRole.ARCHITECT,
                    content = architectResponse,
                    agentStatus = AgentStatus.DONE,
                    tokensPerSecond = architectTps,
                ),
                architectStatus = AgentStatus.DONE,
                coderStatus = AgentStatus.GENERATING,
                isThinking = true,
            )
        }

        // Step 2: Coder — generate code using the plan as context
        val coderUserMessage = buildString {
            appendLine("Based on this plan, generate the code:")
            appendLine(architectResponse)
            appendLine()
            appendLine("Respond using the PLAN/FILENAME/code block format.")
        }

        val coderManaged = ContextManager.buildContext(
            systemPrompt = CODER_SYSTEM_PROMPT_V2,
            history = emptyList(),
            userMessage = coderUserMessage,
            files = _state.value.files,
            contextWindowSize = config.contextWindowSize,
            activeFileIndex = _state.value.activeFileIndex,
            enableCodeContext = config.enableCodeContext,
            enableHistorySummary = config.enableHistorySummary,
        )

        val coderStart = System.currentTimeMillis()
        var coderTokens = 0
        val coderResult = service.chatCompletion(
            systemPrompt = coderManaged.systemPrompt,
            history = coderManaged.history,
            userMessage = coderManaged.userMessage,
            onToken = { _ ->
                if (_state.value.isThinking) {
                    _state.update { it.copy(isThinking = false) }
                }
                coderTokens++
            },
        )
        val callbackCoderTps = ((System.currentTimeMillis() - coderStart) / 1000f).let { el ->
            if (el > 0f) coderTokens / el else null
        }
        val coderTps = (coderResult as? AiResult.Success)?.benchmark?.tokensPerSecond
            ?.takeIf { it > 0f }
            ?: callbackCoderTps

        when (coderResult) {
            is AiResult.Error -> {
                _state.update {
                    it.copy(
                        messages = it.messages + ChatMessage(
                            role = MessageRole.SYSTEM,
                            content = "Coder error: ${coderResult.message}",
                            agentStatus = AgentStatus.ERROR,
                        ),
                        coderStatus = AgentStatus.ERROR,
                        isGenerating = false,
                        isThinking = false,
                    )
                }
                return
            }
            is AiResult.Success -> {
                applyAiResponse(coderResult.content, coderTps)
            }
        }

        // Step 3: Auto-execute and Validator repair loop
        val s = _state.value
        val activeFile = s.files.getOrNull(s.activeFileIndex)
        if (activeFile == null) return

        val execResult = codeExecutor.execute(activeFile.content, activeFile.language)
        _state.update {
            it.copy(
                executionStatus = execResult.status,
                stdout = execResult.stdout,
                stderr = execResult.stderr,
                warnings = execResult.warnings,
                errorLine = execResult.errorLine,
                errorColumn = execResult.errorColumn,
                errorType = execResult.errorType,
                executionDurationMs = execResult.durationMs,
                terminalExpanded = true,
            )
        }

        if (execResult.status == ExecutionStatus.PASSED) {
            _state.update {
                it.copy(
                    isGenerating = false,
                    isThinking = false,
                    coderStatus = AgentStatus.DONE,
                    validatorStatus = AgentStatus.DONE,
                )
            }
            return
        }

        // Step 4: Validator — autonomous repair loop
        runAutonomousRepair(service, config, activeFile, maxIterations = config.maxRepairIterations)
    }

    /**
     * Autonomous repair loop: sends error context back to the AI, re-applies
     * the fixed code, and re-executes. Repeats up to [maxIterations] times
     * or until the code executes successfully.
     */
    private suspend fun runAutonomousRepair(
        service: AiService,
        config: com.pocketide.data.ai.AiConfig,
        initialFile: CodeFile,
        maxIterations: Int,
    ) {
        var currentFile = initialFile
        var currentStderr = _state.value.stderr
        var currentErrorLine = _state.value.errorLine
        var currentErrorColumn = _state.value.errorColumn
        var currentErrorType = _state.value.errorType
        var currentWarnings = _state.value.warnings
        var currentDuration = _state.value.executionDurationMs

        for (iteration in 1..maxIterations) {
            _state.update {
                it.copy(
                    validatorStatus = AgentStatus.GENERATING,
                    isThinking = true,
                    isGenerating = true,
                )
            }

            val repairPrompt = buildString {
                appendLine("The following ${currentFile.language.displayName} code failed (repair attempt $iteration/$maxIterations):")
                appendLine("Error type: ${currentErrorType ?: "Unknown"}")
                if (currentErrorLine != null) {
                    appendLine("Error at line: $currentErrorLine${if (currentErrorColumn != null) ", column: $currentErrorColumn" else ""}")
                }
                if (currentWarnings.isNotBlank()) {
                    appendLine("Warnings: $currentWarnings")
                }
                appendLine("Error message: $currentStderr")
                appendLine("Execution time: ${currentDuration}ms")
                appendLine()
                appendLine("Code:")
                appendLine(currentFile.content)
                appendLine()
                appendLine("Fix the code. Respond using the same PLAN/FILENAME/code block format.")
            }

            val repairManaged = ContextManager.buildContext(
                systemPrompt = VALIDATOR_SYSTEM_PROMPT,
                history = emptyList(),
                userMessage = repairPrompt,
                files = _state.value.files,
                contextWindowSize = config.contextWindowSize,
                activeFileIndex = _state.value.activeFileIndex,
                enableCodeContext = config.enableCodeContext,
                enableHistorySummary = config.enableHistorySummary,
            )

            val repairResult = service.chatCompletion(
                systemPrompt = repairManaged.systemPrompt,
                history = repairManaged.history,
                userMessage = repairManaged.userMessage,
                onToken = { _ ->
                    if (_state.value.isThinking) {
                        _state.update { it.copy(isThinking = false) }
                    }
                },
            )

            when (repairResult) {
                is AiResult.Error -> {
                    _state.update {
                        it.copy(
                            messages = it.messages + ChatMessage(
                                role = MessageRole.SYSTEM,
                                content = "Repair attempt $iteration failed: ${repairResult.message}",
                                agentStatus = AgentStatus.ERROR,
                            ),
                            validatorStatus = AgentStatus.ERROR,
                            isGenerating = false,
                            isThinking = false,
                        )
                    }
                    return
                }
                is AiResult.Success -> {
                    applyAiResponse(repairResult.content, null)
                }
            }

            // Re-execute the repaired code
            val s = _state.value
            val repairedFile = s.files.getOrNull(s.activeFileIndex) ?: return
            currentFile = repairedFile

            val execResult = codeExecutor.execute(repairedFile.content, repairedFile.language)
            _state.update {
                it.copy(
                    executionStatus = execResult.status,
                    stdout = execResult.stdout,
                    stderr = execResult.stderr,
                    warnings = execResult.warnings,
                    errorLine = execResult.errorLine,
                    errorColumn = execResult.errorColumn,
                    errorType = execResult.errorType,
                    executionDurationMs = execResult.durationMs,
                )
            }

            if (execResult.status == ExecutionStatus.PASSED) {
                _state.update {
                    it.copy(
                        messages = it.messages + ChatMessage(
                            role = MessageRole.VALIDATOR,
                            content = "Code repaired successfully after $iteration attempt(s).",
                            agentStatus = AgentStatus.DONE,
                        ),
                        validatorStatus = AgentStatus.DONE,
                        isGenerating = false,
                        isThinking = false,
                    )
                }
                return
            }

            // Update error context for next iteration
            currentStderr = execResult.stderr
            currentErrorLine = execResult.errorLine
            currentErrorColumn = execResult.errorColumn
            currentErrorType = execResult.errorType
            currentWarnings = execResult.warnings
            currentDuration = execResult.durationMs
        }

        // Max iterations reached — show best attempt
        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(
                    role = MessageRole.VALIDATOR,
                    content = "Could not fully repair after $maxIterations attempts. Last error: $currentStderr",
                    agentStatus = AgentStatus.ERROR,
                ),
                validatorStatus = AgentStatus.ERROR,
                isGenerating = false,
                isThinking = false,
            )
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

        val generatedFiles = parsed.files.ifEmpty {
            val code = parsed.code
            val language = parsed.language
            if (code == null || language == null) emptyList() else listOf(
                com.pocketide.data.ai.ParsedAiFile(
                    filename = parsed.filename ?: "main.${language.fileExtension}",
                    code = code,
                    language = language,
                ),
            )
        }
        if (generatedFiles.isEmpty()) {
            _state.update { it.copy(isGenerating = false) }
            return
        }

        val s = _state.value
        val newFiles = s.files.toMutableList()
        var targetIndex = s.activeFileIndex.coerceIn(0, newFiles.lastIndex.coerceAtLeast(0))
        generatedFiles.forEach { generated ->
            val existingIndex = newFiles.indexOfFirst { it.name == generated.filename }
            if (existingIndex >= 0) {
                newFiles[existingIndex] = newFiles[existingIndex].copy(
                    language = generated.language,
                    content = generated.code,
                    isModified = true,
                )
                targetIndex = existingIndex
            } else {
                newFiles.add(
                    CodeFile(
                        name = generated.filename,
                        language = generated.language,
                        content = generated.code,
                        isModified = true,
                    ),
                )
                targetIndex = newFiles.lastIndex
            }
        }
        val activeGenerated = newFiles[targetIndex]
        val generatedMessage = generatedFiles.joinToString("\n\n") { generated ->
            "Generated ${generated.filename}:\n```${generated.language.fileExtension}\n${generated.code}\n```"
        }

        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(
                    role = MessageRole.CODER,
                    content = generatedMessage,
                    agentStatus = AgentStatus.DONE,
                    tokensPerSecond = tokensPerSecond,
                ),
                coderStatus = AgentStatus.DONE,
                files = newFiles,
                activeFileIndex = targetIndex,
                activeFileContent = activeGenerated.content,
                isGenerating = false,
                isThinking = false,
                lastTokensPerSecond = tokensPerSecond,
                unsavedCount = newFiles.count { f -> f.isModified },
            )
        }

        viewModelScope.launch {
            repository.saveFiles(s.projectName, newFiles)
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
        if (_state.value.executionStatus == ExecutionStatus.RUNNING) return
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
                waitingForInput = false,
                inputPrompt = "",
            )
        }

        viewModelScope.launch {
            repository.saveFiles(s.projectName, s.files)
            val console = ExecutionConsole(
                onStdout = { chunk -> _state.update { it.copy(stdout = it.stdout + chunk) } },
                onStderr = { chunk -> _state.update { it.copy(stderr = it.stderr + chunk) } },
                onInputRequested = { prompt ->
                    _state.update { it.copy(waitingForInput = prompt.isNotEmpty() || activeExecutionConsole?.waitingForInput == true, inputPrompt = prompt) }
                },
            )
            activeExecutionConsole?.cancel()
            activeExecutionConsole = console
            val result = codeExecutor.execute(
                ExecutionRequest(
                    code = activeFile.content,
                    language = activeFile.language,
                    fileName = activeFile.name,
                    projectDirectory = repository.projectDirectory(s.projectName),
                    console = console,
                ),
            )
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
                    waitingForInput = false,
                    inputPrompt = "",
                )
            }
            if (activeExecutionConsole === console) activeExecutionConsole = null
        }
    }

    fun submitTerminalInput(value: String): Boolean = activeExecutionConsole?.submitInput(value) == true

    fun retryRepair() {
        val s = _state.value
        val activeFile = s.files.getOrNull(s.activeFileIndex) ?: return
        if (s.stderr.isBlank()) return

        _state.update {
            it.copy(
                validatorStatus = AgentStatus.GENERATING,
                executionStatus = ExecutionStatus.IDLE,
                isGenerating = true,
                isThinking = true,
            )
        }

        viewModelScope.launch {
            val config = aiConfigRepository.load()
            val service = getService(config)
            runAutonomousRepair(service, config, activeFile, maxIterations = config.maxRepairIterations)
        }
    }
}

private const val BENCHMARK_SYSTEM_PROMPT = """You are an on-device coding model benchmark. Follow the request directly, keep the answer deterministic and concise, and do not discuss the benchmark."""

private const val BENCHMARK_FIXED_PROMPT = "Write Python code that parses JSON Lines sensor readings and returns min, max, and average per sensor while safely skipping malformed records. Output code only."

private data class BenchmarkPlan(val profile: String, val threads: Int, val warmup: Boolean)

private const val CODE_MODE_PROMPT_V2 = """You are PocketIDE's local coding agent. Build the requested program directly. Never refuse a normal coding request or say that an AI cannot build it. If one detail is unavailable on Android, implement the closest runnable local version and mention that limit only in PLAN.

Return exactly:
PLAN: one short sentence
FILE: filename.ext
```language
complete runnable code
```

For multiple files, repeat FILE plus its fenced code block. No commentary outside this format. Do not use placeholders or TODOs.

Runtime facts:
- Python is genuine CPython 3.11. input(), functions, classes, exceptions, sibling-file imports, and most standard-library modules work. Use input() for interactive terminal programs.
- JavaScript is Rhino ES5: use var and function, not let/const, arrows, classes, async, fetch, or template literals.
- TypeScript uses compatibility type-stripping and the Rhino ES5 runtime; keep syntax simple.
- Lua uses LuaJ; SQL uses SQLite; Shell uses Android POSIX sh; Java is BeanShell scripting, not javac.
- HTML/CSS/JavaScript/simple TypeScript projects can use browser preview at a device-local 127.0.0.1 URL. Browser pages do not receive the native hardware object; run a Python, JavaScript, TypeScript, Lua, or Java script inside PocketIDE for hardware calls.
- A global hardware object is available in Python, JavaScript, TypeScript, Lua, and Java. Useful methods include toast, vibrate, setFlashlight, batteryLevel, readSensor, speak, notify, getLocation, readFile, writeFile, startServer, and sandboxPath.
- Camera support is capability discovery only through hardware.listCameras(); photo and video capture are not implemented.

Use the language the user requests. If none is requested, choose the runtime that best fits the task; use HTML/CSS/JavaScript for visual browser apps. Never substitute a calculator, Fibonacci example, or other canned demo for the requested program."""

private const val CODER_SYSTEM_PROMPT_V2 = """You are PocketIDE's implementation agent. Produce complete runnable files from the plan. Never refuse a normal coding request.

Return exactly:
PLAN: one short sentence
FILE: filename.ext
```language
full file content
```

Repeat FILE blocks when the plan needs multiple files. No TODOs and no text outside the format. Python is CPython 3.11 with input() and sibling imports. JavaScript/TypeScript must remain Rhino ES5-compatible. Lua, SQLite, Android sh, and BeanShell are available. A global hardware object exposes Android hardware when requested."""

private const val ASK_MODE_PROMPT = """You are a knowledgeable assistant inside PocketIDE, an on-device Android IDE.
The user is in ASK mode — they want explanations, not file modifications.
Answer questions about code, explain concepts, review snippets, and provide guidance.
Do NOT write code blocks or attempt to create files. Respond in plain text.
If the user asks you to write code, suggest they switch to CODE mode.

SUPPORTED LANGUAGES: python, javascript, typescript, lua, sql, java, shell.
HARDWARE BRIDGE — available in javascript, typescript, python, lua, and java via a global `hardware` object:
  toast/toastLong, vibrate/vibratePattern, setFlashlight, batteryLevel/batteryTemperature,
  isCharging, clipboardGet/Set, screenInfo/screenBrightness/setScreenBrightness/keepScreenOn,
  networkType/isOnline, storageFree/Total, readSensor/listSensors, getDeviceInfo, openUrl,
  speak/stopSpeak, playTone, notify, getLocation, listBluetooth, listCameras,
  readFile/writeFile/listFiles/deleteFile/sandboxPath, startServer/stopServer/isServerRunning

When explaining code, consider the ES5 JavaScript constraints (var, no arrow functions,
no template literals) and CPython-on-Android limitations described in CODE mode. Browser
preview runs at 127.0.0.1 but does not expose the native hardware object. listCameras
reports capabilities only; it does not capture photos or video."""

private const val PLAN_MODE_PROMPT = """You are a planning assistant inside PocketIDE, an on-device Android IDE.
The user is in PLAN mode — they want a detailed plan before any code is written.
Analyze the request and respond with a structured implementation plan:

1. Break down the task into steps
2. List files that need to be created or modified (with filenames and extensions)
3. Describe the approach for each file
4. Note any potential issues or edge cases
5. Consider which language is best for each file

SUPPORTED LANGUAGES: python, javascript, typescript, lua, sql, java, shell.
HARDWARE BRIDGE — available in javascript, typescript, python, lua, and java via a global `hardware` object:
  toast/toastLong, vibrate/vibratePattern, setFlashlight, batteryLevel/batteryTemperature,
  isCharging, clipboardGet/Set, screenInfo/screenBrightness/setScreenBrightness/keepScreenOn,
  networkType/isOnline, storageFree/Total, readSensor/listSensors, getDeviceInfo, openUrl,
  speak/stopSpeak, playTone, notify, getLocation, listBluetooth, listCameras,
  readFile/writeFile/listFiles/deleteFile/sandboxPath, startServer/stopServer/isServerRunning

When planning, consider the ES5 JavaScript constraints (var, no arrow functions,
no template literals) and CPython-on-Android limits. Browser preview runs at
127.0.0.1 but does not expose the native hardware object. listCameras reports
capabilities only; it does not capture photos or video. Plan code that will actually
run on-device in the PocketIDE sandbox.

Do NOT write code blocks. The user will switch to CODE mode to implement the plan."""

private const val ARCHITECT_SYSTEM_PROMPT = """You are the Architect agent in PocketIDE's SWARM pipeline.
Your role is to analyze the user's request and produce a concise implementation plan.

1. Break down the task into clear steps
2. Specify which files to create or modify (with filenames and extensions)
3. Describe the algorithm/approach for each file
4. Identify edge cases and potential issues
5. Recommend the best language for each file

SUPPORTED LANGUAGES: python, javascript, typescript, lua, sql, java, shell.
HARDWARE BRIDGE — available in javascript, typescript, python, lua, and java via a global `hardware` object:
  toast/toastLong, vibrate/vibratePattern, setFlashlight, batteryLevel/batteryTemperature,
  isCharging, clipboardGet/Set, screenInfo/screenBrightness/setScreenBrightness/keepScreenOn,
  networkType/isOnline, storageFree/Total, readSensor/listSensors, getDeviceInfo, openUrl,
  speak/stopSpeak, playTone, notify, getLocation, listBluetooth, listCameras,
  readFile/writeFile/listFiles/deleteFile/sandboxPath, startServer/stopServer/isServerRunning

Browser preview runs at 127.0.0.1 but does not expose the native hardware object.
hardware.listCameras reports capabilities only; it does not capture photos or video.

Output ONLY the plan in plain text. Do NOT write code blocks.
The Coder agent will implement based on your plan."""

private const val VALIDATOR_SYSTEM_PROMPT = """You are the Validator agent in PocketIDE's SWARM pipeline.
Your role is to fix code that failed execution. You receive the error output and the failing code.

Analyze the error carefully and fix the root cause, not just the symptom.

Respond in EXACTLY this format:

PLAN:
<brief explanation of the bug and your fix>

FILENAME:
<same filename>

CODE:
```<language>
<fixed code>
```

RULES:
- Fix the actual error, not just suppress it
- Keep the rest of the code intact unless it also needs fixing
- Ensure the code will pass execution this time
- Use the PLAN/FILENAME/CODE format exactly"""
