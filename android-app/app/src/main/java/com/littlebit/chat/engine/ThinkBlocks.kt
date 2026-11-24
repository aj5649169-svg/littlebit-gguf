package com.littlebit.chat.engine

/**
 * Helpers for Qwen3 `<think>` reasoning blocks and the Fast/No-Think mode.
 *
 * Real-device findings (Realme P3 Ultra, Qwen3-8B-Q4_K_M):
 *  - `<think>` chains double wait time and cause thermal throttling.
 *  - llama.cpp's `--jinja` template can crash Turn 2 with std::out_of_range
 *    when prior assistant turns contain `<think>` blocks.
 *
 * Defence in depth:
 *  1. [withNoThink] appends `/no_think` to the user prompt in Fast mode.
 *  2. [strip] removes reasoning blocks from stored assistant turns before
 *     they are fed back into the chat template (Kotlin side).
 *  3. llama_jni.cpp falls back to plain ChatML if the GGUF template throws.
 */
object ThinkBlocks {

    private val THINK_BLOCK = Regex("(?s)<think>.*?</think>")
    private val THINK_OPEN_TO_END = Regex("(?s)<think>.*")

    /**
     * Remove reasoning blocks from a stored assistant message before it is
     * formatted into the next turn's prompt.
     */
    fun strip(text: String): String {
        val withoutClosed = THINK_BLOCK.replace(text, "")
        return THINK_OPEN_TO_END.replace(withoutClosed, "").trim()
    }

    class Split(val think: String?, val answer: String)

    /**
     * Split a (possibly still-streaming) raw model output into the reasoning
     * part and the visible answer. When the closing tag has not arrived yet,
     * everything after `<think>` is treated as reasoning.
     */
    fun split(raw: String): Split {
        val open = raw.indexOf("<think>")
        if (open < 0) return Split(null, raw)
        val answer = raw.substring(0, open)
        val contentStart = open + "<think>".length
        val close = raw.indexOf("</think>", contentStart)
        return if (close >= 0) {
            val think = raw.substring(contentStart, close)
            Split(
                think.ifBlank { null },
                answer + raw.substring(close + "</think>".length),
            )
        } else {
            Split(raw.substring(contentStart).ifBlank { null }, answer)
        }
    }

    /** Qwen3 Fast mode: suppress chain-of-thought by appending `/no_think`. */
    fun withNoThink(prompt: String): String = "$prompt /no_think"

    /**
     * Sentence-completeness guard for the speculative pre-warm: only a draft
     * ending in sentence punctuation (or a short standalone greeting) may
     * trigger background generation — never a mid-clause fragment.
     */
    fun isSentenceComplete(draft: String): Boolean {
        val t = draft.trim()
        if (t.isEmpty()) return false
        if (t.last() in ".?!") return true
        return t.lowercase() in setOf("hi", "hello", "hey", "yo")
    }
}
