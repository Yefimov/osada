package org.osada.ui

import org.osada.i18n.I18n
import org.osada.i18n.Language
import org.w3c.dom.HTMLElement

/** Reusable native language dropdown for the main menu and Settings. */
internal object LanguageSelector {
    private const val MAIN_CONTROL_ID = "smLanguageSwitch"
    private const val MAIN_SELECT_ID = "smLanguageSelect"
    private const val SETTINGS_CONTROL_ID = "smLanguageSetting"
    private const val SETTINGS_SELECT_ID = "smSettingsLanguageSelect"

    fun buildMainMenuControl() {
        LiveLocalization.install()
        if (byId(MAIN_CONTROL_ID) != null) return
        val parent = byId("smMain") ?: return
        val root = addTag(parent, "label")
        root.id = MAIN_CONTROL_ID
        root.className = "osada-language-switch"

        val globe = addTag(root, "span")
        globe.className = "osada-language-switch__globe"
        globe.setAttribute("aria-hidden", "true")
        globe.textContent = "🌐"

        val label = addTag(root, "span")
        label.className = "osada-language-switch__label"

        buildSelect(root, MAIN_SELECT_ID, "osada-language-switch__select")
        refreshAll()
    }

    fun buildSettingsControl() {
        LiveLocalization.install()
        if (byId(SETTINGS_CONTROL_ID) != null) return
        val container = addTag("smSettingsContainer", "div")
        container.id = SETTINGS_CONTROL_ID
        container.className = "settingContainer left osada-language-setting"

        val label = addTag(container, "div")
        label.className = "settingText left osada-language-setting__label"

        val value = addTag(container, "div")
        value.className = "settingValue right osada-language-setting__value"
        buildSelect(value, SETTINGS_SELECT_ID, "osada-language-setting__select")
        refreshAll()
    }

    fun refreshAll() {
        val label = I18n.t("menu.language.label")
        val help = I18n.t("settings.language.help")

        byId(MAIN_CONTROL_ID)?.apply {
            title = help
            setAttribute("aria-label", label)
        }
        (byId(MAIN_CONTROL_ID)?.querySelector(".osada-language-switch__label") as? HTMLElement)?.textContent = label
        refreshSelect(MAIN_SELECT_ID, help)

        byId(SETTINGS_CONTROL_ID)?.title = help
        (byId(SETTINGS_CONTROL_ID)?.querySelector(".osada-language-setting__label") as? HTMLElement)?.apply {
            textContent = label
            title = help
        }
        refreshSelect(SETTINGS_SELECT_ID, help)
    }

    private fun buildSelect(
        parent: HTMLElement,
        id: String,
        className: String,
    ) {
        val select = addTag(parent, "select")
        select.id = id
        select.className = className
        Language.entries.forEach { language ->
            val option = addTag(select, "option")
            option.textContent = language.autonym
            option.setAttribute("value", language.locale)
            option.setAttribute("lang", language.locale)
        }
        select.asDynamic().onchange = {
            val value = select.asDynamic().value as? String
            Language.fromCodeOrNull(value)?.let { I18n.setLanguage(it) }
        }
    }

    private fun refreshSelect(
        id: String,
        help: String,
    ) {
        byId(id)?.apply {
            title = help
            setAttribute("aria-label", I18n.t("menu.language.label"))
            asDynamic().value = I18n.language.locale
        }
    }
}
