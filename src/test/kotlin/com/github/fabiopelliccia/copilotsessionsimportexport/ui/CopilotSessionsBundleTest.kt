package com.github.fabiopelliccia.copilotsessionsimportexport.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class CopilotSessionsBundleTest {

    private val original: Locale = Locale.getDefault(Locale.Category.DISPLAY)

    @After
    fun restoreLocale() = Locale.setDefault(Locale.Category.DISPLAY, original)

    @Test
    fun `follows the regional settings of the machine`() {
        Locale.setDefault(Locale.Category.DISPLAY, Locale.forLanguageTag("it-IT"))
        assertEquals("Riavvia l'IDE ora", restartLabel())

        Locale.setDefault(Locale.Category.DISPLAY, Locale.forLanguageTag("de-AT"))
        assertEquals("IDE jetzt neu starten", restartLabel())
    }

    @Test
    fun `keeps the non latin translations readable`() {
        Locale.setDefault(Locale.Category.DISPLAY, Locale.forLanguageTag("ja-JP"))
        assertEquals("今すぐ IDE を再起動", restartLabel())
    }

    @Test
    fun `falls back to english for an untranslated language`() {
        Locale.setDefault(Locale.Category.DISPLAY, Locale.forLanguageTag("fi-FI"))
        assertEquals("Restart IDE now", restartLabel())

        Locale.setDefault(Locale.Category.DISPLAY, Locale.US)
        assertEquals("Restart IDE now", restartLabel())
    }

    private fun restartLabel() = CopilotSessionsBundle.message("notification.action.restartIde")
}
