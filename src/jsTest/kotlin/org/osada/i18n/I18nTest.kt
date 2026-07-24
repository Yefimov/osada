@file:Suppress("MaxLineLength")

package org.osada.i18n

import kotlin.test.Test
import kotlin.test.assertEquals

class I18nTest {
    @Test
    fun missingRussianValueFallsBackToEnglish() {
        I18n.installBundlesForTests(
            english = """{"menu.settings":"Settings"}""",
            selected = "{}",
            selectedLanguage = Language.RUSSIAN,
        )

        assertEquals("Settings", I18n.t("menu.settings"))
    }

    @Test
    fun blankRussianStringFallsBackToEnglish() {
        I18n.installBundlesForTests(
            english = """{"menu.settings":"Settings"}""",
            selected = """{"menu.settings":""}""",
            selectedLanguage = Language.RUSSIAN,
        )

        assertEquals("Settings", I18n.t("menu.settings"))
    }

    @Test
    fun blankRussianPluralBranchFallsBackToEnglish() {
        I18n.installBundlesForTests(
            english = """{"units":{"one":"{count} unit","other":"{count} units"}}""",
            selected = """{"units":{"one":"","few":"","many":"","other":""}}""",
            selectedLanguage = Language.RUSSIAN,
        )

        assertEquals("2 units", I18n.plural("units", 2))
    }

    @Test
    fun missingCanonicalValueReturnsStableKey() {
        I18n.installBundlesForTests(
            english = "{}",
            selected = "{}",
            selectedLanguage = Language.RUSSIAN,
        )

        assertEquals("missing.key", I18n.t("missing.key"))
    }

    @Test
    fun namedArgumentsCanBeReorderedByTranslation() {
        I18n.installBundlesForTests(
            english = """{"turn":"{name} · Turn {turn}"}""",
            selected = """{"turn":"Ход {turn} · {name}"}""",
            selectedLanguage = Language.RUSSIAN,
        )

        assertEquals("Ход 3 · Москва", I18n.t("turn", mapOf("name" to "Москва", "turn" to 3)))
    }

    @Test
    fun russianPluralCategoriesUseBrowserIntlRules() {
        I18n.installBundlesForTests(
            english = """{"operations":{"one":"{count} operation","other":"{count} operations"}}""",
            selected =
                """{"operations":{"one":"{count} операция","few":"{count} операции","many":"{count} операций","other":"{count} операции"}}""",
            selectedLanguage = Language.RUSSIAN,
        )

        assertEquals("1 операция", I18n.plural("operations", 1))
        assertEquals("2 операции", I18n.plural("operations", 2))
        assertEquals("5 операций", I18n.plural("operations", 5))
        assertEquals("21 операция", I18n.plural("operations", 21))
    }
}

class LanguageTest {
    @Test
    fun regionalBrowserLanguageMatchesSupportedBaseLanguage() {
        assertEquals(Language.RUSSIAN, Language.fromCode("ru-RU"))
        assertEquals(Language.ENGLISH, Language.fromCode("en-GB"))
    }

    @Test
    fun unsupportedBrowserLanguagesFallBackToEnglish() {
        assertEquals(Language.ENGLISH, Language.bestMatch(listOf("de-DE", "pl-PL")))
    }

    @Test
    fun browserPreferenceOrderSelectsFirstSupportedLanguage() {
        assertEquals(Language.RUSSIAN, Language.bestMatch(listOf("de-DE", "ru-RU", "en-US")))
    }
}
