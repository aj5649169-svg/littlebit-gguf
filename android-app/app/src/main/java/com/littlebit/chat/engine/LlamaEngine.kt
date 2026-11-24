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
    )

    data class SamplingParams(
        val temperature: Double = 0.7,
        val topK: Int = 0,               // 0 = disabled
        val repeatPenalty: Double = 1.1,
        val dryMultiplier: Double = 0.0, // 0 = disabled
        val maxTokens: Int = 512,
        val seed: Long = -1,             // <0 = random
    )

    data class GenerateResult(
        val status: Int,
        val tokens: Int,
        val tokensPerSec: Double,
        val output: String,
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
        fun onToken(piece: String)
        fun onComplete(tokens: Int, tokensPerSec: Double)
        fun onError(message: String)
    }

    private var handle: Long = 0

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
        contextSize: Int = 4096,
        threads: Int = 4,
        nGpuLayers: Int = 0,
        onProgress: ProgressCallback? = null,
    ): ModelInfo {
        check(handle == 0L) { "A model is already loaded. Unload it first." }
        val h = LlamaJni.nativeLoadModel(path, contextSize, threads, 512, nGpuLayers, onProgress)
        check(h != 0L) { "nativeLoadModel returned 0" }
        handle = h
        return ModelInfo(
            description = LlamaJni.nativeModelDesc(h),
            sizeBytes = LlamaJni.nativeModelSizeBytes(h),
            fileType = LlamaJni.nativeModelFtype(h),
        )
    }

    fun setThreads(threads: Int) {
        if (handle != 0L) LlamaJni.nativeSetThreads(handle, threads)
    }

    fun unload() {
        val h = handle
        if (h != 0L) {
            LlamaJni.nativeUnload(h)
            handle = 0L
        }
    }

    /** Signal the native loop to stop at the next token. Safe to call anytime. */
    fun stop() {
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
        onToken: (String) -> Unit = {},
    ): GenerateResult = runGeneration(prompt, params, onToken)

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
    ): GenerateResult {
        check(handle != 0L) { "No model loaded" }
        isGenerating = true
        return try {
            val output = StringBuilder()
            var tokens = 0
            var tps = 0.0
            val cb = object : TokenCallback {
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
                    // Surfaced through the result status / partial output.
                }
            }
            val status = if (prompt == null) {
                LlamaJni.nativeContinueGenerate(
                    handle,
                    params.temperature, params.topK, params.repeatPenalty, params.dryMultiplier,
                    params.maxTokens, params.seed, cb,
                )
            } else {
                LlamaJni.nativeGenerate(
                    handle, prompt,
                    params.temperature, params.topK, params.repeatPenalty, params.dryMultiplier,
                    params.maxTokens, params.seed, cb,
                )
            }
            GenerateResult(status, tokens, tps, output.toString())
        } finally {
            isGenerating = false
        }
    }
}
