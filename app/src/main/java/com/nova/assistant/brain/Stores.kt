package com.nova.assistant.brain

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Device-local plain conversation history, capped at the most recent 100 messages. */
class ChatStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(NOVA_PREFERENCES, Context.MODE_PRIVATE)

    fun load(): List<ChatMessage> = synchronized(preferences) { read() }

    fun append(role: ChatRole, text: String): ChatMessage = synchronized(preferences) {
        val message = ChatMessage(UUID.randomUUID().toString(), role, text.take(MAX_MESSAGE_LENGTH), System.currentTimeMillis())
        val messages = (read() + message).takeLast(MAX_MESSAGES)
        val array = JSONArray()
        messages.forEach {
            array.put(JSONObject().put("id", it.id).put("role", it.role.name)
                .put("text", it.text).put("timestamp", it.timestamp))
        }
        preferences.edit().putString(CHAT_KEY, array.toString()).apply()
        message
    }

    fun clear() = synchronized(preferences) { preferences.edit().remove(CHAT_KEY).apply() }

    private fun read(): List<ChatMessage> = runCatching {
        val array = JSONArray(preferences.getString(CHAT_KEY, "[]"))
        buildList {
            for (index in maxOf(0, array.length() - MAX_MESSAGES) until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val role = runCatching { ChatRole.valueOf(item.optString("role")) }.getOrNull() ?: continue
                val id = item.optString("id")
                val text = item.optString("text").take(MAX_MESSAGE_LENGTH)
                if (id.isNotBlank() && text.isNotBlank()) add(ChatMessage(id, role, text, item.optLong("timestamp")))
            }
        }
    }.getOrDefault(emptyList())

    companion object {
        const val MAX_MESSAGES = 100
        private const val MAX_MESSAGE_LENGTH = 8_000
        private const val CHAT_KEY = "chat_json"
    }
}

internal class PreferencesNotes(context: Context) : NoteRepository {
    private val preferences = context.applicationContext.getSharedPreferences(NOVA_PREFERENCES, Context.MODE_PRIVATE)

    override fun load(): List<Note> = synchronized(preferences) { read() }

    override fun add(text: String, timestamp: Long): Note = synchronized(preferences) {
        val note = Note(UUID.randomUUID().toString(), text, timestamp)
        val array = JSONArray()
        (read() + note).takeLast(MAX_NOTES).forEach {
            array.put(JSONObject().put("id", it.id).put("text", it.text).put("timestamp", it.timestamp))
        }
        preferences.edit().putString(NOTES_KEY, array.toString()).apply()
        note
    }

    override fun clear() = synchronized(preferences) { preferences.edit().remove(NOTES_KEY).apply() }

    private fun read(): List<Note> = runCatching {
        val array = JSONArray(preferences.getString(NOTES_KEY, "[]"))
        buildList {
            for (index in maxOf(0, array.length() - MAX_NOTES) until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val text = item.optString("text")
                if (id.isNotBlank() && text.isNotBlank()) add(Note(id, text, item.optLong("timestamp")))
            }
        }
    }.getOrDefault(emptyList())

    companion object {
        const val MAX_NOTES = 200
        private const val NOTES_KEY = "notes_json"
    }
}
