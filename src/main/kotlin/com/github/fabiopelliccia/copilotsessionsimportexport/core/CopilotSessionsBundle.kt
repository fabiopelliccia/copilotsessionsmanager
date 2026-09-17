package com.github.fabiopelliccia.copilotsessionsimportexport.core

import java.text.MessageFormat
import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle

/**
 * Localized texts of the plugin, resolved against the language of the machine the IDE runs on.
 *
 * The IDE itself is English unless a language pack is installed, so relying on its display language
 * alone would ignore users whose workstation is configured for another country. The lookup therefore
 * walks two candidates before giving up on English:
 *
 * 1. the IDE display language, when it is an explicit localization (anything but English);
 * 2. the regional settings of the operating system.
 *
 * A locale without a translation of its own simply falls through to the next candidate, and a key
 * missing from an otherwise translated file falls back to the English text through the parent chain
 * of the bundle, so adding a `CopilotSessionsBundle_<language>.properties` file - even a partial one
 * - is all it takes to support one more language.
 *
 * It lives in `core/` rather than in `ui/` because the warnings and the progress texts of a transfer
 * are written here: see [displayLanguage] for how the IDE language reaches an IntelliJ free layer.
 */
internal object CopilotSessionsBundle {

    private const val BUNDLE = "messages.CopilotSessionsBundle"

    private val control: ResourceBundle.Control =
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)

    /**
     * Display language of the IDE, or `null` when it is unknown.
     *
     * Reading it means calling `DynamicBundle`, an IntelliJ API that `core/` must stay free of, so
     * the UI layer installs the real implementation (`ui/IdeDisplayLanguage`) before any action can
     * run. Left unset - in the unit tests, or wherever the UI never loaded - the lookup simply
     * starts from the regional settings of the operating system.
     */
    @Volatile
    var displayLanguage: () -> Locale? = { null }

    /**
     * Text of [key], with [arguments] substituted into its `{0}`, `{1}`... placeholders.
     *
     * [MessageFormat] is applied **only** when arguments are passed: a translation without
     * placeholders may therefore contain apostrophes as they are, while a translation with
     * placeholders has to double them (`''`), as that format requires.
     */
    fun message(key: String, vararg arguments: Any?): String {
        for (locale in candidateLocales()) {
            translation(locale)?.let { return format(it, key, arguments) }
        }
        return baseBundle()?.let { format(it, key, arguments) } ?: key
    }

    private fun format(bundle: ResourceBundle, key: String, arguments: Array<out Any?>): String {
        val pattern = try {
            bundle.getString(key)
        } catch (_: MissingResourceException) {
            return key
        }
        if (arguments.isEmpty()) return pattern
        val locale = bundle.locale.takeIf { it.language.isNotEmpty() } ?: Locale.ROOT
        return MessageFormat(pattern, locale).format(arguments)
    }

    private fun candidateLocales(): List<Locale> = buildList {
        ideLocale()?.takeIf { it.language.isNotEmpty() && it.language != Locale.ENGLISH.language }?.let(::add)
        add(Locale.getDefault(Locale.Category.DISPLAY))
    }

    private fun ideLocale(): Locale? = runCatching { displayLanguage() }.getOrNull()

    /**
     * Returns the bundle only when the locale is actually translated: without this check the JDK
     * would answer with the English base bundle and the next candidate would never be tried.
     */
    private fun translation(locale: Locale): ResourceBundle? =
        bundle(locale)?.takeIf { it.locale.language.isNotEmpty() }

    private fun baseBundle(): ResourceBundle? = bundle(Locale.ROOT)

    private fun bundle(locale: Locale): ResourceBundle? = try {
        ResourceBundle.getBundle(BUNDLE, locale, CopilotSessionsBundle::class.java.classLoader, control)
    } catch (_: MissingResourceException) {
        null
    }
}
