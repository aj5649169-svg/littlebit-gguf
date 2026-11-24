package com.littlebit.chat.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.littlebit.chat.data.ChatMessage
import com.littlebit.chat.data.ChatSession
import com.littlebit.chat.data.ChatStore
import com.littlebit.chat.data.ModelFiles
import com.littlebit.chat.data.SettingsStore
import com.littlebit.chat.engine.LlamaEngine
import com.littlebit.chat.engine.ThinkBlocks
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shared state holder for the chat UI, model hub and settings screens.
 *
 * Owns the [LlamaEngine] (all native calls are serialized through
 * [engineMutex] — only one generate/continue may touch llama.cpp at a time),
 * chat-history persistence, sampling settings, Fast/Think handling and the
 * speculative background pre-warm.
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = LlamaEngine()
    val settings = SettingsStore(app)
    private val store = ChatStore(File(app.filesDir, "chats/chats.json"))

    // ------------------------------------------------------------------ model
    var modelInfo by mutableStateOf<LlamaEngine.ModelInfo?>(null)
        private set
    var modelName by mutableStateOf<String?>(null)
        private set
    var loadProgress by mutableFloatStateOf(-1f)
        private set
    var loadStatus by mutableStateOf<String?>(null)
        private set
    var localModels by mutableStateOf<List<File>>(emptyList())
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var isCopyingModel by mutableStateOf(false)
        private set
    var copyTotalBytes by mutableStateOf(-1L)
        private set
    var modelLoadSuccessVersion by mutableIntStateOf(0)
        private set
    var attachment by mutableStateOf<Attachment?>(null)
        private set
    private var attachmentRequest = 0
    var ttft by mutableStateOf(0.0)
    var prefill by mutableStateOf(0.0)
    var used by mutableIntStateOf(0)
    var capacity by mutableIntStateOf(0)
    var benchmarking by mutableStateOf(false)
    val busy get() = isGenerating || benchmarking || loadProgress >= 0f
    private var cacheSession: String? = null
    private var cacheSystem: String? = null
    val sortedSessions get() = sessions.sortedWith(compareByDescending<ChatSession> { it.pinned }.thenByDescending { it.updatedAt })
    fun rename(id: String, name: String) {
        if (name.isBlank()) return
        sessions.find { it.id == id }?.let { it.title = name.trim().take(100); store.save() }
    }
    fun pin(id: String) {
        sessions.find { it.id == id }?.let { it.pinned = !it.pinned; store.save() }
    }
    fun clearAttachment() { attachmentRequest++; attachment = null; cancelPrewarm() }
    fun attach(uri: Uri) {
        clearAttachment()
        val request = attachmentRequest
        viewModelScope.launch {
            val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching { readAttachment(getApplication(), uri) }
            }
            if (request == attachmentRequest) result.onSuccess { attachment = it }.onFailure { chatError = it.message }
        }
    }
    fun invalidateDraft() { cancelPrewarm(); cacheSession = null }
    private var activeModelPath: String? = null
    var chatError by mutableStateOf<String?>(null)
        private set
    var promptProgress by mutableStateOf("")
        private set
    var isPrefilling by mutableStateOf(false)
        private set
    val lastModelName: String?
        get() = settings.lastModelPath?.let { File(it).takeIf(File::exists)?.name }

    fun reloadLastModel() {
        val path = settings.lastModelPath ?: return
        val file = File(path)
        if (file.isFile) loadLocalModel(file) else chatError = "Previously loaded model is missing: $path"
    }

    fun dismissChatError() { chatError = null }

    val isLoaded: Boolean get() = engine.isLoaded
    val isUsingVulkan: Boolean get() = (modelInfo?.gpuLayers ?: 0) > 0
    val activeBackendLabel: String
        get() = if (isUsingVulkan) "GPU · Vulkan" else "CPU · KleidiAI"

    // ------------------------------------------------------------------- chat
    val sessions: List<ChatSession> get() = store.sessions
    var currentSession by mutableStateOf<ChatSession?>(null)
        private set
    val currentMessages = mutableStateListOf<ChatMessage>()

    // -------------------------------------------------------------- generation
    var input by mutableStateOf("")
    var isGenerating by mutableStateOf(false)
        private set
    var streamActive by mutableStateOf(false)
        private set
    var rawStream by mutableStateOf("")
        private set
    var lastTps by mutableStateOf(0.0)
        private set
    var lastTokens by mutableIntStateOf(0)
        private set

    // ------------------------------------------------------------ fast / think
    /** Fast mode appends /no_think and hides the thought-process accordion. */
    var fastMode by mutableStateOf(settings.fastMode)
        private set

    fun updateFastMode(enabled: Boolean) {
        if (fastMode == enabled) return
        fastMode = enabled
        settings.fastMode = enabled
        cancelPrewarm()
    }

    // ---------------------------------------------------- speculative pre-warm
    var instantReply by mutableStateOf(settings.instantReply)
        private set

    fun updateInstantReply(enabled: Boolean) {
        if (instantReply == enabled) return
        instantReply = enabled
        settings.instantReply = enabled
        if (!enabled) cancelPrewarm()
    }

    var preWarming by mutableStateOf(false)
        private set
    private var speculative: SpeculativeDraft? = null
    private var prewarmJob: Job? = null

    private val engineMutex = Mutex()

    init {
        store.load()
        val restored = store.sessions.firstOrNull()
        if (restored != null) activate(restored) else createChat()
        refreshLocalModels()
    }

    // ------------------------------------------------------------- chat flows
    private var newChatAfterTurn = false
    fun newChatFromMeter() {
        if (isGenerating) { newChatAfterTurn = true; stopGeneration() } else createChat()
    }

    fun createChat() {
        if (busy) return
        cancelPrewarm()
        syncActiveToStore()
        activate(store.create())
    }

    fun switchChat(id: String) {
        if (busy) return
        if (currentSession?.id == id) return
        cancelPrewarm()
        syncActiveToStore()
        store.sessions.firstOrNull { it.id == id }?.let(::activate)
    }

    fun deleteChat(id: String) {
        if (busy) return
        cancelPrewarm()
        val deletingCurrent = currentSession?.id == id
        store.delete(id)
        if (deletingCurrent) {
            currentSession = null
            currentMessages.clear()
            store.sessions.firstOrNull()?.let(::activate) ?: activate(store.create())
        }
    }

    private fun activate(session: ChatSession) {
        invalidateDraft()
        clearAttachment()
        used = 0; ttft = 0.0; prefill = 0.0; lastTps = 0.0
        currentSession = session
        currentMessages.clear()
        currentMessages.addAll(session.messages)
        input = ""
        rawStream = ""
        streamActive = false
    }

    /** Copy the observable message list back into the persistent session. */
    private fun syncActiveToStore() {
        val s = currentSession ?: return
        try {
            s.messages.clear()
            s.messages.addAll(currentMessages)
            store.touch(s)
            store.lastError?.let { chatError = it }
        } catch (t: Throwable) {
            chatError = "Chat history save failed: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    private fun persistStreamingDraft() {
        val s = currentSession ?: return
        try {
            store.persistDraft(s, currentMessages.toList(), rawStream)
            store.lastError?.let { chatError = it }
        } catch (t: Throwable) {
            chatError = "Chat draft save failed: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    // ------------------------------------------------------------ model flows
    fun refreshLocalModels() {
        viewModelScope.launch(Dispatchers.IO) {
            localModels = ModelFiles.listLocalModels(getApplication())
        }
    }

    fun dismissLoadError() {
        loadError = null
    }

    fun loadModelFromUri(uri: Uri) {
        if (busy) return
        loadProgress = 0f
        viewModelScope.launch(Dispatchers.IO) {
            loadError = null
            loadStatus = "Copying selected .gguf into app storage…"
            loadProgress = 0f
            isCopyingModel = true
            copyTotalBytes = -1L
            try {
                val destination = ModelFiles.copyModelToAppStorage(getApplication(), uri) {
                    copiedBytes, totalBytes ->
                    copyTotalBytes = totalBytes
                    loadProgress = if (totalBytes > 0L) {
                        0.35f * (copiedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                }
                isCopyingModel = false
                localModels = ModelFiles.listLocalModels(getApplication())
                loadFromFile(destination)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                loadStatus = null
                loadError = "Model copy failed: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                isCopyingModel = false
                copyTotalBytes = -1L
                loadProgress = -1f
            }
        }
    }

    fun loadLocalModel(file: File) {
        if (busy) return
        loadProgress = 0f
        viewModelScope.launch(Dispatchers.IO) {
            loadError = null
            try {
                loadFromFile(file)
            } finally {
                loadProgress = -1f
            }
        }
    }

    fun deleteLocalModel(file: File) {
        if (busy) return
        loadProgress = 0f
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (activeModelPath == file.absolutePath) {
                    cancelPrewarmSuspend()
                    engineMutex.withLock {
                        if (engine.isLoaded) engine.unload()
                    }
                    modelInfo = null
                    modelName = null
                    activeModelPath = null
                }
                if (!ModelFiles.deleteLocalModel(getApplication(), file)) {
                    throw IllegalStateException("Could not delete ${file.name}.")
                }
                localModels = ModelFiles.listLocalModels(getApplication())
                loadError = null
                loadStatus = "Deleted ${file.name}."
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                loadError = "Delete failed: ${t.message ?: t.javaClass.simpleName}"
            } finally { loadProgress = -1f }
        }
    }

    private suspend fun loadFromFile(file: File) {
        loadError = null
        loadStatus = "Loading ${file.name}…"
        loadProgress = 0.35f
        try {
            require(file.isFile && file.length() > 0L) {
                "The selected model file is missing or empty."
            }
            require(file.extension.equals("gguf", ignoreCase = true)) {
                "Only .gguf model files can be loaded."
            }

            cancelPrewarmSuspend()
            engineMutex.withLock {
                if (engine.isLoaded) engine.unload()
            }
            modelInfo = null
            modelName = null
            activeModelPath = null

            val info = engineMutex.withLock {
                engine.loadModel(
                    path = file.absolutePath,
                    contextSize = settings.contextSize,
                    threads = settings.threads,
                    nGpuLayers = if (settings.vulkanOffload) settings.vulkanGpuLayers else 0,
                    batch = settings.batch, flash = settings.flash,
                    onProgress = { progress ->
                        loadProgress = 0.35f + (progress.coerceIn(0f, 1f) * 0.65f)
                    },
                ).also { capacity = engine.metrics()[3].toInt() }
            }
            cacheSession = null
            used = 0
            if (settings.vulkanOffload && settings.vulkanGpuLayers > 0 && info.gpuLayers == 0) {
                chatError = "Vulkan unavailable for this load. Using CPU · KleidiAI."
            }
            modelInfo = info
            modelName = file.name
            activeModelPath = file.absolutePath
            loadProgress = 1f
            loadStatus = null
            loadError = null
            settings.lastModelPath = file.absolutePath
            modelLoadSuccessVersion += 1
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (!engine.isLoaded) {
                modelInfo = null
                modelName = null
                activeModelPath = null
            }
            loadStatus = null
            loadError = "Model load failed for ${file.name}: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    fun unloadModel() {
        if (busy) return
        cancelPrewarm()
        loadProgress = 0f
        viewModelScope.launch {
            engineMutex.withLock { engine.unload() }
            loadProgress = -1f
            modelInfo = null
            modelName = null
            activeModelPath = null
            loadStatus = "Model unloaded — RAM freed."
        }
    }

    /** Live-apply the thread count while a model is loaded. */
    fun onThreadsChanged(n: Int) {
        settings.threads = n
        if (!busy) viewModelScope.launch { engineMutex.withLock { if (engine.isLoaded) engine.setThreads(settings.threads) } }
    }

    // --------------------------------------------------------- generation flow
    fun onInputChange(value: String) {
        input = value
        if (speculative != null) {
            // Instant cancel: the millisecond the user types or deletes,
            // signal the native atomic stop flag and discard the buffer.
            cancelPrewarm()
        }
        schedulePrewarm(value)
    }

    fun send() = sendFrom(null)

    private fun sendFrom(replaceFrom: Int?) {
        val file = attachment
        val text = listOfNotNull(input.trim().takeIf { it.isNotEmpty() }, file?.prompt).joinToString("\n\n")
        val calc = settings.calculator && file == null && text.startsWith("/calc ")
        if (text.isBlank() || busy || (!engine.isLoaded && !calc)) return
        val draft = speculative?.takeIf { it.prompt == text }
        input = ""
        attachmentRequest++
        attachment = null
        isGenerating = true
        viewModelScope.launch(Dispatchers.Default) {
            var appended = false
            try {
                cancelPrewarmSuspend()
                fun commitUser(text: String, fast: Boolean = false) {
                    if (replaceFrom != null) {
                        while (currentMessages.size > replaceFrom) currentMessages.removeAt(currentMessages.lastIndex)
                    }
                    currentMessages.add(ChatMessage("user", text, fast = fast))
                    appended = true
                }
                if (calc) {
                    commitUser(text)
                    val answer = com.littlebit.chat.plugins.PluginRegistry.calculator.execute(text)
                    currentMessages.add(ChatMessage("assistant", answer))
                    used = 0
                    syncActiveToStore()
                } else engineMutex.withLock {
                    val matched = draft?.takeIf { it.tokens > 0 && it.formatted.isNotEmpty() &&
                        it.sampling == samplingParams() && !settings.clock && cacheSession == currentSession?.id && cacheSystem == settings.systemPrompt }
                    val prior = replaceFrom?.let { currentMessages.take(it) } ?: currentMessages.toList()
                    val prompt = matched?.formatted ?: formatPrompt(text, prior)
                    commitUser(text, fastMode)
                    syncActiveToStore()
                    generateReply(prompt, matched)
                }
            } catch (e: Exception) {
                if (!appended && input.isBlank()) { input = text }
                chatError = e.message ?: "Generation failed"
            } finally { endTurn() }
        }
    }

    fun sendText(text: String) { if (!busy) { input = text; send() } }
    fun stopGeneration() { engine.stop() }

    fun removeMessage(index: Int) {
        if (busy || index !in currentMessages.indices) return
        invalidateDraft()
        currentMessages.removeAt(index)
        syncActiveToStore()
    }
    fun resend(index: Int, replacement: String) {
        if (busy || !isLoaded || replacement.isBlank() || currentMessages.getOrNull(index)?.role != "user") return
        invalidateDraft()
        clearAttachment()
        input = replacement
        sendFrom(index)
    }
    fun regenerate(index: Int) {
        if (index != currentMessages.lastIndex) return
        val user = currentMessages.indexOfLast { it.role == "user" }
        if (user >= 0) resend(user, currentMessages[user].content)
    }
    fun continueReply(index: Int) {
        if (busy || !isLoaded || index != currentMessages.lastIndex) return
        val previous = currentMessages.getOrNull(index) ?: return
        val user = currentMessages.getOrNull(index - 1) ?: return
        if (!previous.cutOff || user.role != "user") return
        isGenerating = true
        viewModelScope.launch(Dispatchers.Default) {
            try {
                cancelPrewarmSuspend()
                engineMutex.withLock {
                    val prompt = formatPrompt(user.content, currentMessages.take(index - 1), user.fast, previous.content)
                    generateReply(prompt, null, previous.content, index)
                }
            } catch (e: Exception) { chatError = e.message } finally { endTurn() }
        }
    }
    private fun endTurn() {
        rawStream = ""
        streamActive = false
        isPrefilling = false
        isGenerating = false
        if (newChatAfterTurn) {
            newChatAfterTurn = false
            viewModelScope.launch { createChat() }
        }
    }

    // Called with the engine mutex. Trim whole oldest turns in the inference
    // prompt only; the complete conversation remains available in history.
    private fun formatPrompt(userText: String, prior: List<ChatMessage> = currentMessages.toList(),
                             fast: Boolean = fastMode, tail: String = ""): String {
        val system = settings.systemPrompt + if (settings.clock) "\n" + com.littlebit.chat.plugins.PluginRegistry.timeContext() else ""
        val history = prior.map { m ->
            m.role to when {
                m.role == "assistant" -> ThinkBlocks.strip(m.content)
                m.fast -> ThinkBlocks.withNoThink(m.content)
                else -> m.content
            }
        }.toMutableList()
        history.add("user" to if (fast) ThinkBlocks.withNoThink(userText) else userText)
        var trimmed = false
        while (true) {
            val prompt = engine.formatChat(system, history) + tail
            val size = engine.promptSize(prompt)
            if (size <= capacity - minOf(128, capacity / 8) || history.size == 1) {
                require(size in 1..(capacity - 4)) { "This message exceeds the loaded context. Shorten it or reload with a larger context." }
                if (trimmed || size >= capacity - 128 || cacheSession != currentSession?.id || cacheSystem != system) {
                    engine.reset()
                }
                cacheSession = currentSession?.id
                cacheSystem = system
                return prompt
            }
            history.removeAt(0)
            while (history.size > 1 && history.first().first != "user") history.removeAt(0)
            trimmed = true
        }
    }

    private fun samplingParams(maxTokens: Int = settings.maxTokens) = LlamaEngine.SamplingParams(
        temperature = settings.temperature, repeatPenalty = settings.repeatPenalty,
        dryMultiplier = settings.dryMultiplier, maxTokens = maxTokens,
        topK = settings.topK, topP = settings.topPercent / 100.0, minP = settings.minPercent / 100.0)

    private fun updateMetrics() {
        val m = engine.metrics()
        ttft = m[0]
        prefill = m[1]
        used = m[2].toInt()
        capacity = m[3].toInt()
        lastTps = m[4]
    }

    private fun generateReply(prompt: String, draft: SpeculativeDraft?, seed: String = "", replaceIndex: Int? = null) {
        streamActive = true
        rawStream = seed + (draft?.buffer?.toString() ?: "")
        isPrefilling = rawStream.isEmpty()
        lastTokens = draft?.tokens ?: 0
        lastTps = 0.0
        engine.setThreads(settings.threads)
        promptProgress = ""
        val progress: (Int, Int) -> Unit = { done, total -> promptProgress = "$done / $total tokens" }
        val output = StringBuilder(rawStream)
        var nextUpdate = 0L
        var nextSave = android.os.SystemClock.elapsedRealtime() + 2000L
        var pieces = 0
        val accept: (String) -> Unit = { piece ->
            output.append(piece)
            val now = android.os.SystemClock.elapsedRealtime()
            if (now >= nextUpdate) {
                rawStream = output.toString()
                isPrefilling = false
                nextUpdate = now + 40L
            }
            pieces++
            if (pieces == 1 || pieces % 8 == 0) {
                updateMetrics()
            }
            if (now >= nextSave && replaceIndex == null) {
                persistStreamingDraft()
                nextSave = now + 2000L
            }
        }
        val result = when {
            draft == null -> engine.generate(prompt, samplingParams(), progress, accept)
            draft.isComplete -> requireNotNull(draft.result)
            else -> {
                val remaining = (settings.maxTokens - draft.tokens).coerceAtLeast(1)
                val continued = engine.continueGenerate(samplingParams(remaining), accept)
                if (continued.status < 0) {
                    output.setLength(0)
                    output.append(seed)
                    rawStream = seed
                    engine.reset()
                    engine.generate(prompt, samplingParams(), progress, accept)
                } else continued.copy(tokens = continued.tokens + draft.tokens)
            }
        }
        updateMetrics()
        rawStream = output.toString()
        lastTokens = result.tokens
        lastTps = result.tokensPerSec
        if (result.status < 0) chatError = result.error ?: "Generation failed"
        val message = ChatMessage("assistant", rawStream, cutOff = result.isStopped || result.isContextFull ||
            result.tokens >= settings.maxTokens)
        if (replaceIndex == null) currentMessages.add(message) else currentMessages[replaceIndex] = message
        syncActiveToStore()
    }

    fun benchmark() {
        if (busy || !isLoaded) return
        benchmarking = true
        viewModelScope.launch(Dispatchers.Default) {
            try {
                cancelPrewarmSuspend()
                engineMutex.withLock {
                    engine.reset()
                    cacheSession = null
                    val threads = settings.threads
                    engine.setThreads(threads)
                    val prompt = engine.formatChat(SettingsStore.DEFAULT_SYSTEM_PROMPT,
                        listOf("user" to ThinkBlocks.withNoThink("Explain how rain forms, step by step.")))
                    val result = engine.generate(prompt, LlamaEngine.SamplingParams(maxTokens = 96, seed = 42))
                    check(result.status == 0) { result.error ?: "Benchmark interrupted" }
                    val m = engine.metrics()
                    val row = "%s · %s · %d threads · %d GPU layers\n%.1f p/s · %.1f t/s · %.0f ms TTFT (%d tokens)".format(
                        modelName, activeBackendLabel, threads, modelInfo?.gpuLayers ?: 0, m[1], result.tokensPerSec, m[0], result.tokens)
                    val old = org.json.JSONArray(settings.benchmarkHistory)
                    val rows = org.json.JSONArray().put(row)
                    for (i in 0 until minOf(4, old.length())) rows.put(old.getString(i))
                    settings.benchmarkHistory = rows.toString()
                }
            } catch (e: Exception) { chatError = e.message } finally { benchmarking = false }
        }
    }

    // --------------------------------------------------------- pre-warm logic
    private fun schedulePrewarm(value: String) {
        prewarmJob?.cancel()
        val text = value.trim()
        if (!instantReply || !engine.isLoaded || busy || attachment != null || text.startsWith("/calc ")) return
        if (text.isEmpty() || !ThinkBlocks.isSentenceComplete(text)) return
        prewarmJob = viewModelScope.launch {
            delay(1800) // debounce: user paused typing
            if (input.trim() != text) return@launch
            if (!engine.isLoaded || isGenerating || speculative != null) return@launch
            startPrewarm(text)
        }
    }

    private fun startPrewarm(text: String) {
        val draft = SpeculativeDraft(prompt = text, sampling = samplingParams())
        speculative = draft
        preWarming = true
        prewarmJob = viewModelScope.launch(Dispatchers.Default) {
            engineMutex.withLock {
                // 2 threads keeps the keyboard smooth and the phone cool.
                engine.setThreads(2)
                try {
                    val prompt = formatPrompt(text)
                    draft.formatted = prompt
                    val limit = minOf(384, settings.maxTokens)
                    val res = engine.generate(prompt, draft.sampling.copy(maxTokens = limit)) { piece ->
                        draft.buffer.append(piece)
                        draft.tokens++
                    }
                    draft.result = res
                    draft.tokens = res.tokens
                    draft.isComplete = res.status == 0 && (res.tokens < limit || limit == settings.maxTokens)
                    if (res.status < 0) draft.tokens = 0
                } catch (t: Throwable) {
                    draft.buffer.setLength(0)
                    draft.tokens = 0
                } finally {
                    engine.setThreads(settings.threads)
                }
            }
            preWarming = false
        }
    }

    private fun cancelPrewarm() {
        if (speculative != null || prewarmJob != null) {
            speculative = null
            preWarming = false
            engine.stop()
            prewarmJob?.cancel()
            prewarmJob = null
        }
    }

    private suspend fun cancelPrewarmSuspend() {
        val job = prewarmJob
        speculative = null
        preWarming = false
        engine.stop()
        job?.cancel()
        job?.join()
        prewarmJob = null
    }

    override fun onCleared() {
        runCatching { engine.stop() }
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { engineMutex.withLock { engine.unload() } }
        super.onCleared()
    }

    private class SpeculativeDraft(
        val prompt: String,
        val sampling: LlamaEngine.SamplingParams,
        var formatted: String = "",
        var result: LlamaEngine.GenerateResult? = null,
        val buffer: StringBuilder = StringBuilder(),
        @Volatile var tokens: Int = 0,
        @Volatile var isComplete: Boolean = false,

    )
}
