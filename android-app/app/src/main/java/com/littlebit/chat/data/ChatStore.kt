package com.littlebit.chat.data

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class ChatMessage(val role: String, val content: String, val fast: Boolean = false, val cutOff: Boolean = false)
class ChatSession(val id: String, title: String, updatedAt: Long, val messages: MutableList<ChatMessage>, pinned: Boolean = false) {
    var title by mutableStateOf(title)
    var updatedAt by mutableStateOf(updatedAt)
    var pinned by mutableStateOf(pinned)
}

/** JSON chat history with guarded writes and recoverable streaming drafts. */
class ChatStore(private val file: File) {
    val sessions = mutableStateListOf<ChatSession>()
    @Volatile var lastError: String? = null
        private set

    @Synchronized
    fun load() {
        sessions.clear()
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("chats") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val messages = mutableStateListOf<ChatMessage>()
                val rows = o.optJSONArray("messages") ?: JSONArray()
                for (j in 0 until rows.length()) {
                    val m = rows.getJSONObject(j)
                    if (m.getString("role") == "assistant" && m.getString("content").isBlank()) continue
                    messages.add(ChatMessage(m.getString("role"), m.getString("content"), m.optBoolean("fast"), m.optBoolean("cutOff")))
                }
                sessions.add(ChatSession(
                    id = o.getString("id"),
                    title = o.optString("title", "New chat"),
                    updatedAt = o.optLong("updatedAt", 0L),
                    messages = messages, pinned = o.optBoolean("pinned"),
                ))
            }
            sessions.sortByDescending { it.updatedAt }
            lastError = null
        } catch (t: Throwable) {
            lastError = "Could not read chat history: ${t.message ?: t.javaClass.simpleName}"
            Log.e("LittleBitChatStore", lastError!!, t)
        }
    }

    @Synchronized
    fun save(): Boolean {
        val parent = file.parentFile ?: file.absoluteFile.parentFile
        val tmp = File(parent, file.name + ".tmp")
        return try {
            val root = JSONObject()
            val rows = JSONArray()
            for (s in sessions) {
                val o = JSONObject()
                o.put("id", s.id)
                o.put("pinned", s.pinned)
                o.put("title", s.title)
                o.put("updatedAt", s.updatedAt)
                val messages = JSONArray()
                for (m in s.messages) {
                    val mo = JSONObject()
                    mo.put("fast", m.fast)
                    mo.put("cutOff", m.cutOff)
                    mo.put("role", m.role)
                    mo.put("content", m.content)
                    messages.put(mo)
                }
                o.put("messages", messages)
                rows.put(o)
            }
            root.put("chats", rows)
            if (!parent.exists() && !parent.mkdirs()) throw IllegalStateException("Could not create chat history directory.")
            tmp.writeText(root.toString())
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            lastError = null
            true
        } catch (t: Throwable) {
            tmp.delete()
            lastError = "Could not save chat history: ${t.message ?: t.javaClass.simpleName}"
            Log.e("LittleBitChatStore", lastError!!, t)
            false
        }
    }

    @Synchronized
    fun persistDraft(session: ChatSession, visible: List<ChatMessage>, partial: String) {
        session.messages.clear()
        session.messages.addAll(visible)
        if (partial.isNotEmpty()) session.messages.add(ChatMessage("assistant", partial, cutOff = true))
        session.updatedAt = System.currentTimeMillis()
        save()
    }

    @Synchronized
    fun create(): ChatSession {
        val session = ChatSession(UUID.randomUUID().toString(), "New chat", System.currentTimeMillis(), mutableStateListOf())
        sessions.add(0, session)
        save()
        return session
    }

    @Synchronized
    fun delete(id: String) {
        sessions.removeAll { it.id == id }
        save()
    }

    @Synchronized
    fun touch(session: ChatSession) {
        if (session.title == "New chat") {
            val first = session.messages.firstOrNull { it.role == "user" }?.content
            if (!first.isNullOrBlank()) session.title = first.trim().split(Regex("\\s+"), limit = 7).take(6).joinToString(" ").take(80)
        }
        session.updatedAt = System.currentTimeMillis()
        sessions.sortByDescending { it.updatedAt }
        save()
    }
}
