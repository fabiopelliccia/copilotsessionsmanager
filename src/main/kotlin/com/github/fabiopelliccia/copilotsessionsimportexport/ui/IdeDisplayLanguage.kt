package com.github.fabiopelliccia.copilotsessionsimportexport.ui

import com.github.fabiopelliccia.copilotsessionsimportexport.core.CopilotSessionsBundle
import com.intellij.DynamicBundle

/**
 * Hands the display language of the IDE to [CopilotSessionsBundle].
 *
 * The bundle lives in `core/`, which may not touch the IntelliJ API, so the only piece that needs
 * it - reading the language of an installed language pack - is supplied from here. [install] is
 * called while [CopilotSessionActionBase] is loaded, that is before any text of this plugin can be
 * requested: every user visible message is produced by one of its two actions.
 */
internal object IdeDisplayLanguage {

    fun install() {
        CopilotSessionsBundle.displayLanguage = { runCatching { DynamicBundle.getLocale() }.getOrNull() }
    }
}
