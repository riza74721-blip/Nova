package com.nova.assistant.brain

import android.content.Context
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Local Turkish command handling. No model API or cloud conversation is implied. */
class AssistantBrain internal constructor(
    private val noteStore: NoteRepository,
    private val now: () -> Date = { Date() },
    private val timeZone: () -> TimeZone = { TimeZone.getDefault() },
    private val nameProvider: () -> String = { "Nova" }
) {
    constructor(context: Context) : this(
        PreferencesNotes(context),
        nameProvider = {
            context.applicationContext.getSharedPreferences(NOVA_PREFERENCES, Context.MODE_PRIVATE)
                .getString("assistant_name", "Nova").orEmpty().trim().ifBlank { "Nova" }
        }
    )

    private var awaitingNote = false
    private var askedHowAreYou = false
    private val turkish = Locale.forLanguageTag("tr-TR")

    fun respond(text: String): Reply {
        var original = TurkishText.clean(text)
        if (original.isBlank()) return Reply("Seni duyamadım. Tekrar konuşabilir ya da mesajını yazabilirsin.")
        if (original.length > 4_000) return Reply("Bu mesaj çok uzun. Lütfen 4.000 karakterden kısa bir mesaj yaz.")
        var command = TurkishText.fold(original).trimEnd('?', '!', '.', ' ')
        val name = nameProvider().trim().take(40).ifBlank { "Nova" }
        val foldedName = TurkishText.fold(name)
        val addressedCommand = command.removePrefix("$foldedName ").removeSuffix(" $foldedName")
        val followingHowAreYou = askedHowAreYou
        askedHowAreYou = false
        if (addressedCommand in setOf("iptal", "vazgec", "bosver", "vazgectim")) {
            val wasWaiting = awaitingNote
            awaitingNote = false
            askedHowAreYou = false
            return Reply(if (wasWaiting) "Not alma işlemini iptal ettim." else "Tamam. Başka bir konuda yardımcı olabilirim.")
        }
        if (awaitingNote) return saveNote(original)

        // Addressing Nova is optional; the visible assistant name can be changed in settings.
        if (command.startsWith("$foldedName ")) {
            command = command.removePrefix("$foldedName ")
            original = original.substring(foldedName.length + 1)
        }
        if (command.endsWith(" $foldedName")) {
            command = command.removeSuffix(" $foldedName")
            original = original.trimEnd('?', '!', '.', ' ').dropLast(foldedName.length + 1)
        }

        if (command in setOf("not al", "bir not al", "not ekle", "not kaydet")) {
            awaitingNote = true
            return Reply("Ne not almamı istersin? Sonraki mesajını not olarak kaydedeceğim. Vazgeçmek için iptal yazabilirsin.")
        }
        val notePrefix = Regex("^(?:bir )?not (?:al|ekle|kaydet)(?:\\s*[:;,]\\s*|\\s+)").find(TurkishText.fold(original))
        if (notePrefix != null) return saveNote(original.substring(notePrefix.value.length).trim())

        if (command in setOf("notlar", "notlarim", "notlarimi goster", "notlari goster", "notlarimi oku", "notlari oku")) {
            val notes = noteStore.load()
            if (notes.isEmpty()) return Reply("Henüz kayıtlı notun yok. Not al diyerek ilk notunu ekleyebilirsin.")
            val visible = notes.takeLast(10).reversed()
            val summary = visible.mapIndexed { index, note -> "${index + 1}. ${note.text}" }.joinToString("\n")
            val heading = if (notes.size > 10) "${notes.size} notundan son 10 tanesi:" else "Notların:"
            return Reply("$heading\n$summary")
        }
        if (command in setOf("notlari sil", "notlarimi sil", "tum notlari sil", "butun notlari sil")) {
            val count = noteStore.load().size
            return if (count == 0) Reply("Silinecek kayıtlı notun yok.")
                else Reply("Notları silmek için onayını bekliyorum.", Action.ConfirmDeleteNotes(count))
        }

        arithmetic(command)?.let { return it }

        if (command in setOf("saat", "saat kac", "saati soyle", "su an saat kac")) return Reply("Saat ${format("HH:mm")}")
        if (command in setOf("tarih", "bugun ayin kaci", "bugun gunlerden ne", "bugunun tarihi", "bugun hangi gun")) {
            return Reply("Bugün ${format("d MMMM yyyy EEEE")}")
        }

        when (command) {
            "ayarlari ac", "ayarlar", "telefon ayarlarini ac", "cihaz ayarlarini ac" ->
                return Reply("Telefon ayarlarını açıyorum.", Action.OpenSettings(SettingsScreen.GENERAL))
            "wifi", "wi-fi", "wifi ayarlarini ac", "wi-fi ayarlarini ac", "kablosuz ag ayarlarini ac", "wifi ac", "wi-fi ac" ->
                return Reply("Wi-Fi ayarlarını açıyorum. Bağlantıyı bu ekrandan değiştirebilirsin.", Action.OpenSettings(SettingsScreen.WIFI))
            "bluetooth", "bluetooth ayarlarini ac", "bluetooth ac" ->
                return Reply("Bluetooth ayarlarını açıyorum. Bağlantıyı bu ekrandan değiştirebilirsin.", Action.OpenSettings(SettingsScreen.BLUETOOTH))
            "youtube", "youtube ac", "youtube'u ac", "youtube uygulamasini ac" ->
                return Reply("YouTube'u açıyorum.", Action.OpenApp("com.google.android.youtube", "YouTube", "https://www.youtube.com/"))
            "whatsapp", "whatsapp ac", "whatsapp'i ac", "whatsapp uygulamasini ac" ->
                return Reply("WhatsApp'ı açıyorum.", Action.OpenApp("com.whatsapp", "WhatsApp", "https://www.whatsapp.com/"))
            "spotify", "spotify ac", "spotify'i ac", "spotify uygulamasini ac" ->
                return Reply("Spotify'ı açıyorum.", Action.OpenApp("com.spotify.music", "Spotify", "https://open.spotify.com/"))
            "tarayici", "tarayiciyi ac", "internet tarayicisini ac", "chrome ac", "interneti ac" ->
                return Reply("İnternet tarayıcısını açıyorum.", Action.OpenUrl("https://www.google.com/"))
        }

        if (command in setOf("internette ara", "webde ara", "haritada ara", "haritalarda ara")) {
            return Reply("Neyi arayayım? Örneğin internette Türk kahvesi ara veya haritada eczane ara diyebilirsin.")
        }
        val search = Regex("^(internette|webde|googleda|google'da|haritada|haritalarda) (.+)$").find(TurkishText.fold(original))
        if (search != null) {
            val mapSearch = search.groupValues[1].startsWith("harita")
            var query = original.substring(search.groups[2]!!.range.first).trim()
            val ending = Regex("\\s+(ara|arat|goster)[?!.]?$").find(TurkishText.fold(query))
            if (ending != null) query = query.dropLast(ending.value.length).trim()
            if (query.isBlank()) return Reply("Aramak istediğin yeri veya konuyu da söyleyebilir misin?")
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = if (mapSearch) "https://www.google.com/maps/search/?api=1&query=$encoded"
                else "https://www.google.com/search?q=$encoded"
            return Reply(if (mapSearch) "Haritada arıyorum: $query" else "İnternette arıyorum: $query", Action.OpenUrl(url))
        }

        if (command in setOf("yardim", "yardim et", "ne yapabilirsin", "neler yapabilirsin", "komutlar", "komutlari goster")) {
            return Reply("Şunları yapabilirim:\n• Saat ve tarihi söylemek\n• Hesapla 12 çarpı 7 gibi işlemler\n• Not al, notlarımı göster\n• İnternette bir konu ara\n• Haritada bir yer ara\n• YouTube, WhatsApp, Spotify veya tarayıcıyı aç\n• Wi-Fi, Bluetooth ve telefon ayarlarını aç\nBunlar yerel komutlar; genel sorulara yapay zekâ yanıtı üretmiyorum.")
        }
        if (command in setOf("adın ne", "adin ne", "ismin ne", "kimsin", "sen kimsin", "kendini tanit")) {
            return Reply("Ben $name. Notlarını kaydedebilir, hesap yapabilir ve arama ya da uygulama açma komutlarında yardımcı olabilirim.")
        }
        if (command in setOf("nasilsin", "ne haber", "naber")) {
            askedHowAreYou = true
            return Reply("Buradayım, yardımcı olmaya hazırım. Sen nasılsın?")
        }
        if (followingHowAreYou && command in setOf("iyiyim", "ben de iyiyim", "iyiyim tesekkurler", "fena degilim", "kotuyum", "iyi degilim")) {
            askedHowAreYou = false
            return Reply(if (command in setOf("kotuyum", "iyi degilim")) "Üzüldüm. İstersen aklındakini bir nota kaydedebiliriz."
                else "Buna sevindim. Bugün hangi işini kolaylaştıralım?")
        }
        if (command in setOf("tesekkurler", "tesekkur ederim", "sag ol", "sagol", "cok tesekkurler")) return Reply("Rica ederim. Başka bir işinde de yardımcı olabilirim.")
        if (command in setOf("merhaba", "selam", "selamlar", "gunaydin", "iyi aksamlar", "iyi gunler")) {
            return Reply("Merhaba! Ben $name. Not alabilir, hesap yapabilir veya istediğin bir konuyu internette arayabilirim. Ne yapalım?")
        }
        return Reply("Bunu henüz yapamıyorum. Not alabilir, hesap yapabilir, saat ve tarihi söyleyebilir, arama yapabilir veya uygulama açabilirim. Örnekler için yardım yazabilirsin.")
    }

    fun notes(): List<Note> = noteStore.load()

    /** Call only after the user accepts the UI confirmation requested by ConfirmDeleteNotes. */
    fun deleteAllNotes(): Reply {
        awaitingNote = false
        noteStore.clear()
        return Reply("Kayıtlı notlarını sildim.")
    }

    private fun saveNote(text: String): Reply {
        if (text.isBlank()) {
            awaitingNote = true
            return Reply("Notuna ne yazmamı istersin?")
        }
        if (text.length > 2_000) return Reply("Bir not en fazla 2.000 karakter olabilir. Lütfen notunu kısalt.")
        if (noteStore.load().size >= PreferencesNotes.MAX_NOTES) {
            awaitingNote = false
            return Reply("200 not sınırına ulaştın. Yeni not eklemeden önce notlarını gözden geçirip temizleyebilirsin.")
        }
        awaitingNote = false
        noteStore.add(text, now().time)
        return Reply("Notunu kaydettim: $text")
    }

    private fun format(pattern: String) = SimpleDateFormat(pattern, turkish).apply { timeZone = this@AssistantBrain.timeZone() }.format(now())

    private fun arithmetic(command: String): Reply? {
        val explicit = command.startsWith("hesapla ") || command.startsWith("hesapla:") || command.startsWith("hesapla(")
        var expression = command.removePrefix("hesapla").trimStart(' ', ':')
            .removeSuffix(" kac eder").removeSuffix(" sonucu ne").trim()
        val operations = mapOf("arti" to "+", "eksi" to "-", "carpi" to "*", "kere" to "*", "bolu" to "/")
        operations.forEach { (word, operator) -> expression = expression.replace(Regex("\\b$word\\b"), operator) }
        expression = expression.replace('−', '-')
        val numberWordPattern = Regex("\\b(?:$NUMBER_WORDS)(?:\\s+(?:$NUMBER_WORDS))*\\b")
        expression = numberWordPattern.replace(expression) { match -> numberWords(match.value).toString() }
            .replace(Regex("\\s*virgul\\s*"), ",")
        val looksLikeMath = expression.any { it.isDigit() } && expression.any { it in "+-*/×÷" } &&
            expression.all { it.isDigit() || it.isWhitespace() || it in ".,()+-*/×÷" }
        if (!explicit && !looksLikeMath) return null
        return when (val result = Arithmetic.evaluate(expression)) {
            is Arithmetic.Result.Success -> Reply("Sonuç: ${result.value}")
            is Arithmetic.Result.Failure -> Reply(result.message)
        }
    }

    private fun numberWords(text: String): Long {
        var result = 0L
        var group = 0L
        text.split(' ').forEach { word ->
            when (word) {
                "yuz" -> group = maxOf(1L, group) * 100
                "bin" -> { result += maxOf(1L, group) * 1_000; group = 0 }
                else -> group += NUMBER_VALUES[word] ?: 0
            }
        }
        return result + group
    }

    companion object {
        private val NUMBER_VALUES = mapOf("sifir" to 0L, "bir" to 1L, "iki" to 2L, "uc" to 3L, "dort" to 4L, "bes" to 5L,
            "alti" to 6L, "yedi" to 7L, "sekiz" to 8L, "dokuz" to 9L, "on" to 10L, "yirmi" to 20L, "otuz" to 30L,
            "kirk" to 40L, "elli" to 50L, "altmis" to 60L, "yetmis" to 70L, "seksen" to 80L, "doksan" to 90L)
        private val NUMBER_WORDS = (NUMBER_VALUES.keys + listOf("yuz", "bin")).joinToString("|")
    }
}
