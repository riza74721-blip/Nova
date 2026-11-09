package com.nova.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.util.Date
import java.util.TimeZone

class AssistantBrainTest {
    private val repository = MemoryNotes()
    private val brain = AssistantBrain(repository, { Date(0) }, { TimeZone.getTimeZone("GMT+03:00") })

    @Test fun noteCaptureIsMultiTurnAndPersistsInRepository() {
        assertTrue(brain.respond("not al").text.startsWith("Ne not almamı istersin?"))
        assertEquals("Notunu kaydettim: Yarın kitap al", brain.respond("Yarın kitap al").text)
        assertEquals(listOf("Yarın kitap al"), repository.load().map { it.text })
        val recreated = AssistantBrain(repository)
        assertTrue(recreated.respond("notlarımı göster").text.contains("Yarın kitap al"))
    }

    @Test fun directNotePreservesCaseAndCanBeAddressedToAssistant() {
        brain.respond("Nova not al: İstanbul toplantısı saat 14.00")
        assertEquals("İstanbul toplantısı saat 14.00", repository.load().single().text)
    }

    @Test fun addressedInlineNoteWithoutPunctuationUsesTheWholeBody() {
        brain.respond("Nova not al süt al")
        assertEquals("süt al", repository.load().single().text)
    }

    @Test fun cancellingNoteCaptureDoesNotSaveTheFollowingCommand() {
        brain.respond("not al")
        assertEquals("Not alma işlemini iptal ettim.", brain.respond("İPTAL").text)
        assertEquals("Saat 03:00", brain.respond("saat kaç").text)
        assertTrue(repository.load().isEmpty())
    }

    @Test fun deletionRequiresUiConfirmationAndOnlyConfirmedMethodDeletes() {
        brain.respond("not al alışveriş")
        val reply = brain.respond("notları sil")
        assertEquals(Action.ConfirmDeleteNotes(1), reply.action)
        assertEquals(1, repository.load().size)
        assertEquals("Kayıtlı notlarını sildim.", brain.deleteAllNotes().text)
        assertTrue(repository.load().isEmpty())
        assertNull(brain.respond("notları sil").action)
    }

    @Test fun arithmeticWorksForTypingAndTurkishSpeech() {
        assertEquals("Sonuç: 84", brain.respond("hesapla 12 * 7").text)
        assertEquals("Sonuç: 84", brain.respond("on iki çarpı yedi kaç eder?").text)
        assertEquals("Sonuç: 3,75", brain.respond("hesapla iki virgül beş artı 1,25").text)
        assertEquals("Sonuç: 14", brain.respond("HESAPLA   İKİ   ARTI ÜÇ ÇARPI DÖRT").text)
        assertTrue(brain.respond("hesapla 5 bölü sıfır").text.contains("Sıfıra bölme"))
    }

    @Test fun dateAndClockUseTurkishLocaleAndInjectedTimeZone() {
        assertEquals("Saat 03:00", brain.respond("SAAT KAÇ?").text)
        assertEquals("Bugün 1 Ocak 1970 Perşembe", brain.respond("bugün günlerden ne").text)
    }

    @Test fun webSearchEncodesUserTextAndKeepsItInsideQuery() {
        val reply = brain.respond("İnternette İstanbul & müzik / 2026 ara")
        val action = reply.action as Action.OpenUrl
        assertTrue(action.url.startsWith("https://www.google.com/search?q="))
        assertEquals("İstanbul & müzik / 2026", URLDecoder.decode(action.url.substringAfter("?q="), "UTF-8"))
    }

    @Test fun mapSearchStripsTurkishShowSuffix() {
        val action = brain.respond("Haritada Beşiktaş eczane göster").action as Action.OpenUrl
        assertTrue(action.url.startsWith("https://www.google.com/maps/search/?api=1&query="))
        assertEquals("Beşiktaş eczane", URLDecoder.decode(action.url.substringAfter("&query="), "UTF-8"))
    }

    @Test fun settingsCommandsReturnActionsAndDoNotClaimToChangeRadios() {
        val wifi = brain.respond("Wi-Fi aç")
        assertEquals(Action.OpenSettings(SettingsScreen.WIFI), wifi.action)
        assertTrue(wifi.text.contains("ayarlarını"))
        assertEquals(Action.OpenSettings(SettingsScreen.BLUETOOTH), brain.respond("Bluetooth ayarlarını aç").action)
    }

    @Test fun knownAppsHaveExplicitPackagesAndHttpsFallbacks() {
        val action = brain.respond("YouTube aç").action as Action.OpenApp
        assertEquals("com.google.android.youtube", action.packageName)
        assertTrue(action.fallbackUrl!!.startsWith("https://"))
        assertEquals("com.whatsapp", (brain.respond("WhatsApp aç").action as Action.OpenApp).packageName)
        assertNotNull(brain.respond("tarayıcıyı aç").action)
    }

    @Test fun nameIsReadLazilyAndBasicDialogueHasContext() {
        var name = "Nova"
        val renamed = AssistantBrain(repository, nameProvider = { name })
        name = "Yıldız"
        assertTrue(renamed.respond("adın ne").text.startsWith("Ben Yıldız."))
        assertTrue(renamed.respond("Yıldız nasılsın").text.contains("Sen nasılsın?"))
        assertTrue(renamed.respond("iyiyim").text.startsWith("Buna sevindim."))
    }

    @Test fun unsupportedRequestsDoNotPretendToPerformActionsOrUseAi() {
        val reply = brain.respond("anneme mesaj gönder")
        assertTrue(reply.text.startsWith("Bunu henüz yapamıyorum."))
        assertNull(reply.action)
        assertTrue(brain.respond("yardım").text.contains("genel sorulara yapay zekâ yanıtı üretmiyorum"))
        assertFalse(brain.respond(" ").text.isBlank())
    }

    @Test fun oversizedNoteDoesNotOverwriteOrDropExistingNotes() {
        brain.respond("not al ilk not")
        brain.respond("not al " + "a".repeat(2_001))
        assertEquals(listOf("ilk not"), repository.load().map { it.text })
    }

    private class MemoryNotes : NoteRepository {
        private val notes = mutableListOf<Note>()
        override fun load(): List<Note> = notes.toList()
        override fun add(text: String, timestamp: Long) = Note("note-${notes.size}", text, timestamp).also { notes.add(it) }
        override fun clear() { notes.clear() }
    }
}
