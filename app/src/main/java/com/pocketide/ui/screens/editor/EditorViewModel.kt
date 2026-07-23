package com.pocketide.ui.screens.editor

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketide.data.ai.AiConfigRepository
import com.pocketide.data.ai.AiResult
import com.pocketide.data.ai.AiService
import com.pocketide.data.ai.BackendInfo
import com.pocketide.data.ai.BenchmarkDashboard
import com.pocketide.data.ai.BenchmarkDepth
import com.pocketide.data.ai.BenchmarkPowerSampler
import com.pocketide.data.ai.BenchmarkProtocol
import com.pocketide.data.ai.BenchmarkRunOptions
import com.pocketide.data.ai.ChatTurn
import com.pocketide.data.ai.CodeFileAttempt
import com.pocketide.data.ai.CodeGenerationPipeline
import com.pocketide.data.ai.CodeModelReply
import com.pocketide.data.ai.CodeProjectGenerationResult
import com.pocketide.data.ai.ContextManager
import com.pocketide.data.ai.ExecutorchLlmRunner
import com.pocketide.data.ai.InferencePhase
import com.pocketide.data.ai.InferenceResourcePlan
import com.pocketide.data.ai.LlamaCppRunner
import com.pocketide.data.ai.ModelFormat
import com.pocketide.data.ai.PreviousProcessExit
import com.pocketide.data.ai.ProcessExitDiagnostics
import com.pocketide.data.ai.ParsedAiFile
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
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
    val benchmarkPhase: String? = null,
    val benchmarkSummary: BenchmarkDashboard? = null,
    val benchmarkError: String? = null,
    val chatSessions: List<ChatSessionSummary> = emptyList(),
    val activeChatSessionId: String = UUID.randomUUID().toString(),
    val previewUrl: String? = null,
    val waitingForInput: Boolean = false,
    val inputPrompt: String = "",
    val inferencePhase: InferencePhase = InferencePhase.IDLE,
    val previousProcessExit: PreviousProcessExit? = null,
)

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = FileRepository(application)
    private val codeExecutor = CodeExecutor(application)
    private val previewServer = ProjectPreviewServer(codeExecutor)
    private val aiConfigRepository = AiConfigRepository(application)
    private val threadCalibrationRepository = ThreadCalibrationRepository(application)
    private val chatHistoryRepository = ChatHistoryRepository(application)
    private val processExitDiagnostics = ProcessExitDiagnostics(application)
    private val benchmarkPowerSampler = BenchmarkPowerSampler(application)
    private val recoveredProcessExit = processExitDiagnostics.consumeLatestAbnormal()
    private val executorchRunner = ExecutorchLlmRunner()
    private val llamaCppRunner = LlamaCppRunner(application)
    private val _state = MutableStateFlow(EditorUiState(previousProcessExit = recoveredProcessExit))
    val state: StateFlow<EditorUiState> = _state.asStateFlow()
    private var cachedService: AiService? = null
    private var cachedConfigHash: Int = 0
    private var activeExecutionConsole: ExecutionConsole? = null
    private var generationJob: Job? = null
    private var benchmarkJob: Job? = null

    override fun onCleared() {
        activeExecutionConsole?.cancel()
        cachedService?.stopGeneration()
        generationJob?.cancel()
        benchmarkJob?.cancel()
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
        if (_state.value.isGenerating) {
            cachedService?.stopGeneration()
            generationJob?.cancel(CancellationException("New chat opened"))
        }
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
                inferencePhase = InferencePhase.IDLE,
            )
        }
    }

    fun openChatSession(id: String) {
        if (_state.value.isGenerating) {
            cachedService?.stopGeneration()
            generationJob?.cancel(CancellationException("Another chat opened"))
        }
        viewModelScope.launch {
            val session = chatHistoryRepository.load(id) ?: return@launch
            _state.update {
                it.copy(
                    messages = session.messages,
                    activeChatSessionId = session.summary.id,
                    inputText = "",
                    isGenerating = false,
                    isThinking = false,
                    inferencePhase = InferencePhase.IDLE,
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
        cachedService?.takeIf { hash == cachedConfigHash }?.let { return it }
        val service = AiService(executorchRunner, llamaCppRunner, config, getApplication())
        service.session.setPreviousProcessExit(recoveredProcessExit)
        cachedService = service
        cachedConfigHash = hash
        return service
    }

    fun dismissPreviousProcessExit() {
        _state.update { it.copy(previousProcessExit = null) }
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
    fun runBenchmarkSuite(depth: BenchmarkDepth) {
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
                benchmarkPhase = "Preparing real on-device workload",
                benchmarkError = null,
                benchmarkSummary = null,
                benchmarkReport = null,
                benchmarkJson = null,
            )
        }
        benchmarkJob = viewModelScope.launch {
            val thisJob = currentCoroutineContext()[Job]
            try {
            val config = aiConfigRepository.load()
            if (!config.isConfigured) {
                _state.update {
                    it.copy(
                        benchmarkRunning = false,
                        benchmarkPhase = null,
                        benchmarkError = "Add an on-device model in Settings first.",
                    )
                }
                return@launch
            }
            val activeModel = config.activeModel
            if (activeModel == null || activeModel.format == ModelFormat.UNKNOWN) {
                _state.update {
                    it.copy(
                        benchmarkRunning = false,
                        benchmarkPhase = null,
                        benchmarkError = "Select a supported GGUF or PTE model first.",
                    )
                }
                return@launch
            }
            val isGguf = activeModel.format == ModelFormat.GGUF

            val service = getService(config)
            service.session.clear()
            service.session.recordPowerSample(benchmarkPowerSampler.sample("benchmark_start", 0))
            val cpuCores = Runtime.getRuntime().availableProcessors()
            val heuristicThreads = BackendInfo.optimalThreadCount.coerceIn(1, cpuCores)
            val savedCalibration = if (isGguf) {
                threadCalibrationRepository.load(activeModel.modelPath)
            } else {
                null
            }
            val candidateThreads = if (isGguf) {
                when (depth) {
                    BenchmarkDepth.QUICK -> buildSet {
                        addAll(1..minOf(MAX_QUICK_BENCHMARK_THREADS, cpuCores))
                        add(heuristicThreads)
                    }.sorted()
                    BenchmarkDepth.DEEP -> (1..minOf(MAX_DEEP_BENCHMARK_THREADS, cpuCores)).toList()
                    BenchmarkDepth.SUSTAINED -> savedCalibration?.let { listOf(it.threadCount) }
                        ?: buildSet {
                            addAll(1..minOf(MAX_QUICK_BENCHMARK_THREADS, cpuCores))
                            add(heuristicThreads)
                        }.sorted()
                }
            } else {
                // PTE thread/delegate choices are fixed during model export and
                // are not controlled by LlmModule's Android generation API.
                listOf(heuristicThreads)
            }
            val generatedTokens = when (depth) {
                BenchmarkDepth.QUICK -> QUICK_BENCHMARK_OUTPUT_TOKENS
                BenchmarkDepth.DEEP, BenchmarkDepth.SUSTAINED -> DEEP_BENCHMARK_OUTPUT_TOKENS
            }
            val screeningRuns = if (!isGguf) {
                when (depth) {
                    BenchmarkDepth.QUICK -> QUICK_MEASURED_RUNS
                    BenchmarkDepth.DEEP -> PTE_DEEP_MEASURED_RUNS
                    BenchmarkDepth.SUSTAINED -> 0
                }
            } else {
                when (depth) {
                    BenchmarkDepth.QUICK -> QUICK_MEASURED_RUNS
                    BenchmarkDepth.DEEP -> DEEP_SCREENING_RUNS
                    BenchmarkDepth.SUSTAINED -> if (savedCalibration == null) DEEP_SCREENING_RUNS else 0
                }
            }
            val confirmationRuns = if (isGguf && depth == BenchmarkDepth.DEEP) DEEP_CONFIRMATION_RUNS else 0
            val sustainedRuns = if (depth == BenchmarkDepth.SUSTAINED) SUSTAINED_MEASURED_RUNS else 0
            val finalistCount = if (isGguf && depth == BenchmarkDepth.DEEP) {
                minOf(DEEP_FINALIST_COUNT, candidateThreads.size)
            } else {
                0
            }
            val screeningTotal = if (!isGguf && depth == BenchmarkDepth.SUSTAINED) {
                0
            } else {
                candidateThreads.size * (screeningRuns + 1)
            }
            val confirmationWarmupsPerProfile = if (confirmationRuns > 0) 2 else 0
            val totalRuns = screeningTotal +
                finalistCount * (confirmationRuns + confirmationWarmupsPerProfile) +
                (if (sustainedRuns > 0) sustainedRuns + 1 else 0) +
                (if (isGguf) 1 else 0)

            service.session.setProtocol(
                BenchmarkProtocol(
                    depth = depth,
                    backend = if (isGguf) "llama.cpp / GGUF" else "ExecuTorch / PTE",
                    workload = "deterministic_code_generation",
                    promptSha256 = BenchmarkProtocol.promptSha256(BENCHMARK_FIXED_PROMPT),
                    generatedTokens = generatedTokens,
                    candidateThreads = candidateThreads,
                    screeningMeasuredRuns = screeningRuns,
                    confirmationMeasuredRuns = confirmationRuns,
                    sustainedMeasuredRuns = sustainedRuns,
                    deterministicSeed = if (isGguf) 42 else -1,
                    temperature = 0.0,
                    ignoreEos = isGguf,
                    threadControl = if (isGguf) {
                        "llama.cpp context load-time n_threads; native context reloaded between profiles"
                    } else {
                        "PTE export/runtime controlled; Android LlmModule does not expose worker count"
                    },
                ),
            )
            _state.update {
                it.copy(
                    benchmarkTotalRuns = totalRuns,
                    benchmarkPhase = if (!isGguf) {
                        if (depth == BenchmarkDepth.SUSTAINED) {
                            "Preparing sustained ExecuTorch PTE measurement"
                        } else {
                            "Measuring fixed ExecuTorch PTE backend"
                        }
                    } else if (depth == BenchmarkDepth.DEEP) {
                        "Screening ${candidateThreads.size} thread profiles"
                    } else if (depth == BenchmarkDepth.SUSTAINED) {
                        "Selecting a sustainable thread profile"
                    } else {
                        "Calibrating ${candidateThreads.size} thread profiles"
                    },
                )
            }

            val measured = mutableListOf<ThreadProfileSample>()
            var completedRuns = 0

            suspend fun execute(plans: List<BenchmarkPlan>): Boolean {
                plans.forEach { run ->
                    if (benchmarkMemoryIsLow()) {
                        _state.update {
                            it.copy(
                                benchmarkRunning = false,
                                benchmarkPhase = null,
                                benchmarkError = "Benchmark stopped before Android's low-memory threshold. " +
                                    "Close other apps, let the phone cool, then retry Quick.",
                            )
                        }
                        return false
                    }
                    val result = service.chatCompletion(
                        systemPrompt = BENCHMARK_SYSTEM_PROMPT,
                        history = emptyList(),
                        userMessage = BENCHMARK_FIXED_PROMPT,
                        benchmarkOptions = BenchmarkRunOptions(
                            profile = run.profile,
                            threadCount = run.threads,
                            isWarmup = run.warmup,
                            generatedTokens = generatedTokens,
                            workload = "deterministic_code_generation",
                            phase = run.phase,
                        ),
                    )
                    service.session.recordPowerSample(
                        benchmarkPowerSampler.sample("after_${run.phase}", completedRuns + 1),
                    )
                    if (result is AiResult.Error) {
                        _state.update {
                            it.copy(
                                benchmarkRunning = false,
                                benchmarkPhase = null,
                                benchmarkError = "Run ${completedRuns + 1} failed: ${result.message}",
                            )
                        }
                        return false
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
                    completedRuns += 1
                    _state.update { it.copy(benchmarkCompletedRuns = completedRuns) }
                }
                return true
            }

            fun phasePlan(threads: List<Int>, phase: String, measuredRuns: Int): List<BenchmarkPlan> =
                buildList {
                    threads.forEach { count ->
                        val profile = if (isGguf) "T$count" else "PTE"
                        add(BenchmarkPlan(profile, count, true, phase))
                        repeat(measuredRuns) { add(BenchmarkPlan(profile, count, false, phase)) }
                    }
                }

            fun counterbalancedConfirmationPlan(
                threads: List<Int>,
                measuredRuns: Int,
            ): List<BenchmarkPlan> = buildList {
                if (threads.isEmpty() || measuredRuns <= 0) return@buildList
                val firstBlockRuns = (measuredRuns + 1) / 2
                val secondBlockRuns = measuredRuns - firstBlockRuns
                listOf(threads to firstBlockRuns, threads.reversed() to secondBlockRuns)
                    .filter { (_, runs) -> runs > 0 }
                    .forEach { (order, runs) ->
                        order.forEach { count ->
                            add(BenchmarkPlan("T$count", count, true, "confirmation"))
                            repeat(runs) {
                                add(BenchmarkPlan("T$count", count, false, "confirmation"))
                            }
                        }
                    }
            }

            // KotlinLlamaCpp configures native worker threads when the context is loaded.
            // Grouped plans therefore reload once per contiguous profile, then warm it before
            // collecting measurements at that load-configured thread count.
            val orderedCandidates = if (isGguf) {
                listOf(heuristicThreads) + candidateThreads.filter { it != heuristicThreads }
            } else {
                candidateThreads
            }
            if (!(!isGguf && depth == BenchmarkDepth.SUSTAINED) &&
                !execute(phasePlan(orderedCandidates, "screening", screeningRuns))
            ) {
                return@launch
            }

            val confirmedThreads = if (isGguf && depth == BenchmarkDepth.DEEP) {
                val finalists = measured
                    .groupBy { it.threadCount }
                    .mapValues { (_, samples) -> samples.map { it.tokensPerSecond }.average() }
                    .entries
                    .sortedByDescending { it.value }
                    .take(finalistCount)
                    .map { it.key }
                    .sorted()
                service.session.setConfirmationThreads(finalists)
                _state.update {
                    it.copy(benchmarkPhase = "Confirming top profiles: ${finalists.joinToString()}")
                }
                if (!execute(counterbalancedConfirmationPlan(finalists, confirmationRuns))) {
                    return@launch
                }
                finalists
            } else {
                candidateThreads
            }

            val selectionSamples = measured.filter { it.threadCount in confirmedThreads }
            var calibration = if (isGguf) {
                ThreadProfileSelector.select(selectionSamples) ?: savedCalibration?.takeIf {
                    depth == BenchmarkDepth.SUSTAINED && it.threadCount in confirmedThreads
                }
            } else {
                null
            }
            fun attachHeuristicComparison(value: com.pocketide.data.ai.ThreadCalibration):
                com.pocketide.data.ai.ThreadCalibration {
                val currentHeuristicMedian = com.pocketide.data.ai.BenchmarkStatistics.calculate(
                    measured.filter { it.threadCount == heuristicThreads }.map { it.tokensPerSecond },
                )?.median?.toFloat()
                return value.copy(
                    comparisonThreadCount = if (currentHeuristicMedian != null) {
                        heuristicThreads
                    } else {
                        value.comparisonThreadCount
                    },
                    comparisonMedianTokensPerSecond = currentHeuristicMedian
                        ?: value.comparisonMedianTokensPerSecond,
                )
            }
            calibration = calibration?.let(::attachHeuristicComparison)
            if (depth == BenchmarkDepth.SUSTAINED) {
                val sustainedThread = calibration?.threadCount ?: candidateThreads.first()
                if (isGguf) service.session.setConfirmationThreads(listOf(sustainedThread))
                _state.update {
                    it.copy(
                        benchmarkPhase = if (isGguf) {
                            "Measuring sustained speed, energy, and temperature on $sustainedThread thread(s)"
                        } else {
                            "Measuring sustained speed, energy, and temperature on the fixed PTE backend"
                        },
                    )
                }
                service.session.recordPowerSample(
                    benchmarkPowerSampler.sample("sustained_start", completedRuns),
                )
                if (!execute(phasePlan(listOf(sustainedThread), "sustained", sustainedRuns))) return@launch
                service.session.recordPowerSample(
                    benchmarkPowerSampler.sample("sustained_complete", completedRuns),
                )
                if (isGguf && calibration != null) {
                    val previousCalibration = calibration
                    calibration = ThreadProfileSelector.select(
                        measured.filter { it.threadCount == sustainedThread },
                    )?.copy(
                        comparisonThreadCount = previousCalibration.comparisonThreadCount,
                        comparisonMedianTokensPerSecond = previousCalibration.comparisonMedianTokensPerSecond,
                    )?.let(::attachHeuristicComparison) ?: previousCalibration
                }
            }
            val nativeBenchmark = if (isGguf && calibration != null) {
                _state.update { it.copy(benchmarkPhase = "Running native prompt/decode microbenchmark") }
                service.runNativeKernelBenchmark(
                    threadCount = calibration.threadCount,
                    promptTokens = NATIVE_BENCHMARK_PROMPT_TOKENS,
                    generatedTokens = NATIVE_BENCHMARK_GENERATED_TOKENS,
                    repetitions = if (depth == BenchmarkDepth.DEEP) {
                        DEEP_NATIVE_REPETITIONS
                    } else {
                        QUICK_NATIVE_REPETITIONS
                    },
                )
            } else {
                null
            }
            service.session.setNativeKernelBenchmark(
                result = nativeBenchmark,
                error = when {
                    !isGguf -> "Not applicable to PTE; real performance is measured through ExecuTorch generation callbacks"
                    calibration != null && nativeBenchmark == null ->
                        "Runtime returned no parseable native benchmark result"
                    else -> null
                },
            )
            if (isGguf) {
                completedRuns += 1
                service.session.recordPowerSample(
                    benchmarkPowerSampler.sample("after_native_microbenchmark", completedRuns),
                )
                _state.update { it.copy(benchmarkCompletedRuns = completedRuns) }
            }

            if (isGguf && calibration != null) {
                threadCalibrationRepository.save(activeModel.modelPath, calibration)
                service.session.setThreadCalibration(heuristicThreads, calibration)
            }
            service.session.recordPowerSample(benchmarkPowerSampler.sample("benchmark_complete", completedRuns))
            val summary = service.session.dashboard()
            val report = service.session.exportReport()
            val json = service.session.exportJson()
            _state.update {
                it.copy(
                    benchmarkRunning = false,
                    benchmarkPhase = null,
                    benchmarkSummary = summary,
                    benchmarkReport = report,
                    benchmarkJson = json,
                )
            }
            } catch (cancelled: CancellationException) {
                _state.update {
                    it.copy(
                        benchmarkRunning = false,
                        benchmarkPhase = null,
                        benchmarkError = "Benchmark cancelled. No incomplete calibration was saved.",
                    )
                }
                throw cancelled
            } catch (error: Throwable) {
                Log.e(BENCHMARK_TAG, "Benchmark suite failed", error)
                _state.update {
                    it.copy(
                        benchmarkRunning = false,
                        benchmarkPhase = null,
                        benchmarkError = "Benchmark stopped safely: " +
                            (error.message ?: error.javaClass.simpleName) +
                            ". Close other apps, cool the phone, and try Quick first.",
                    )
                }
            } finally {
                if (benchmarkJob === thisJob) benchmarkJob = null
            }
        }
    }

    fun stopBenchmark() {
        if (!_state.value.benchmarkRunning) return
        cachedService?.stopGeneration()
        benchmarkJob?.cancel(CancellationException("Benchmark stopped by user"))
    }

    private fun benchmarkMemoryIsLow(): Boolean {
        val manager = getApplication<Application>()
            .getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return info.lowMemory
    }

    fun clearBenchmark() {
        cachedService?.session?.clear()
        _state.update {
            it.copy(
                benchmarkCompletedRuns = 0,
                benchmarkTotalRuns = 0,
                benchmarkPhase = null,
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
                inferencePhase = InferencePhase.PREPARING_PROMPT,
            )
        }

        generationJob = viewModelScope.launch {
            val thisJob = currentCoroutineContext()[Job]
            try {
                val config = aiConfigRepository.load()
                val service = getService(config)
                val resourcePlan = service.prepareResourcePlan(
                    deterministicGeneration = currentMode == AiMode.CODE,
                )
                if (resourcePlan == null) {
                    showGenerationFailure("No configured model is available for memory planning.")
                    return@launch
                }
                if (!resourcePlan.allowed) {
                    showGenerationFailure(resourcePlan.blockingMessage())
                    return@launch
                }

                // A phone-safe completion can be much shorter than a multi-file project. Give
                // every requested file its own clean generation budget so one cut-off fence can
                // never discard all of the model's work.
                if (currentMode == AiMode.CODE && currentModelMode == ModelMode.SINGLE) {
                    runSingleModelCodePipeline(
                        service = service,
                        config = config,
                        resourcePlan = resourcePlan,
                        request = prompt,
                    )
                    return@launch
                }

                val systemPrompt = when (currentMode) {
                    AiMode.CODE -> if (currentModelMode == ModelMode.SWARM) {
                        ARCHITECT_SYSTEM_PROMPT
                    } else {
                        CODE_MODE_PROMPT_V2
                    }
                    AiMode.ASK -> ASK_MODE_PROMPT
                    AiMode.PLAN -> PLAN_MODE_PROMPT
                }

                // Prompt pruning and the native context must use the same resource decision.
                val managed = ContextManager.buildContext(
                    systemPrompt = systemPrompt,
                    history = historyForRequest,
                    userMessage = prompt,
                    files = _state.value.files,
                    contextWindowSize = resourcePlan.effectiveContext,
                    responseTokenBudget = resourcePlan.maxOutputTokens,
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
                    resourcePlan = resourcePlan,
                    onPhase = ::updateInferencePhase,
                    onToken = { _ -> tokenCount++ },
                    deterministicGeneration = currentMode == AiMode.CODE,
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
                    is AiResult.Error -> showGenerationFailure(result.message)
                    is AiResult.Success -> when (currentMode) {
                        AiMode.CODE -> {
                            if (currentModelMode == ModelMode.SWARM) {
                                runSwarmPipeline(service, config, resourcePlan, result.content, tps)
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
                                inferencePhase = InferencePhase.COMPLETE,
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
                                inferencePhase = InferencePhase.COMPLETE,
                                lastTokensPerSecond = tps,
                                lastTtftMs = benchTtft,
                                lastMemoryDeltaMb = benchMemDelta,
                                lastStrategy = benchStrategy,
                                lastPipelineLog = benchPipelineLog,
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                if (_state.value.inferencePhase != InferencePhase.CANCELLED) {
                    _state.update {
                        it.copy(
                            isGenerating = false,
                            isThinking = false,
                            inferencePhase = InferencePhase.CANCELLED,
                        )
                    }
                }
                throw cancelled
            } catch (error: Throwable) {
                Log.e(AI_GENERATION_TAG, "AI generation failed", error)
                showGenerationFailure(error.message ?: error.javaClass.simpleName)
            } finally {
                _state.update { it.copy(isGenerating = false, isThinking = false) }
                if (generationJob === thisJob) generationJob = null
            }
        }
    }

    fun stopGeneration() {
        if (!_state.value.isGenerating) return
        cachedService?.stopGeneration()
        generationJob?.cancel(CancellationException("Stopped by user"))
        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(
                    role = MessageRole.SYSTEM,
                    content = "Generation stopped.",
                    agentStatus = AgentStatus.IDLE,
                ),
                isGenerating = false,
                isThinking = false,
                inferencePhase = InferencePhase.CANCELLED,
            )
        }
    }

    private fun updateInferencePhase(phase: InferencePhase) {
        _state.update {
            if (!it.isGenerating) it else it.copy(
                inferencePhase = phase,
                isThinking = phase == InferencePhase.PREPARING_PROMPT ||
                    phase == InferencePhase.LOADING_MODEL ||
                    phase == InferencePhase.READING_PROMPT,
            )
        }
    }

    private fun showGenerationFailure(message: String) {
        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(
                    role = MessageRole.SYSTEM,
                    content = "Error: $message",
                    agentStatus = AgentStatus.ERROR,
                ),
                architectStatus = AgentStatus.ERROR,
                isGenerating = false,
                isThinking = false,
                inferencePhase = InferencePhase.FAILED,
            )
        }
    }

    /** Reliable single-model Code mode for small output budgets on memory-constrained phones. */
    private suspend fun runSingleModelCodePipeline(
        service: AiService,
        config: com.pocketide.data.ai.AiConfig,
        resourcePlan: InferenceResourcePlan,
        request: String,
    ) {
        val snapshot = _state.value
        val activeFile = snapshot.files.getOrNull(snapshot.activeFileIndex)
        val targets = CodeGenerationPipeline.resolveTargets(request, activeFile)
        val targetNames = targets.map { it.filename.lowercase() }.toSet()
        val existingProjectFiles = snapshot.files.filter { it.name.lowercase() in targetNames }

        _state.update {
            it.copy(
                architectStatus = AgentStatus.DONE,
                coderStatus = AgentStatus.GENERATING,
                isThinking = true,
                inferencePhase = InferencePhase.PREPARING_PROMPT,
            )
        }

        var latestTtftMs: Long? = null
        var latestMemoryDeltaMb: Float? = null
        var latestStrategy: String? = null
        var latestPipelineLog: String? = null

        val outcome = CodeGenerationPipeline.generateProject(
            originalRequest = request,
            targets = targets,
            outputTokenLimit = resourcePlan.maxOutputTokens,
            // Reuse the user's bounded repair preference. More retries add heat and do not
            // reliably improve a weak local model forever, so keep the local loop finite.
            maxAttempts = config.maxRepairIterations.coerceIn(1, 4),
        ) { attempt ->
            _state.update {
                it.copy(
                    coderStatus = AgentStatus.GENERATING,
                    isThinking = true,
                    inferencePhase = InferencePhase.PREPARING_PROMPT,
                )
            }

            val contextFiles = buildGenerationContextFiles(
                existingFiles = if (config.enableCodeContext) existingProjectFiles else emptyList(),
                attempt = attempt,
            )
            val activeContextIndex = contextFiles.indexOfFirst {
                it.name.equals(attempt.target.filename, ignoreCase = true)
            }.takeIf { it >= 0 } ?: contextFiles.lastIndex.coerceAtLeast(0)
            val managed = ContextManager.buildContext(
                systemPrompt = CodeGenerationPipeline.systemPrompt(attempt),
                history = emptyList(),
                userMessage = CodeGenerationPipeline.userPrompt(attempt),
                files = contextFiles,
                contextWindowSize = resourcePlan.effectiveContext,
                responseTokenBudget = resourcePlan.maxOutputTokens,
                activeFileIndex = activeContextIndex,
                // Completed siblings are required to keep imports and element IDs consistent,
                // even when optional conversation code context is disabled in Settings.
                enableCodeContext = contextFiles.isNotEmpty(),
                enableHistorySummary = false,
            )

            val startedAt = System.currentTimeMillis()
            var streamedTokens = 0
            when (val result = service.chatCompletion(
                systemPrompt = managed.systemPrompt,
                history = managed.history,
                userMessage = managed.userMessage,
                resourcePlan = resourcePlan,
                onPhase = ::updateInferencePhase,
                onToken = { _ -> streamedTokens++ },
                deterministicGeneration = true,
            )) {
                is AiResult.Error -> CodeModelReply.Error(result.message)
                is AiResult.Success -> {
                    val elapsedSeconds = (System.currentTimeMillis() - startedAt) / 1000f
                    val callbackTps = if (elapsedSeconds > 0f) streamedTokens / elapsedSeconds else null
                    val measuredTps = result.benchmark?.tokensPerSecond?.takeIf { it > 0f } ?: callbackTps
                    latestTtftMs = result.benchmark?.ttftMs?.takeIf { it >= 0 }
                    latestMemoryDeltaMb = result.benchmark?.let {
                        it.memoryDeltaBytes / (1024f * 1024f)
                    }
                    latestStrategy = result.tuning?.strategy?.displayName
                    latestPipelineLog = result.pipelineLog
                    val generatedTokens = result.benchmark?.tokenCount ?: streamedTokens
                    val attemptedOutputTokens = result.tuning?.seqLen ?: resourcePlan.maxOutputTokens
                    CodeModelReply.Success(
                        content = result.content,
                        tokensPerSecond = measuredTps,
                        hitOutputLimit = generatedTokens >= attemptedOutputTokens,
                    )
                }
            }
        }

        when (outcome) {
            is CodeProjectGenerationResult.Error -> {
                _state.update {
                    it.copy(
                        messages = it.messages + ChatMessage(
                            role = MessageRole.CODER,
                            content = outcome.message,
                            agentStatus = AgentStatus.ERROR,
                        ),
                        architectStatus = AgentStatus.DONE,
                        coderStatus = AgentStatus.ERROR,
                        isGenerating = false,
                        isThinking = false,
                        inferencePhase = InferencePhase.FAILED,
                    )
                }
            }
            is CodeProjectGenerationResult.Success -> {
                applyGeneratedFiles(
                    plan = outcome.plan + " Generated ${outcome.files.size} complete file(s) " +
                        "in ${outcome.totalModelCalls} bounded local generation(s).",
                    generatedFiles = outcome.files,
                    tokensPerSecond = outcome.averageTokensPerSecond,
                )
                _state.update {
                    it.copy(
                        lastTtftMs = latestTtftMs,
                        lastMemoryDeltaMb = latestMemoryDeltaMb,
                        lastStrategy = latestStrategy,
                        lastPipelineLog = latestPipelineLog,
                    )
                }
            }
        }
    }

    private fun buildGenerationContextFiles(
        existingFiles: List<CodeFile>,
        attempt: CodeFileAttempt,
    ): List<CodeFile> {
        val merged = linkedMapOf<String, CodeFile>()
        existingFiles.forEach { file -> merged[file.name.lowercase()] = file }
        attempt.completedFiles.forEach { generated ->
            merged[generated.filename.lowercase()] = CodeFile(
                name = generated.filename,
                language = generated.language,
                content = generated.code,
            )
        }
        return merged.values.toList()
    }

    /**
     * SWARM pipeline: Architect plan, Coder generation, then Validator repair.
     * The initial AI response is treated as the Architect's plan. The Coder then
     * generates code from that plan. If execution fails, the Validator repairs
     * automatically up to [AiConfig.maxRepairIterations] times.
     */
    private suspend fun runSwarmPipeline(
        service: AiService,
        config: com.pocketide.data.ai.AiConfig,
        resourcePlan: InferenceResourcePlan,
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
            contextWindowSize = resourcePlan.effectiveContext,
            responseTokenBudget = resourcePlan.maxOutputTokens,
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
            resourcePlan = resourcePlan,
            onPhase = ::updateInferencePhase,
            onToken = { _ -> coderTokens++ },
            deterministicGeneration = true,
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
                        inferencePhase = InferencePhase.FAILED,
                    )
                }
                return
            }
            is AiResult.Success -> {
                if (!applyAiResponse(coderResult.content, coderTps)) return
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
                    inferencePhase = InferencePhase.COMPLETE,
                )
            }
            return
        }

        // Step 4: Validator — autonomous repair loop
        runAutonomousRepair(
            service,
            config,
            resourcePlan,
            activeFile,
            maxIterations = config.maxRepairIterations,
        )
    }

    /**
     * Autonomous repair loop: sends error context back to the AI, re-applies
     * the fixed code, and re-executes. Repeats up to [maxIterations] times
     * or until the code executes successfully.
     */
    private suspend fun runAutonomousRepair(
        service: AiService,
        config: com.pocketide.data.ai.AiConfig,
        resourcePlan: InferenceResourcePlan,
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
                contextWindowSize = resourcePlan.effectiveContext,
                responseTokenBudget = resourcePlan.maxOutputTokens,
                activeFileIndex = _state.value.activeFileIndex,
                enableCodeContext = config.enableCodeContext,
                enableHistorySummary = config.enableHistorySummary,
            )

            val repairResult = service.chatCompletion(
                systemPrompt = repairManaged.systemPrompt,
                history = repairManaged.history,
                userMessage = repairManaged.userMessage,
                resourcePlan = resourcePlan,
                onPhase = ::updateInferencePhase,
                deterministicGeneration = true,
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
                            inferencePhase = InferencePhase.FAILED,
                        )
                    }
                    return
                }
                is AiResult.Success -> {
                    if (!applyAiResponse(repairResult.content, null)) return
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
                        inferencePhase = InferencePhase.COMPLETE,
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
                inferencePhase = InferencePhase.FAILED,
            )
        }
    }

    private fun applyAiResponse(rawContent: String, tokensPerSecond: Float? = null): Boolean {
        _state.update { it.copy(inferencePhase = InferencePhase.APPLYING_FILES, isThinking = false) }
        val parsed = parseAiResponse(rawContent)

        if (parsed.isTruncated) {
            _state.update {
                it.copy(
                    messages = it.messages + ChatMessage(
                        role = MessageRole.CODER,
                        content = rawContent +
                            "\n\nResponse ended before the closing code fence. The partial output was not " +
                            "written over your file. Copy it here, or ask the model to regenerate a smaller complete version.",
                        agentStatus = AgentStatus.ERROR,
                        tokensPerSecond = tokensPerSecond,
                    ),
                    architectStatus = AgentStatus.DONE,
                    coderStatus = AgentStatus.ERROR,
                    isGenerating = false,
                    isThinking = false,
                    inferencePhase = InferencePhase.FAILED,
                    lastTokensPerSecond = tokensPerSecond,
                )
            }
            return false
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
            _state.update {
                it.copy(
                    messages = it.messages + ChatMessage(
                        role = MessageRole.CODER,
                        content = rawContent + "\n\nNo complete supported file was found in the model response.",
                        agentStatus = AgentStatus.ERROR,
                        tokensPerSecond = tokensPerSecond,
                    ),
                    coderStatus = AgentStatus.ERROR,
                    isGenerating = false,
                    isThinking = false,
                    inferencePhase = InferencePhase.FAILED,
                )
            }
            return false
        }

        return applyGeneratedFiles(
            plan = parsed.plan ?: "Apply the complete generated file${if (generatedFiles.size == 1) "" else "s"}.",
            generatedFiles = generatedFiles,
            tokensPerSecond = tokensPerSecond,
        )
    }

    private fun applyGeneratedFiles(
        plan: String,
        generatedFiles: List<ParsedAiFile>,
        tokensPerSecond: Float?,
    ): Boolean {
        if (generatedFiles.isEmpty()) return false
        _state.update { it.copy(inferencePhase = InferencePhase.APPLYING_FILES, isThinking = false) }
        val s = _state.value
        val newFiles = s.files.toMutableList()
        var targetIndex = s.activeFileIndex.coerceIn(0, newFiles.lastIndex.coerceAtLeast(0))
        generatedFiles.forEach { generated ->
            val existingIndex = newFiles.indexOfFirst {
                it.name.equals(generated.filename, ignoreCase = true)
            }
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
                messages = it.messages + listOf(
                    ChatMessage(
                        role = MessageRole.ARCHITECT,
                        content = plan,
                        agentStatus = AgentStatus.DONE,
                        tokensPerSecond = tokensPerSecond,
                    ),
                    ChatMessage(
                        role = MessageRole.CODER,
                        content = generatedMessage,
                        agentStatus = AgentStatus.DONE,
                        tokensPerSecond = tokensPerSecond,
                    ),
                ),
                architectStatus = AgentStatus.DONE,
                coderStatus = AgentStatus.DONE,
                files = newFiles,
                activeFileIndex = targetIndex,
                activeFileContent = activeGenerated.content,
                isGenerating = false,
                isThinking = false,
                inferencePhase = InferencePhase.COMPLETE,
                lastTokensPerSecond = tokensPerSecond,
                unsavedCount = newFiles.count { f -> f.isModified },
            )
        }

        viewModelScope.launch {
            repository.saveFiles(s.projectName, newFiles)
        }
        return true
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

        generationJob = viewModelScope.launch {
            val thisJob = currentCoroutineContext()[Job]
            try {
                val config = aiConfigRepository.load()
                val service = getService(config)
                val resourcePlan = service.prepareResourcePlan(deterministicGeneration = true)
                if (resourcePlan == null || !resourcePlan.allowed) {
                    showGenerationFailure(
                        resourcePlan?.blockingMessage() ?: "Could not inspect device memory before repair.",
                    )
                    return@launch
                }
                runAutonomousRepair(
                    service,
                    config,
                    resourcePlan,
                    activeFile,
                    maxIterations = config.maxRepairIterations,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(AI_GENERATION_TAG, "AI repair failed", error)
                showGenerationFailure(error.message ?: error.javaClass.simpleName)
            } finally {
                _state.update { it.copy(isGenerating = false, isThinking = false) }
                if (generationJob === thisJob) generationJob = null
            }
        }
    }
}

private const val BENCHMARK_SYSTEM_PROMPT = """You are an on-device coding model benchmark. Follow the request directly, keep the answer deterministic and concise, and do not discuss the benchmark."""

private const val BENCHMARK_FIXED_PROMPT = "Write Python code that parses JSON Lines sensor readings and returns min, max, and average per sensor while safely skipping malformed records. Output code only."

private const val MAX_QUICK_BENCHMARK_THREADS = 4
private const val MAX_DEEP_BENCHMARK_THREADS = 8
private const val QUICK_BENCHMARK_OUTPUT_TOKENS = 96
private const val DEEP_BENCHMARK_OUTPUT_TOKENS = 128
private const val QUICK_MEASURED_RUNS = 3
private const val PTE_DEEP_MEASURED_RUNS = 5
private const val DEEP_SCREENING_RUNS = 2
private const val DEEP_CONFIRMATION_RUNS = 4
private const val DEEP_FINALIST_COUNT = 3
private const val SUSTAINED_MEASURED_RUNS = 8
private const val NATIVE_BENCHMARK_PROMPT_TOKENS = 128
private const val NATIVE_BENCHMARK_GENERATED_TOKENS = 32
private const val QUICK_NATIVE_REPETITIONS = 3
private const val DEEP_NATIVE_REPETITIONS = 5
private const val BENCHMARK_TAG = "PocketIDEBenchmark"
private const val AI_GENERATION_TAG = "PocketIDEGeneration"

private data class BenchmarkPlan(
    val profile: String,
    val threads: Int,
    val warmup: Boolean,
    val phase: String,
)

private const val CODE_MODE_PROMPT_V2 = """You are PocketIDE's local coding agent. Build the requested program directly. Never refuse a normal coding request or say that an AI cannot build it. If one detail is unavailable on Android, implement the closest runnable local version and mention that limit only in PLAN.

Return exactly:
PLAN: one short sentence
FILE: filename.ext
```language
complete runnable code
```

For multiple files, repeat FILE plus its fenced code block. No commentary outside this format. Do not use placeholders or TODOs. Keep the implementation compact enough to finish. Never stop inside a string or code block, and always close every quote and code fence before ending.

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

Repeat FILE blocks when the plan needs multiple files. No TODOs and no text outside the format. Keep the implementation compact enough to finish, and always close every string and code fence. Python is CPython 3.11 with input() and sibling imports. JavaScript/TypeScript must remain Rhino ES5-compatible. Lua, SQLite, Android sh, and BeanShell are available. A global hardware object exposes Android hardware when requested."""

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
