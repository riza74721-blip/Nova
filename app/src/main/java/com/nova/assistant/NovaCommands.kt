package com.nova.assistant

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Commands supported by the button-operated first version of Nova. */
internal object NovaCommands {
    private val turkish = Locale.forLanguageTag("tr-TR")

    fun reply(
        speech: String,
        now: Date = Date(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val command = speech.trim().lowercase(turkish).replace(Regex("\\s+"), " ")
        fun format(pattern: String) = SimpleDateFormat(pattern, turkish).apply {
            this.timeZone = timeZone
        }.format(now)

        return when {
            command.isBlank() -> "Konuşma algılanamadı. Tekrar deneyebilirsin."
            command.contains("merhaba") || command.contains("selam") -> "Merhaba! Ben Nova."
            command.contains("saat kaç") -> "Saat ${format("HH:mm")}"
            command.contains("tarih") || command.contains("bugün günlerden") -> "Bugün ${format("d MMMM yyyy EEEE")}"
            command.contains("adın ne") -> "Benim adım Nova."
            else -> "Seni duydum: ${speech.trim()}. Bu komut henüz ilk sürümde desteklenmiyor."
        }
    }
}
