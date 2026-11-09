package com.nova.assistant.brain

const val NOVA_PREFERENCES = "nova_v2"

data class Reply(val text: String, val action: Action? = null)

/** The UI executes these only in response to the user's command. */
sealed class Action {
    data class OpenUrl(val url: String) : Action()
    data class OpenSettings(val screen: SettingsScreen) : Action()
    data class OpenApp(val packageName: String, val displayName: String, val fallbackUrl: String?) : Action()
    data class ConfirmDeleteNotes(val count: Int) : Action()
}

enum class SettingsScreen { GENERAL, WIFI, BLUETOOTH }
enum class ChatRole { USER, ASSISTANT }

data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val text: String,
    val timestamp: Long
)

data class Note(val id: String, val text: String, val timestamp: Long)

internal interface NoteRepository {
    fun load(): List<Note>
    fun add(text: String, timestamp: Long): Note
    fun clear()
}

internal object TurkishText {
    fun clean(text: String) = text.trim().replace(Regex("\\s+"), " ")

    /** ASCII variants are accepted as well as proper Turkish keyboard spelling. */
    fun fold(text: String): String = clean(text).lowercase(java.util.Locale.forLanguageTag("tr-TR"))
        .replace('ı', 'i').replace('ğ', 'g').replace('ü', 'u')
        .replace('ş', 's').replace('ö', 'o').replace('ç', 'c')
}
