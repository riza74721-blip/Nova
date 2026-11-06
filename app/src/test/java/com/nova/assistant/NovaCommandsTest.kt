package com.nova.assistant

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date
import java.util.TimeZone

class NovaCommandsTest {
    @Test fun understandsTurkishUppercase() {
        assertEquals("Benim adım Nova.", NovaCommands.reply("ADIN NE"))
        assertEquals("Merhaba! Ben Nova.", NovaCommands.reply("MERHABA"))
    }

    @Test fun handlesEmptyAndUnknownSpeech() {
        assertEquals("Konuşma algılanamadı. Tekrar deneyebilirsin.", NovaCommands.reply("  \n "))
        assertEquals("Seni duydum: ışıkları aç. Bu komut henüz ilk sürümde desteklenmiyor.", NovaCommands.reply("  ışıkları aç "))
    }

    @Test fun clockUsesSuppliedTimeZoneAndCollapsesWhitespace() {
        assertEquals("Saat 03:00", NovaCommands.reply("SAAT   KAÇ", Date(0), TimeZone.getTimeZone("GMT+03:00")))
    }

    @Test fun dateIsTurkishAndUsesTheSameClock() {
        assertEquals("Bugün 1 Ocak 1970 Perşembe", NovaCommands.reply("BUGÜN GÜNLERDEN NE", Date(0), TimeZone.getTimeZone("UTC")))
        assertEquals("Bugün 1 Ocak 1970 Perşembe", NovaCommands.reply("tarih", Date(0), TimeZone.getTimeZone("UTC")))
    }
}
