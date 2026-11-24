package com.littlebit.chat.data

import androidx.compose.runtime.mutableStateListOf
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class ChatMessage(val role: String, val content: String)

data class ChatSession(
    val id: String,
    var title: String,
    var updatedAt: Long,
    val messages: MutableList<ChatMessage>,
)

/**
 * Chat history persisted as JSON in internal storage (filesDir/chats/chats.json).
 * Survives app restarts; supports switching, renaming (derived from the first
 * user message) and deletion.
 */
class ChatStore(private val file: File) {

    val sessions = mutableStateListOf<ChatSession>()

    @Synchronized
    fun load() {
        sessions.clear()
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("chats") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val msgs = mutableListOf<ChatMessage>()
                val marr = o.optJSONArray("messages") ?: JSONArray()
                for (j in 0 until marr.length()) {
                    val m = marr.getJSONObject(j)
                    msgs.add(ChatMessage(m.getString("role"), m.getString("content")))
                }
                sessions.add(
                    ChatSession(
                        id = o.getString("id"),
                        title = o.optString("title", "New chat"),
                        updatedAt = o.optLong("updatedAt", 0L),
                        messages = msgs,
                    ),
                )
            }
            // Newest first.
            sessions.sortByDescending { it.updatedAt }
        }
    }

    @Synchronized
    fun save() {
        val root = JSONObject()
        val arr = JSONArray()
        for (s in sessions) {
            val o = JSONObject()
            o.put("id", s.id)
            o.put("title", s.title)
            o.put("updatedAt", s.updatedAt)
            val marr = JSONArray()
            for (m in s.messages) {
                val mo = JSONObject()
                mo.put("role", m.role)
                mo.put("content", m.content)
                marr.put(mo)
            }
            o.put("messages", marr)
            arr.put(o)
        }
        root.put("chats", arr)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(root.toString())
            tmp.delete()
        }
    }

    @Synchronized
    fun create(): ChatSession {
        val session = ChatSession(
            id = UUID.randomUUID().toString(),
            title = "New chat",
            updatedAt = System.currentTimeMillis(),
            messages = mutableListOf(),
        )
        sessions.add(0, session)
        save()
        return session
    }

    @Synchronized
    fun delete(id: String) {
        sessions.removeAll { it.id == id }
        save()
    }

    /** Refresh title/updatedAt from content and persist. Callers sync messages first. */
    @Synchronized
    fun touch(session: ChatSession) {
        if (session.title == "New chat") {
            val firstUser = session.messages.firstOrNull { it.role == "user" }?.content
            if (!firstUser.isNullOrBlank()) {
                session.title = firstUser.replace(Regex("\\s+"), " ").take(32).trim()
            }
        }
        session.updatedAt = System.currentTimeMillis()
        sessions.sortByDescending { it.updatedAt }
        save()
    }
}
