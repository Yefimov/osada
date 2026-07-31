package org.osada.ui

import org.osada.i18n.I18n
import org.osada.uiSettings
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event

/**
 * The mobile block of the Settings screen (spec §52). Split out of [StartMenuSettingsBuilder],
 * which is already at the project's function-per-object limit.
 *
 * Three-way selects rather than checkboxes, because "auto" is the honest default for all of them:
 * the game can measure the device, and forcing a layout should be an explicit act. Page scale is
 * deliberately NOT offered here — browser zoom belongs to the browser (spec §42/§44).
 */
internal object MobileSettingsBuilder {
    fun buildControls() {
        selectRow(
            "osadaMobileUi",
            "settings.mobile.interface",
            listOf(
                MobileUiOverride.AUTO to "settings.mobile.value.auto",
                MobileUiOverride.ON to "settings.mobile.value.on",
                MobileUiOverride.OFF to "settings.mobile.value.off",
            ),
            uiSettings.mobileUiMode,
        ) { MobileLayoutController.setOverride(it) }

        selectRow(
            "osadaConfirmAttacks",
            "settings.mobile.confirm_attacks",
            listOf(
                ConfirmAttacks.AUTO to "settings.mobile.value.auto",
                ConfirmAttacks.ON to "settings.mobile.value.on",
                ConfirmAttacks.OFF to "settings.mobile.value.off",
            ),
            uiSettings.confirmAttacks,
        ) { uiSettings.confirmAttacks = it }

        selectRow(
            "osadaDensity",
            "settings.mobile.density",
            listOf(
                "compact" to "settings.mobile.density.compact",
                "standard" to "settings.mobile.density.standard",
                "large" to "settings.mobile.density.large",
            ),
            uiSettings.interfaceDensity,
        ) {
            uiSettings.interfaceDensity = it
            // Density is published as a body class, so re-running the layout pass applies it.
            MobileLayoutController.applyNow()
        }

        buildTutorialReplayRow()
    }

    private fun selectRow(
        id: String,
        labelKey: String,
        options: List<Pair<String, String>>,
        current: String,
        onChange: (String) -> Unit,
    ) {
        val container = addTag("smSettingsContainer", "div")
        container.className = "settingContainer left"
        val help = I18n.t("$labelKey.help")
        container.title = help
        val textDiv = addTag(container, "div")
        textDiv.className = "settingText left"
        textDiv.textContent = I18n.t("$labelKey.label")
        textDiv.title = help
        val wrap = addTag(container, "div")
        wrap.style.cssFloat = "right"
        val select = addTag(wrap, "select") as HTMLSelectElement
        select.id = id
        select.className = "osadaSideSelect"
        select.title = help
        options.forEach { (value, optionKey) ->
            val option = addTag(select, "option")
            option.setAttribute("value", value)
            option.textContent = I18n.t(optionKey)
            if (value == current) option.setAttribute("selected", "selected")
        }
        select.value = current
        select.addEventListener("change", { _: Event -> onChange(select.value) })
    }

    private fun buildTutorialReplayRow() {
        val container = addTag("smSettingsContainer", "div")
        container.className = "settingContainer left"
        val help = I18n.t("settings.mobile.tutorial.help")
        container.title = help
        val textDiv = addTag(container, "div")
        textDiv.className = "settingText left"
        textDiv.textContent = I18n.t("settings.mobile.tutorial.label")
        textDiv.title = help
        val wrap = addTag(container, "div")
        wrap.style.cssFloat = "right"
        val button = addTag(wrap, "div")
        button.id = "osadaTutorialReplay"
        button.className = "smallButton"
        button.textContent = I18n.t("settings.mobile.tutorial.replay")
        button.asButton(I18n.t("settings.mobile.tutorial.replay")) { MobileOnboarding.show() }
    }
}
