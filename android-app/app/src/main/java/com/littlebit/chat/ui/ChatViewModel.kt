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

    val engine = LlamaEngine()
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
    private var activeModelPath: String? = null

    val isLoaded: Boolean get() = engine.isLoaded

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
    fun createChat() {
        cancelPrewarm()
        syncActiveToStore()
        activate(store.create())
    }

    fun switchChat(id: String) {
        if (currentSession?.id == id) return
        cancelPrewarm()
        syncActiveToStore()
        store.sessions.firstOrNull { it.id == id }?.let(::activate)
    }

    fun deleteChat(id: String) {
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
        s.messages.clear()
        s.messages.addAll(currentMessages)
        store.touch(s)
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
        if (loadProgress >= 0f) return
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
        if (loadProgress >= 0f) return
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
        if (loadProgress >= 0f) return
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
            }
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
                // CPU-only model loading: GPU offload remains disabled.
                engine.loadModel(
                    path = file.absolutePath,
                    contextSize = settings.contextSize,
                    threads = settings.threads,
                    nGpuLayers = 0,
                    onProgress = { progress ->
                        loadProgress = 0.35f + (progress.coerceIn(0f, 1f) * 0.65f)
                    },
                )
            }
            modelInfo = info
            modelName = file.name
            activeModelPath = file.absolutePath
            loadProgress = 1f
            loadStatus = null
            loadError = null
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
        cancelPrewarm()
        viewModelScope.launch {
            engineMutex.withLock { engine.unload() }
            modelInfo = null
            modelName = null
            activeModelPath = null
            loadStatus = "Model unloaded — RAM freed."
        }
    }

    /** Live-apply the thread count while a model is loaded. */
    fun onThreadsChanged(n: Int) {
        settings.threads = n
        if (engine.isLoaded && !isGenerating) engine.setThreads(settings.threads)
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

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || isGenerating || !engine.isLoaded) return
        input = ""
        viewModelScope.launch(Dispatchers.Default) {
            val draft = speculative?.takeIf { it.prompt == text }
            // Format BEFORE the visible user message is appended, so the
            // history contains exactly one copy of the current user turn.
            val prompt = formatPrompt(text)
            cancelPrewarmSuspend()
            appendUserMessage(text)
            if (draft != null && draft.tokens > 0 && draft.buffer.isNotEmpty()) {
                flushAndContinue(draft, prompt)
            } else {
                runTurn(prompt, seedTokens = 0, seedText = "")
            }
        }
    }

    /** Send a fixed string (suggestion chips) as the user message. */
    fun sendText(text: String) {
        if (isGenerating || !engine.isLoaded) return
        input = text
        send()
    }

    fun stopGeneration() {
        engine.stop()
    }

    private fun appendUserMessage(text: String) {
        currentMessages.add(ChatMessage("user", text))
        syncActiveToStore()
    }

    /**
     * Build the fully-templated prompt for [userText]. Previous assistant
     * turns are passed through [ThinkBlocks.strip] so `<think>` blocks can
     * never reach the chat template (multi-turn crash fix), and `/no_think`
     * is appended in Fast mode without appearing in the visible bubble.
     */
    private fun formatPrompt(userText: String): String {
        val history = currentMessages.map {
            if (it.role == "assistant") it.role to ThinkBlocks.strip(it.content) else it.role to it.content
        } + ("user" to if (fastMode) ThinkBlocks.withNoThink(userText) else userText)
        return engine.formatChat(settings.systemPrompt, history)
    }

    private fun samplingParams(maxTokens: Int = 512) = LlamaEngine.SamplingParams(
        temperature = settings.temperature,
        repeatPenalty = settings.repeatPenalty,
        dryMultiplier = settings.dryMultiplier,
        maxTokens = maxTokens,
        seed = -1,
    )

    private suspend fun runTurn(prompt: String, seedTokens: Int, seedText: String) {
        isGenerating = true
        streamActive = true
        rawStream = seedText
        lastTps = 0.0
        lastTokens = seedTokens
        val t0 = System.nanoTime()
        try {
            val result = engineMutex.withLock {
                engine.setThreads(settings.threads)
                engine.generate(prompt, samplingParams()) { piece ->
                    rawStream += piece
                    lastTokens++
                }
            }
            if (result.status == LlamaEngine.GenerateResult.STATUS_ERROR && seedTokens > 0) {
                // Continuation failed — retry as a clean full turn.
                rawStream = ""
                lastTokens = 0
                val retry = engineMutex.withLock {
                    engine.generate(prompt, samplingParams()) { piece ->
                        rawStream += piece
                        lastTokens++
                    }
                }
                finalizeTurn(retry.tokens, t0)
            } else {
                finalizeTurn(result.tokens + seedTokens, t0)
            }
        } catch (t: Throwable) {
            rawStream += "\n\nError: ${t.message ?: t.javaClass.simpleName}"
            finalizeTurn(lastTokens, t0)
        }
    }

    /** Flush a matched speculative draft into the bubble, then keep sampling. */
    private suspend fun flushAndContinue(draft: SpeculativeDraft, prompt: String) {
        isGenerating = true
        streamActive = true
        rawStream = draft.buffer.toString()
        lastTokens = draft.tokens
        lastTps = 0.0
        if (draft.isComplete) {
            // Draft finished while the user was reaching for Send — done.
            val warmSecs = (System.nanoTime() - draft.startedAt) / 1_000_000_000.0
            val warmTps = if (warmSecs > 0.0) draft.tokens / warmSecs else 0.0
            finalizeTurn(draft.tokens, draft.startedAt, tpsOverride = warmTps)
            return
        }
        val t0 = System.nanoTime()
        try {
            val result = engineMutex.withLock {
                engine.setThreads(settings.threads)
                engine.continueGenerate(samplingParams()) { piece ->
                    rawStream += piece
                    lastTokens++
                }
            }
            if (result.status == LlamaEngine.GenerateResult.STATUS_ERROR) {
                // KV state unusable — fall back to a full clean generation.
                rawStream = ""
                lastTokens = 0
                val full = engineMutex.withLock {
                    engine.generate(prompt, samplingParams()) { piece ->
                        rawStream += piece
                        lastTokens++
                    }
                }
                finalizeTurn(full.tokens, t0)
            } else {
                finalizeTurn(lastTokens, t0)
            }
        } catch (t: Throwable) {
            rawStream += "\n\nError: ${t.message ?: t.javaClass.simpleName}"
            finalizeTurn(lastTokens, t0)
        }
    }

    private fun finalizeTurn(tokens: Int, t0: Long, tpsOverride: Double? = null) {
        if (tpsOverride != null) {
            lastTps = tpsOverride
        } else {
            val secs = (System.nanoTime() - t0) / 1_000_000_000.0
            lastTps = if (secs > 0.0) tokens / secs else 0.0
        }
        lastTokens = tokens
        currentMessages.add(ChatMessage("assistant", rawStream))
        rawStream = ""
        streamActive = false
        isGenerating = false
        syncActiveToStore()
    }

    // --------------------------------------------------------- pre-warm logic
    private fun schedulePrewarm(value: String) {
        prewarmJob?.cancel()
        val text = value.trim()
        if (!instantReply || !engine.isLoaded || isGenerating) return
        if (text.isEmpty() || !ThinkBlocks.isSentenceComplete(text)) return
        prewarmJob = viewModelScope.launch {
            delay(1800) // debounce: user paused typing
            if (input.trim() != text) return@launch
            if (!engine.isLoaded || isGenerating || speculative != null) return@launch
            startPrewarm(text)
        }
    }

    private fun startPrewarm(text: String) {
        val prompt = runCatching { formatPrompt(text) }.getOrNull() ?: return
        val draft = SpeculativeDraft(prompt = text)
        speculative = draft
        preWarming = true
        prewarmJob = viewModelScope.launch(Dispatchers.Default) {
            engineMutex.withLock {
                // 2 threads keeps the keyboard smooth and the phone cool.
                engine.setThreads(2)
                try {
                    val res = engine.generate(prompt, samplingParams(maxTokens = 384)) { piece ->
                        draft.buffer.append(piece)
                        draft.tokens++
                    }
                    draft.isComplete = res.status == LlamaEngine.GenerateResult.STATUS_OK
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
        job?.join()
        prewarmJob = null
    }

    override fun onCleared() {
        runCatching { engine.stop() }
        runCatching { engine.unload() }
        super.onCleared()
    }

    private class SpeculativeDraft(
        val prompt: String,
        val buffer: StringBuilder = StringBuilder(),
        @Volatile var tokens: Int = 0,
        @Volatile var isComplete: Boolean = false,
        val startedAt: Long = System.nanoTime(),
    )
}
