package com.littlebit.chat.engine

/**
 * Kotlin wrapper around the native LittleBit engine (libllama_jni.so).
 *
 * The engine runs llama.cpp in-process (patched with LittleBit Q1_0 + Arm
 * KleidiAI, GGML_NATIVE=OFF / GGML_OPENMP=OFF) — the same configuration as
 * the Termux binaries built by scripts/build-android.sh.
 *
 * Threading: [loadModel] and [generate] block the calling thread. Call them
 * from a background dispatcher (e.g. Dispatchers.Default); tokens stream back
 * through [onToken] on that same background thread. Only one of
 * [generate]/[continueGenerate] may run at a time — the ViewModel guards
 * engine access with a Mutex.
 */
class LlamaEngine {

    data class ModelInfo(
        val description: String,
        val sizeBytes: Long,
        val fileType: String,
        val gpuLayers: Int = 0,
    )

    data class SamplingParams(
        val temperature: Double = 0.7,
        val topP: Double = 0.90,
        val minP: Double = 0.05,
        val topK: Int = 40,               // 0 = disabled
        val repeatPenalty: Double = 1.1,
        val dryMultiplier: Double = 0.0, // 0 = disabled
        val maxTokens: Int = 1024,
        val seed: Long = -1,             // <0 = random
    )

    data class GenerateResult(
        val status: Int,
        val tokens: Int,
        val tokensPerSec: Double,
        val output: String,
        val error: String? = null,
    ) {
        val isStopped get() = status == STATUS_STOPPED
        val isContextFull get() = status == STATUS_CONTEXT_FULL

        companion object {
            const val STATUS_OK = 0
            const val STATUS_STOPPED = 1
            const val STATUS_CONTEXT_FULL = 2
            const val STATUS_ERROR = -1
        }
    }

    fun interface ProgressCallback {
        fun onProgress(progress: Float)
    }

    interface TokenCallback {
        fun onPrefill(done: Int, total: Int)
        fun onToken(piece: String)
        fun onComplete(tokens: Int, tokensPerSec: Double)
        fun onError(message: String)
    }

    @Volatile private var handle: Long = 0

    val isLoaded: Boolean get() = handle != 0L

    @Volatile
    var isGenerating: Boolean = false
        private set

    init {
        LlamaJni.nativeInit()
    }

    /**
     * Blocks. Loads a GGUF model (any standard quant or LittleBit Q1_0).
     * [nGpuLayers] is plumbed for Vulkan GPU offload (v0.2); keep it 0 on CPU.
     */
    fun loadModel(
        path: String,
        contextSize: Int = 2048,
        threads: Int = 4,
        nGpuLayers: Int = 99,
        batch: Int = 512,
        flash: Boolean = true,
        onProgress: ProgressCallback? = null,
    ): ModelInfo {
        check(handle == 0L) { "A model is already loaded. Unload it first." }
        val h = LlamaJni.nativeLoadModel(path, contextSize, threads, batch, nGpuLayers, flash, onProgress)
        check(h != 0L) { "nativeLoadModel returned 0" }
        handle = h
        return ModelInfo(
            description = LlamaJni.nativeModelDesc(h),
            sizeBytes = LlamaJni.nativeModelSizeBytes(h),
            fileType = LlamaJni.nativeModelFtype(h),
            gpuLayers = LlamaJni.nativeModelGpuLayers(h),
        )
    }

    fun metrics() = LlamaJni.nativeMetrics(handle)
    fun benchmarkContext() = LlamaEngine().also {
        it.handle = LlamaJni.nativeBenchmarkContext(handle)
        check(it.isLoaded) { "Could not allocate a benchmark context" }
    }
    fun reset() = LlamaJni.nativeReset(handle)
    fun promptSize(prompt: String) = LlamaJni.nativePromptSize(handle, prompt)

    @Synchronized fun conserve(enabled: Boolean) = LlamaJni.nativeConserve(handle, enabled)
    fun synchronize() = LlamaJni.nativeSynchronize(handle)

    fun setThreads(threads: Int, batch: Int = threads, cores: IntArray = intArrayOf()) {
        if (handle != 0L) LlamaJni.nativeSetThreads(handle, threads, batch, cores)
    }

    private var draft: LlamaEngine? = null

    fun clearDraft() {
        if (handle != 0L) LlamaJni.nativeAttachDraft(handle, 0)
        draft?.unload()
        draft = null
    }

    fun attachDraft(path: String, threads: Int, progress: ProgressCallback) {
        clearDraft()
        val candidate = LlamaEngine()
        try {
            candidate.loadModel(path, metrics()[3].toInt(), threads, 0, 128, false, progress)
            check(LlamaJni.nativeAttachDraft(handle, candidate.handle)) { "Incompatible vocabulary or attention architecture" }
            draft = candidate
        } finally {
            if (draft == null) candidate.unload()
        }
    }

    @Synchronized fun unload() {
        val h = handle
        if (h != 0L) {
            clearDraft()
            LlamaJni.nativeUnload(h)
            handle = 0L
        }
    }

    /** Signal the native loop to stop at the next token. Safe to call anytime. */
    @Synchronized fun stop() {
        if (handle != 0L) LlamaJni.nativeStop(handle)
    }

    /** Formats the conversation with the model's own GGUF chat template. */
    fun formatChat(systemPrompt: String, messages: List<Pair<String, String>>): String {
        check(handle != 0L) { "No model loaded" }
        val roles = messages.map { it.first }.toTypedArray()
        val contents = messages.map { it.second }.toTypedArray()
        return LlamaJni.nativeFormatChat(handle, systemPrompt, roles, contents)
    }

    /** Blocking generation loop. Call from a background thread. */
    fun generate(
        prompt: String,
        params: SamplingParams = SamplingParams(),
        prefill: (Int, Int) -> Unit = { _, _ -> },
        onToken: (String) -> Unit = {},
    ): GenerateResult = runGeneration(prompt, params, onToken, prefill)

    /**
     * Continue generation from the current KV-cache state (no prompt feeding).
     * Used after flushing a speculative pre-warm draft; falls back to
     * [generate] if the native side reports an error.
     */
    fun continueGenerate(
        params: SamplingParams = SamplingParams(),
        onToken: (String) -> Unit = {},
    ): GenerateResult = runGeneration(null, params, onToken)

    private fun runGeneration(
        prompt: String?,
        params: SamplingParams,
        onToken: (String) -> Unit,
        prefill: (Int, Int) -> Unit = { _, _ -> },
    ): GenerateResult {
        check(handle != 0L) { "No model loaded" }
        isGenerating = true
        return try {
            var error: String? = null
            val output = StringBuilder()
            var tokens = 0
            var tps = 0.0
            val cb = object : TokenCallback {
                override fun onPrefill(done: Int, total: Int) = prefill(done, total)
                override fun onToken(piece: String) {
                    output.append(piece)
                    tokens++
                    onToken(piece)
                }

                override fun onComplete(t: Int, rate: Double) {
                    tokens = t
                    tps = rate
                }

                override fun onError(message: String) {
                    error = message
                }
            }
            val status = if (prompt == null) {
                LlamaJni.nativeContinueGenerate(
                    handle,
                    params.temperature, params.topK, params.topP, params.minP, params.repeatPenalty, params.dryMultiplier,
                    params.maxTokens, params.seed, cb,
                )
            } else {
                LlamaJni.nativeGenerate(
                    handle, prompt,
                    params.temperature, params.topK, params.topP, params.minP, params.repeatPenalty, params.dryMultiplier,
                    params.maxTokens, params.seed, cb,
                )
            }
            GenerateResult(status, tokens, tps, output.toString(), error)
        } finally {
            isGenerating = false
        }
    }
}
