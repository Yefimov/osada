@file:Suppress("LargeClass", "LongMethod", "TooManyFunctions")

package org.osada.ui

import org.osada.GameHolder
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
        GameStateMenuBuilder.refreshLocalization()
        refreshCampaignScreen()
        refreshScenarioScreen()
        GameplayLocalization.refreshAll()
    }

    private fun refreshMainMenu() {
        val buttonKeys =
            mapOf(
                "continuegame" to "menu.main.continue",
                "restartmission" to "menu.main.restart_mission",
                "newcampaign" to "menu.main.new_campaign",
                "multiplayer" to "menu.main.multiplayer",
                "newscenario" to "menu.main.single_scenario",
                "settings" to "menu.main.settings",
                "tutorial" to "menu.main.tutorial",
            )
        buttonKeys.forEach { (id, prefix) -> refreshMenuButton(id, prefix) }
        refreshMenuButton(
            "saveload",
            if (GameHolder.instance?.gameStarted == true) "menu.main.save_load" else "menu.main.load_game",
        )
        StartMenuBuilder.savedGameSummary()?.takeIf(String::isNotBlank)?.let { summary ->
            byId("continuegame")?.querySelector(".osada-menu-btn__sub")?.textContent = summary
        }

        byId("smLogoText")?.textContent = I18n.t("menu.main.tagline")
        byId("heroDesk")?.apply {
            title = I18n.t("menu.main.hero_desk.help")
            setAttribute("aria-label", I18n.t("menu.main.hero_desk.label"))
            querySelector(".osada-menu-btn__label")?.textContent = I18n.t("menu.main.hero_desk.label")
            querySelector(".osada-menu-btn__sub")?.textContent = I18n.t("menu.main.hero_desk.subtitle")
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

    /**
     * Re-labels the already-built settings screen after an in-session language change.
     *
     * Everything here is derived from [StartMenuSettingsBuilder]'s own declarations rather than
     * restated. The previous version kept a hand-written copy of the section list and the checkbox
     * key map, and both went stale the moment the Mobile section was added:
     *
     * - the section list still had four entries against the screen's five, and matched them to
     *   headers BY INDEX — so from Mobile onwards every header got the title of the section *after*
     *   it (Mobile read "Sound", Sound read "Observer Mode") and the real Observer Mode header was
     *   never written to at all;
     * - `reducedEffects` was missing from the checkbox map, and [MobileSettingsBuilder]'s selects
     *   and Replay button were not covered by anything, so those rows kept whatever language they
     *   were built in — the "Упрощённые эффекты" / "Показать" rows a player saw under English.
     *
     * Deriving from the builder is what makes those two failure modes structurally impossible.
     */
    private fun refreshSettings() {
        LanguageSelector.refreshAll()
        refreshSettingsHeaders()
        StartMenuSettingsBuilder.settingSections
            .flatMap { it.items }
            .forEach { (id, labelKey) ->
                refreshSettingRow(id, labelKey, StartMenuSettingsBuilder.settingHelpKeys[id] ?: labelKey)
            }
        StartMenuSettingsBuilder.sliderLabelKeys.forEach { (id, labelKey) ->
            refreshSettingRow(id, labelKey, StartMenuSettingsBuilder.sliderHelpKeys[id] ?: labelKey)
        }
        MobileSettingsBuilder.refreshLocalization()
        // Re-written after the rows above: while a multiplayer match is running the Observer Mode
        // rows carry the lock explanation, not their own help text.
        ObserverModeLock.refresh()
        byId("smSetOkBut")?.apply {
            title = I18n.t("settings.done.help")
            setAttribute("data-label", I18n.t("common.done.label"))
        }
        byId("uiokbut")?.setAttribute("data-label", I18n.t("common.continue.label"))
    }

    private fun refreshSettingsHeaders() {
        // Same order the builder emits: the Display header for the top sliders, then one per
        // section. Index matching is only safe because both sides now read the same list.
        val titles =
            listOf(StartMenuSettingsBuilder.DISPLAY_SECTION_TITLE_KEY to null) +
                StartMenuSettingsBuilder.settingSections.map { it.titleKey to it.captionKey }
        val headers = byId("smSettingsContainer")?.querySelectorAll(".osada-settings-header")
        titles.forEachIndexed { index, (titleKey, captionKey) ->
            val header = headers?.item(index) as? org.w3c.dom.HTMLElement ?: return@forEachIndexed
            header.querySelector(".osada-settings-header__title")?.textContent = I18n.t(titleKey)
            captionKey?.let { key ->
                header.querySelector(".osada-settings-header__caption")?.textContent = I18n.t(key)
            }
        }
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
        byId("smCampDifficultyLabel")?.textContent = I18n.t("campaign.difficulty.label")
        StartMenuCampaignStory.refreshLocalization()
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
        CampaignBackupButtons.refresh()
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

    /**
     * Re-renders every campaign row from current state.
     *
     * `internal` rather than private because it is not only a language-switch concern: importing a
     * campaign file changes one row's operation/turn note, and re-running this is what keeps the
     * register from showing the run the import just replaced ([CampaignBackupButtons]).
     */
    @Suppress("LoopWithTooManyJumpStatements")
    internal fun refreshCampaignRows() {
        val list = byId("osadaCampList") ?: return
        val select = byId("smCampSel")?.querySelector("select") ?: return
        val options = select.asDynamic().options
        val runs = StartMenuCampaignData.campaignRunsByFile()
        val rows = list.children
        for (index in 0 until rows.length) {
            val row = rows.item(index) as? org.w3c.dom.HTMLElement ?: continue
            val optionIndex = row.asDynamic().optionIndex as? Int ?: continue
            val option = options[optionIndex] ?: continue
            val campaignIndex = (option.value as? String)?.toIntOrNull() ?: continue
            val campaign = StartMenuBuilder.campaignList().getOrNull(campaignIndex) ?: continue
            applyCampaignRow(row, option, campaign, runs)
        }
    }

    private fun applyCampaignRow(
        row: org.w3c.dom.HTMLElement,
        option: dynamic,
        campaign: dynamic,
        runs: Map<String, org.osada.save.CampaignRunMetadata>,
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
        applyCampaignProgressNote(row, campaign.file as? String, operations, runs)
    }

    private fun applyCampaignProgressNote(
        row: org.w3c.dom.HTMLElement,
        file: String?,
        operations: Int?,
        runs: Map<String, org.osada.save.CampaignRunMetadata>,
    ) {
        val run = file?.let { runs[it] } ?: return
        val note = row.querySelector(".osadaListRowNote") as? org.w3c.dom.HTMLElement ?: return
        val (text, title) = StartMenuCampaignData.progressNoteText(run, operations)
        note.textContent = text
        note.title = title
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
}
