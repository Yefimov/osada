@file:Suppress("LargeClass", "LongMethod", "TooManyFunctions")

package org.osada.ui

import kotlinx.browser.localStorage
import org.osada.GameHolder
import org.osada.VERSION
import org.osada.i18n.I18n
import org.osada.model.Equipment
import org.osada.model.getCountryNameByEqp

/** Refreshes already-built start-menu DOM after an in-session locale change. */
internal object LiveLocalization {
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        I18n.onLanguageChanged(::refresh)
    }

    private fun refresh() {
        LanguageSelector.refreshAll()
        refreshMainMenu()
        refreshSettings()
        refreshCampaignScreen()
        refreshScenarioScreen()
        GameplayLocalization.refreshAll()
    }

    private fun refreshMainMenu() {
        val buttonKeys =
            mapOf(
                "continuegame" to "menu.main.continue",
                "newcampaign" to "menu.main.new_campaign",
                "newscenario" to "menu.main.single_scenario",
                "settings" to "menu.main.settings",
                "tutorial" to "menu.main.tutorial",
            )
        buttonKeys.forEach { (id, prefix) -> refreshMenuButton(id, prefix) }
        refreshMenuButton(
            "saveload",
            if (GameHolder.instance?.gameStarted == true) "menu.main.save_load" else "menu.main.load_game",
        )
        savedGameSummary()?.takeIf(String::isNotBlank)?.let { summary ->
            byId("continuegame")?.querySelector(".osada-menu-btn__sub")?.textContent = summary
        }

        byId("smLogoText")?.textContent = I18n.t("menu.main.tagline")
        byId("hallOfFame")?.apply {
            title = I18n.t("menu.main.hall_of_fame.help")
            querySelector(".osada-menu-btn__label")?.textContent = I18n.t("menu.main.hall_of_fame.label")
            querySelector(".osada-menu-btn__sub")?.textContent = I18n.t("menu.main.hall_of_fame.subtitle")
        }
        StartMenuBuilder.refreshRandomQuote()
    }

    private fun refreshMenuButton(
        id: String,
        keyPrefix: String,
    ) {
        byId(id)?.apply {
            val label = I18n.t("$keyPrefix.label")
            val subtitle = I18n.t("$keyPrefix.subtitle")
            title = subtitle
            querySelector(".osada-menu-btn__label")?.textContent = label
            querySelector(".osada-menu-btn__sub")?.textContent = subtitle
        }
    }

    private fun savedGameSummary(): String? {
        val majorVersion = VERSION.split(".").take(2).joinToString(".")
        val raw = localStorage.getItem("osada-scenario-$majorVersion") ?: return null
        return try {
            val data = JSON.parse<dynamic>(raw)
            val name = data.name as? String
            val turn = data.turn as? Int
            val maxTurns = data.maxTurns as? Int
            when {
                name != null && turn != null && maxTurns != null ->
                    I18n.t(
                        "menu.save.summary_full",
                        mapOf("name" to name, "turn" to turn, "maxTurns" to maxTurns),
                    )
                name != null && turn != null ->
                    I18n.t(
                        "menu.save.summary_short",
                        mapOf("name" to name, "turn" to turn),
                    )
                else -> ""
            }
        } catch (_: Throwable) {
            ""
        }
    }

    private fun refreshSettings() {
        LanguageSelector.refreshAll()
        val sectionKeys =
            listOf(
                "settings.section.map_view.title" to null,
                "settings.section.gameplay.title" to null,
                "settings.section.sound.title" to null,
                "settings.section.observer.title" to "settings.section.observer.caption",
            )
        val headers = byId("smSettingsContainer")?.querySelectorAll(".osada-settings-header")
        sectionKeys.forEachIndexed { index, (titleKey, captionKey) ->
            val header = headers?.item(index) as? org.w3c.dom.HTMLElement ?: return@forEachIndexed
            header.querySelector(".osada-settings-header__title")?.textContent = I18n.t(titleKey)
            captionKey?.let { key ->
                header.querySelector(".osada-settings-header__caption")?.textContent = I18n.t(key)
            }
        }

        settingKeys.forEach { (id, keys) -> refreshSettingRow(id, keys.first, keys.second) }
        sliderKeys.forEach { (id, keys) -> refreshSettingRow(id, keys.first, keys.second) }
        byId("smSetOkBut")?.title = I18n.t("settings.done.help")
    }

    private fun refreshSettingRow(
        controlId: String,
        labelKey: String,
        helpKey: String,
    ) {
        val control = byId(controlId) ?: return
        val container = control.asDynamic().closest(".settingContainer") as? org.w3c.dom.HTMLElement ?: return
        val label = I18n.t(labelKey)
        val help = I18n.t(helpKey)
        container.title = help
        (container.querySelector(".settingText") as? org.w3c.dom.HTMLElement)?.apply {
            textContent = label
            title = help
        }
        control.title = help
    }

    private fun refreshCampaignScreen() {
        byId("smCampHeader")?.textContent = I18n.t("campaign.selection.title")
        byId("smCBackBut")?.apply {
            title = I18n.t("campaign.back.help")
            setAttribute("data-label", I18n.t("common.back.label"))
        }
        byId("smCPlayBut")?.apply {
            title = I18n.t("campaign.start.help")
            setAttribute("data-label", I18n.t("campaign.start.label"))
        }
        byId("smCFlowBut")?.title = I18n.t("campaign.flow.help")
        (byId("smCampSel")?.querySelector("select") as? org.w3c.dom.HTMLElement)?.title =
            I18n.t("campaign.select.help")
        (byId("smCampPath")?.querySelector(".osadaCollapseSummary") as? org.w3c.dom.HTMLElement)?.apply {
            textContent = I18n.t("campaign.path.label")
            title = I18n.t("campaign.path.help")
        }

        refreshDifficultySelector()
        refreshSelectedCampaignDossier()
        refreshCampaignRows()
        byId("osadaCampList")?.let { list ->
            refreshListToolbar(list, "campaign.filter.placeholder", campaignModes)
            StartMenuListToolbar.applyListView(list)
        }
    }

    private fun refreshDifficultySelector() {
        val labels =
            mapOf(
                StartMenuCampaignData.DIFFICULTY_HISTORICAL to "campaign.difficulty.historical.label",
                StartMenuCampaignData.DIFFICULTY_TACTICAL to "campaign.difficulty.tactical.label",
                StartMenuCampaignData.DIFFICULTY_OPERATIONAL to "campaign.difficulty.operational.label",
            )
        val segments = byId("smCampDif")?.children ?: return
        @Suppress("LoopWithTooManyJumpStatements")
        for (index in 0 until segments.length) {
            val segment = segments.item(index) as? org.w3c.dom.HTMLElement ?: continue
            val difficulty = segment.asDynamic().diffValue as? Int ?: continue
            segment.textContent = labels[difficulty]?.let { I18n.t(it) } ?: segment.textContent
            segment.title = StartMenuCampaignData.difficultyHint(difficulty)
        }
        val selected =
            byId("smCamp")?.asDynamic()?.selectedDifficulty as? Int
                ?: StartMenuCampaignData.DIFFICULTY_HISTORICAL
        byId("smCampDifHint")?.textContent = StartMenuCampaignData.difficultyHint(selected)
        StartMenuCampaignData.updateCampaignPrestigeDisplay()
    }

    private fun refreshSelectedCampaignDossier() {
        val selected = byId("smCamp")?.asDynamic()?.selectedCampaign as? Int ?: return
        val campaign = StartMenuBuilder.campaignList().getOrNull(selected) ?: return
        val country =
            Equipment.getCountryNameByEqp(
                campaign.flag as? Int ?: 0,
                campaign.eqp as? String ?: "",
            )
        byId("smCampCountry")?.innerHTML = "<b>${I18n.t("campaign.country.label")}</b><br/>$country"
        val operations = campaign.scenarios as? Int
        val operationValue = operations?.let(I18n::formatNumber) ?: (campaign.scenarios as? String ?: "")
        byId("smCampScenarios")?.innerHTML = "<b>${I18n.t("campaign.operations.label")}</b><br/>$operationValue"
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private fun refreshCampaignRows() {
        val list = byId("osadaCampList") ?: return
        val select = byId("smCampSel")?.querySelector("select") ?: return
        val options = select.asDynamic().options
        val progress = StartMenuCampaignData.activeCampaignProgress()
        val rows = list.children
        for (index in 0 until rows.length) {
            val row = rows.item(index) as? org.w3c.dom.HTMLElement ?: continue
            val optionIndex = row.asDynamic().optionIndex as? Int ?: continue
            val option = options[optionIndex] ?: continue
            val campaignIndex = (option.value as? String)?.toIntOrNull() ?: continue
            val campaign = StartMenuBuilder.campaignList().getOrNull(campaignIndex) ?: continue
            applyCampaignRow(row, option, campaign, progress)
        }
    }

    private fun applyCampaignRow(
        row: org.w3c.dom.HTMLElement,
        option: dynamic,
        campaign: dynamic,
        progress: Pair<String, Int>?,
    ) {
        val operations = campaign.scenarios as? Int
        val years = StartMenuListToolbar.extractYears(option.text as? String ?: "")
        row.querySelector(".osadaListRowSub")?.innerHTML =
            listOfNotNull(
                years.ifBlank { null },
                operations?.let { I18n.plural("campaign.row.operations", it) },
            ).joinToString(" &middot; ")
        row.title =
            I18n.t(
                "list.select_dossier.help",
                mapOf("name" to (option.text as? String ?: "").trim()),
            )
        applyCampaignProgressNote(row, campaign.file as? String, operations, progress)
    }

    private fun applyCampaignProgressNote(
        row: org.w3c.dom.HTMLElement,
        file: String?,
        operations: Int?,
        progress: Pair<String, Int>?,
    ) {
        if (progress == null || file != progress.first) return
        val note = row.querySelector(".osadaListRowNote") as? org.w3c.dom.HTMLElement ?: return
        val current = progress.second + 1
        note.textContent =
            if (operations != null) {
                I18n.t("campaign.progress.full", mapOf("current" to current, "total" to operations))
            } else {
                I18n.t("campaign.progress.short", mapOf("current" to current))
            }
        note.title = I18n.t("campaign.progress.help")
    }

    private fun refreshScenarioScreen() {
        byId("smScenHeader")?.textContent = I18n.t("scenario.selection.title")
        byId("smSBackBut")?.apply {
            title = I18n.t("scenario.back.help")
            setAttribute("data-label", I18n.t("common.back.label"))
        }
        byId("smSPlayBut")?.title = I18n.t("scenario.start.help")
        (byId("smScenSel")?.querySelector("select") as? org.w3c.dom.HTMLElement)?.title =
            I18n.t("scenario.select.help")

        refreshScenarioRows()
        byId("osadaScenList")?.let { list ->
            refreshListToolbar(list, "scenario.filter.placeholder", scenarioModes)
            StartMenuListToolbar.applyListView(list)
        }
        refreshScenarioSidePicker()
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private fun refreshScenarioRows() {
        val list = byId("osadaScenList") ?: return
        val select = byId("smScenSel")?.querySelector("select") ?: return
        val options = select.asDynamic().options
        val rows = list.children
        for (index in 0 until rows.length) {
            val row = rows.item(index) as? org.w3c.dom.HTMLElement ?: continue
            if (row.classList.contains("osadaListRow--group")) continue
            val optionIndex = row.asDynamic().optionIndex as? Int ?: continue
            val option = options[optionIndex] ?: continue
            applyScenarioRow(row, option)
        }
    }

    private fun applyScenarioRow(
        row: org.w3c.dom.HTMLElement,
        option: dynamic,
    ) {
        row.title =
            I18n.t(
                "list.select_dossier.help",
                mapOf("name" to (option.text as? String ?: "").trim()),
            )
        val note = row.querySelector(".osadaListRowNote") as? org.w3c.dom.HTMLElement ?: return
        note.textContent =
            I18n.t(
                if (note.classList.contains("osadaListRowNote--played")) {
                    "scenario.status.played"
                } else {
                    "scenario.status.new"
                },
            )
    }

    private fun refreshScenarioSidePicker() {
        val selectedScenario = byId("smScen")?.asDynamic()?.selectedScenario as? Int ?: return
        val scenario = StartMenuBuilder.scenarioList().getOrNull(selectedScenario) ?: return
        val selectedSide = if (byId("smSide1")?.getAttribute("aria-checked") == "true") 1 else 0
        StartMenuSidePicker.selectScenarioSide(scenario, selectedSide)
    }

    private fun refreshListToolbar(
        list: org.w3c.dom.HTMLElement,
        placeholderKey: String,
        modes: List<String>,
    ) {
        val register = list.parentElement ?: return
        register.querySelector(".osadaListFilter")?.setAttribute("placeholder", I18n.t(placeholderKey))
        val sideSelect = register.querySelector(".osadaSideSelect") as? org.w3c.dom.HTMLElement
        sideSelect?.title = I18n.t("list.filter.country.help")
        val sideOptions = sideSelect?.asDynamic()?.options
        if (sideOptions != null && sideOptions != undefined && (sideOptions.length as? Int ?: 0) > 0) {
            sideOptions[0].text = I18n.t("list.all_countries.label")
        }

        val segments = register.querySelector(".osadaListSorts")?.children ?: return
        @Suppress("LoopWithTooManyJumpStatements")
        for (index in 0 until segments.length) {
            val segment = segments.item(index) as? org.w3c.dom.HTMLElement ?: continue
            val mode = modes.getOrNull(index) ?: continue
            val keyPrefix = sortKeyPrefix(mode) ?: continue
            segment.textContent = I18n.t("$keyPrefix.label")
            segment.title = I18n.t("$keyPrefix.help")
        }
    }

    private fun sortKeyPrefix(mode: String): String? =
        when (mode) {
            StartMenuListToolbar.SORT_DEFAULT -> "list.sort.default"
            StartMenuListToolbar.SORT_NAME -> "list.sort.name"
            StartMenuListToolbar.SORT_YEAR -> "list.sort.year"
            StartMenuListToolbar.SORT_SIZE -> "list.sort.size"
            else -> null
        }

    private val campaignModes =
        listOf(
            StartMenuListToolbar.SORT_DEFAULT,
            StartMenuListToolbar.SORT_NAME,
            StartMenuListToolbar.SORT_YEAR,
            StartMenuListToolbar.SORT_SIZE,
        )

    private val scenarioModes =
        listOf(
            StartMenuListToolbar.SORT_DEFAULT,
            StartMenuListToolbar.SORT_NAME,
        )

    private val settingKeys =
        mapOf(
            "showGridTerrain" to Pair("settings.map.show_grid_terrain.label", "settings.map.show_grid_terrain.help"),
            "markOwnUnits" to Pair("settings.map.mark_own_units.label", "settings.map.mark_own_units.help"),
            "markEnemyUnits" to Pair("settings.map.mark_enemy_units.label", "settings.map.mark_enemy_units.help"),
            "useRetina" to Pair("settings.map.use_retina.label", "settings.map.use_retina.help"),
            "quickAnimation" to
                Pair("settings.gameplay.quick_animation.label", "settings.gameplay.quick_animation.help"),
            "showDetailInfoToolTips" to
                Pair("settings.gameplay.optional_objectives.label", "settings.gameplay.optional_objectives.help"),
            "confirmEndTurn" to
                Pair("settings.gameplay.confirm_end_turn.label", "settings.gameplay.confirm_end_turn.help"),
            "muteUnitSounds" to Pair("settings.sound.mute_unit_sounds.label", "settings.sound.mute_unit_sounds.help"),
            "noFOW" to Pair("settings.observer.no_fow.label", "settings.observer.no_fow.help"),
            "showHiddenVictoryHexes" to
                Pair("settings.observer.hidden_victory_hexes.label", "settings.observer.hidden_victory_hexes.help"),
        )

    private val sliderKeys =
        mapOf(
            "uiresize" to Pair("settings.slider.interface_width.label", "settings.slider.interface_width.help"),
            "uiscale" to Pair("settings.slider.interface_scale.label", "settings.slider.interface_scale.help"),
            "mapscale" to Pair("settings.slider.map_scale.label", "settings.slider.map_scale.help"),
            "soundvolume" to Pair("settings.slider.effects_volume.label", "settings.slider.effects_volume.help"),
            "ambientvolume" to Pair("settings.slider.ambient_volume.label", "settings.slider.ambient_volume.help"),
        )
}
