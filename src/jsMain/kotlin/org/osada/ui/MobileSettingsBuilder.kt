package org.osada.ui

import org.osada.i18n.I18n
import org.osada.uiSettings
import org.w3c.dom.HTMLElement
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
    /** `<select id>` -> (label/help key prefix, option value -> option label key), shared by
     *  [buildControls] and [refreshLocalization] so a language change cannot miss a row. These
     *  rows had no refresh path at all and kept the language they were built in — the Russian
     *  "Упрощённые эффекты"/"Показать" block a player saw under English. */
    private val selectRows =
        listOf(
            Triple(
                "osadaMobileUi",
                "settings.mobile.interface",
                listOf(
                    MobileUiOverride.AUTO to "settings.mobile.value.auto",
                    MobileUiOverride.ON to "settings.mobile.value.on",
                    MobileUiOverride.OFF to "settings.mobile.value.off",
                ),
            ),
            Triple(
                "osadaConfirmAttacks",
                "settings.mobile.confirm_attacks",
                listOf(
                    ConfirmAttacks.AUTO to "settings.mobile.value.auto",
                    ConfirmAttacks.ON to "settings.mobile.value.on",
                    ConfirmAttacks.OFF to "settings.mobile.value.off",
                ),
            ),
            Triple(
                "osadaDensity",
                "settings.mobile.density",
                listOf(
                    "compact" to "settings.mobile.density.compact",
                    "standard" to "settings.mobile.density.standard",
                    "large" to "settings.mobile.density.large",
                ),
            ),
        )

    fun buildControls() {
        val handlers: Map<String, (String) -> Unit> =
            mapOf(
                "osadaMobileUi" to { value -> MobileLayoutController.setOverride(value) },
                "osadaConfirmAttacks" to { value -> uiSettings.confirmAttacks = value },
                "osadaDensity" to { value ->
                    uiSettings.interfaceDensity = value
                    // Density is published as a body class, so re-running the layout pass applies it.
                    MobileLayoutController.applyNow()
                },
            )
        val currents =
            mapOf(
                "osadaMobileUi" to uiSettings.mobileUiMode,
                "osadaConfirmAttacks" to uiSettings.confirmAttacks,
                "osadaDensity" to uiSettings.interfaceDensity,
            )
        selectRows.forEach { (id, labelKey, options) ->
            selectRow(id, labelKey, options, currents[id].orEmpty(), handlers.getValue(id))
        }
        buildTutorialReplayRow()
    }

    /** Re-labels the already-built mobile rows in the new language, values and selection intact. */
    fun refreshLocalization() {
        selectRows.forEach { (id, labelKey, options) ->
            val select = byId(id) as? HTMLSelectElement ?: return@forEach
            val help = I18n.t("$labelKey.help")
            select.title = help
            (select.asDynamic().closest(".settingContainer") as? HTMLElement)?.let { container ->
                container.title = help
                (container.querySelector(".settingText") as? HTMLElement)?.apply {
                    textContent = I18n.t("$labelKey.label")
                    title = help
                }
            }
            // Rewrite option TEXT only. Rebuilding the options would drop the current selection.
            val selected = select.value
            options.forEachIndexed { index, (_, optionKey) ->
                (select.asDynamic().options[index] as? HTMLElement)?.textContent = I18n.t(optionKey)
            }
            select.value = selected
        }
        byId("osadaTutorialReplay")?.apply {
            val label = I18n.t("settings.mobile.tutorial.replay")
            textContent = label
            setAttribute("aria-label", label)
            title = I18n.t("settings.mobile.tutorial.help")
        }
        (byId("osadaTutorialReplay")?.asDynamic()?.closest(".settingContainer") as? HTMLElement)?.let { container ->
            val help = I18n.t("settings.mobile.tutorial.help")
            container.title = help
            (container.querySelector(".settingText") as? HTMLElement)?.apply {
                textContent = I18n.t("settings.mobile.tutorial.label")
                title = help
            }
        }
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
        // NOT `.smallButton`: that is the 26px ROUND BRASS PLATE used for single-glyph icon
        // buttons, and a whole word stretched its background image into a blurry oval. This row
        // needs a labelled button, so it gets the same dark panel treatment as the Settings "Done"
        // button beneath it.
        button.className = "osada-settings-btn"
        button.textContent = I18n.t("settings.mobile.tutorial.replay")
        button.asButton(I18n.t("settings.mobile.tutorial.replay")) { MobileOnboarding.show() }
    }
}
