package com.littlebit.chat.engine

/**
 * Thin JNI bindings for libllama_jni.so.
 *
 * See cpp/llama_jni.cpp for the native implementation and the mapping of
 * each signature. Loaded once via [System.loadLibrary] in [LlamaEngine].
 */
internal object LlamaJni {
    init {
        System.loadLibrary("llama_jni")
    }

    external fun nativeInit()

    external fun nativeLoadModel(
        path: String,
        nCtx: Int,
        nThreads: Int,
        nBatch: Int,
        nGpuLayers: Int,
        flash: Boolean,
        progress: LlamaEngine.ProgressCallback?,
    ): Long

    external fun nativeModelDesc(handle: Long): String
    external fun nativeModelGpuLayers(handle: Long): Int
    external fun nativeModelSizeBytes(handle: Long): Long
    external fun nativeModelFtype(handle: Long): String

    external fun nativeFormatChat(
        handle: Long,
        system: String,
        roles: Array<String>,
        contents: Array<String>,
    ): String

    /** Returns 0 finished, 1 stopped, 2 context full, -1 error. */
    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        temperature: Double,
        topK: Int,
        topP: Double,
        minP: Double,
        repeatPenalty: Double,
        dryMultiplier: Double,
        maxTokens: Int,
        seed: Long,
        callback: LlamaEngine.TokenCallback,
    ): Int

    /**
     * Continue sampling from the current KV-cache state without re-processing
     * a prompt. Used to seamlessly finish a speculative pre-warm draft.
     * Same return codes as [nativeGenerate].
     */
    external fun nativeContinueGenerate(
        handle: Long,
        temperature: Double,
        topK: Int,
        topP: Double,
        minP: Double,
        repeatPenalty: Double,
        dryMultiplier: Double,
        maxTokens: Int,
        seed: Long,
        callback: LlamaEngine.TokenCallback,
    ): Int

    external fun nativeMetrics(handle: Long): DoubleArray
    external fun nativeAttachDraft(handle: Long, draft: Long): Boolean
    external fun nativeBenchmarkContext(handle: Long): Long
    external fun nativeReset(handle: Long)
    external fun nativePromptSize(handle: Long, prompt: String): Int
    external fun nativeStop(handle: Long)
    external fun nativeConserve(handle: Long, enabled: Boolean)
    external fun nativeSynchronize(handle: Long)
    external fun nativeSetThreads(handle: Long, threads: Int, batch: Int, cores: IntArray)
    external fun nativeUnload(handle: Long)
}
