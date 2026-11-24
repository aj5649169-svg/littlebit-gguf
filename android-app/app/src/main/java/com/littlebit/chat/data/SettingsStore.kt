package com.littlebit.chat.data

import android.content.Context
import android.content.SharedPreferences

/**
 * App settings backed by SharedPreferences. Inference defaults follow the
 * Realme P3 Ultra tuning: 4 threads (Big.LITTLE performance cores), 4096 ctx,
 * repeat penalty 1.18, DRY 0.8.
 */
class SettingsStore(context: Context) {

    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "You are LittleBit Chat, a helpful on-device AI assistant. Be concise and accurate."
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("littlebit_settings", Context.MODE_PRIVATE)

    /** CPU threads 2–8. Default 4 = optimal for Big.LITTLE performance cores. */
    var threads: Int
        get() = prefs.getInt("threads", 4)
        set(v) { prefs.edit().putInt("threads", v.coerceIn(2, 8)).apply() }

    /** Context size 1024–8192. Applies on next model load. */
    var contextSize: Int
        get() = prefs.getInt("context_size", 4096)
        set(v) { prefs.edit().putInt("context_size", v.coerceIn(1024, 8192)).apply() }

    var temperature: Double
        get() = prefs.getFloat("temperature", 0.7f).toDouble()
        set(v) { prefs.edit().putFloat("temperature", v.coerceIn(0.0, 2.0).toFloat()).apply() }

    var repeatPenalty: Double
        get() = prefs.getFloat("repeat_penalty", 1.18f).toDouble()
        set(v) { prefs.edit().putFloat("repeat_penalty", v.coerceIn(1.0, 2.0).toFloat()).apply() }

    var dryMultiplier: Double
        get() = prefs.getFloat("dry_multiplier", 0.8f).toDouble()
        set(v) { prefs.edit().putFloat("dry_multiplier", v.coerceIn(0.0, 2.0).toFloat()).apply() }

    var systemPrompt: String
        get() = prefs.getString("system_prompt", DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
        set(v) { prefs.edit().putString("system_prompt", v).apply() }

    /** Instant Reply (background speculative pre-warm). Default OFF. */
    var instantReply: Boolean
        get() = prefs.getBoolean("instant_reply", false)
        set(v) { prefs.edit().putBoolean("instant_reply", v).apply() }

    /** Fast mode appends /no_think (no reasoning chain). Default ON — thinking
     *  doubles wait time and throttles the phone. */
    var fastMode: Boolean
        get() = prefs.getBoolean("fast_mode", true)
        set(v) { prefs.edit().putBoolean("fast_mode", v).apply() }

    /** Vulkan GPU offload — effective in v0.2 once -DGGML_VULKAN=ON ships.
     *  Plumbed end-to-end now (nGpuLayers), CPU-only until then. */
    var vulkanOffload: Boolean
        get() = prefs.getBoolean("vulkan_offload", false)
        set(v) { prefs.edit().putBoolean("vulkan_offload", v).apply() }
}
