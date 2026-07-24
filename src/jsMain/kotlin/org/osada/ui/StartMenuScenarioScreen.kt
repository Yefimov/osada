package org.osada.ui

import kotlinx.browser.localStorage
import org.osada.i18n.I18n
import org.osada.model.Equipment
import org.osada.model.getCountryName
import org.osada.model.getCountryNameByEqp
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLOptionElement

/**
 * [StartMenuBuilder]'s scenario-selection screen: the register (select dropdown, synced list,
 * dossier) and the played-scenario history. Split out purely to keep [StartMenuBuilder] within
 * the project's function-count/class-size limits -- not expected to be called from elsewhere.
 * The human/AI side picker lives in [StartMenuSidePicker]; shared register plumbing in
 * [StartMenuListToolbar].
 */
internal object StartMenuScenarioScreen {
    // scenariolist row tuple index: ['file','name','desc',[],[],'eqp'] -- see ScenarioLoader.
    private const val SCENARIO_EQP_INDEX = 5

    // ---- Played-scenario history (3c) ------------------------------------------------------
    // A plain localStorage set of scenario FILE names, written when a standalone scenario is
    // started. Touches no game save; purely cosmetic ("New" vs "Played" chip).
    private const val PLAYED_SCENARIOS_KEY = "osada-played-scenarios"

    fun buildScenarioSelection() {
        val scenSelect = buildScenarioSelectDropdown()
        wireScenarioHandlers(scenSelect)
    }

    private fun buildScenarioSelectDropdown(): HTMLElement {
        val scenSelect = addTag("smScenSel", "select")
        StartMenuBuilder.scenarioList().forEachIndexed { index, scenario ->
            val option = addTag(scenSelect, "option")
            val length = scenario.length as? Int ?: 0
            if (length == 1) {
                option.asDynamic().disabled = true
                option.textContent = "» " + (scenario[0] as? String ?: "")
            } else {
                option.asDynamic().value = index.toString()
                option.textContent = "    " + (scenario[1] as? String ?: "")
            }
        }
        return scenSelect
    }

    private fun wireScenarioHandlers(scenSelect: HTMLElement) {
        scenSelect.title = I18n.t("scenario.select.help")
        scenSelect.asDynamic().onchange = { onScenSelectChange(scenSelect) }
        buildScenarioScreen(scenSelect)

        byId("smSBackBut")?.title = I18n.t("scenario.back.help")
        byId("smSBackBut")?.setAttribute("data-label", I18n.t("common.back.label"))
        byId("smSBackBut")?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            makeHidden("smScen")
            makeVisible("smMain")
            val game = gameRef()
            UIBuilder.setEquipmentFlags(game?.scenario?.eqp as? String)
        }

        byId("smSPlayBut")?.title = I18n.t("scenario.start.help")
        byId("smSPlayBut")?.setAttribute("data-label", I18n.t("scenario.start.label"))
        byId("smSPlayBut")?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            val selectedScenario = byId("smScen")?.asDynamic()?.selectedScenario as? Int
            val scenario = selectedScenario?.let { StartMenuBuilder.scenarioList().getOrNull(it) }
            if (scenario != null) {
                StartMenuBuilder.startNewScenario(scenario[0] as? String ?: "", scenario[2] as? String ?: "")
            }
        }
    }

    private fun onScenSelectChange(scenSelect: HTMLElement) {
        val selectedIndex = scenSelect.asDynamic().selectedIndex as? Int ?: -1
        val value = if (selectedIndex < 0) null else scenSelect.asDynamic().options[selectedIndex].value as? String
        val scenarioIndex = value?.toIntOrNull()
        val scenario = scenarioIndex?.let { StartMenuBuilder.scenarioList().getOrNull(it) }
        if (scenarioIndex == null || scenario == null) return
        byId("smScenDesc")?.innerHTML = scenario[2] as? String ?: ""
        UIBuilder.setEquipmentFlags(scenario[SCENARIO_EQP_INDEX] as? String)
        // Reset to the scenario's own default: whichever side player id 0 belongs to is
        // human, everyone else is AI — same default the old per-player toggles used, now
        // driven through the side picker (selectScenarioSide) instead of a second state.
        StartMenuSidePicker.selectScenarioSide(scenario, StartMenuSidePicker.defaultHumanSide(scenario))
        byId("smScen")?.asDynamic()?.selectedScenario = scenarioIndex
        byId("smScenTitle")?.innerHTML = scenario[1] as? String ?: ""
        // Extract date from scenario description and show in subtitle
        val scenDesc = scenario[2] as? String ?: ""
        val scenDate =
            StartMenuListToolbar.extractYears(scenDesc).ifBlank {
                StartMenuListToolbar.extractYears(
                    scenario[1] as? String ?: "",
                )
            }
        byId("smScenDossierSub")?.innerHTML = scenDate
        byId("osadaScenList")?.let { StartMenuListToolbar.syncListHighlight(scenSelect, it) }
    }

    /** Restructures #smScen into header / register / dossier / footer and fills the register. */
    private fun buildScenarioScreen(scenSelect: HTMLElement) {
        val root = byId("smScen") ?: return
        byId("smScenSel")?.classList?.add("osadaHiddenSelect")

        val header = addTag(root, "div")
        header.id = "smScenHeader"
        header.className = "osadaScreenHeader"
        header.textContent = I18n.t("scenario.selection.title")

        val body = addTag(root, "div")
        body.id = "smScenBody"
        body.className = "osadaScreenBody"

        val register = addTag(body, "div")
        register.id = "smScenRegister"
        register.className = "osadaRegister"
        val list = addTag(register, "div")
        list.id = "osadaScenList"
        list.className = "osadaList"

        val dossier = addTag(body, "div")
        dossier.id = "smScenDossier"
        dossier.className = "osadaDossier"

        val head = addTag(dossier, "div")
        head.id = "smScenDossierHead"
        head.className = "osadaDossierHead"
        StartMenuListToolbar.theaterPlaceholder(head)
        val headText = addTag(head, "div")
        headText.className = "osadaDossierHeadText"
        val title = addTag(headText, "div")
        title.id = "smScenTitle"
        title.className = "osadaDossierTitle"
        val sub = addTag(headText, "div")
        sub.id = "smScenDossierSub"
        sub.className = "osadaDossierSub"

        byId("smScenPlayers")?.let { dossier.appendChild(it) }
        byId("smScenDesc")?.let { dossier.appendChild(it) }

        byId("smScenButtons")?.let { root.appendChild(it) }

        // Group headers now name the CAMPAIGN each scenario belongs to (scenariolist.js was regrouped
        // from per-efile to per-campaign), so carry the group name into each row: it becomes the
        // row's second line, and makes the campaign searchable from the scenario filter.
        var group = ""
        val played = playedScenarios()
        StartMenuListToolbar.buildSyncedList(scenSelect, list) { option, index, row, selectable ->
            if (!selectable) {
                group = option.text.replace("»", "").trim()
                row.textContent = group
                StartMenuListToolbar.tagRow(row, index, group)
            } else {
                renderScenarioRow(option, index, row, group, played)
            }
        }
        // No Year sort: scenariolist.js carries no date per scenario (it's in the scenario XML, which
        // isn't loaded until you start one). Campaign order already reads chronologically anyway.
        StartMenuListToolbar.buildListToolbar(
            register,
            list,
            listOf(StartMenuListToolbar.SORT_DEFAULT, StartMenuListToolbar.SORT_NAME),
            "scenario.filter.placeholder",
            "scenario.counter",
        )
    }

    private fun renderScenarioRow(
        option: HTMLOptionElement,
        index: Int,
        row: HTMLElement,
        group: String,
        played: Set<String>,
    ) {
        // `dynamic` indexes with brackets, NOT ?.get(n) — a safe-call `get` compiles to a real
        // .get() METHOD call, which a JS array doesn't have ("scenario.get is not a function").
        val scenario: dynamic = StartMenuBuilder.scenarioList().getOrNull(index)
        val text = addTag(row, "div")
        text.className = "osadaListRowText"
        val title = option.text.trim()
        val name = addTag(text, "div")
        name.className = "osadaListRowName"
        name.textContent = title
        val rowSub = addTag(text, "div")
        rowSub.className = "osadaListRowSub"
        rowSub.textContent = group

        // Side chip / flag come from the country the HUMAN would play — player id 0, the one
        // the scenario screen itself defaults to human (see onScenSelectChange). A scenario has
        // two sides, so "this scenario's side" can only mean the playable one.
        val eqpName = (if (scenario != null) scenario[5] else null) as? String ?: ""
        val humanCountry = humanCountryOf(scenario)
        val countryName =
            if (humanCountry != null) {
                Equipment.getCountryNameByEqp(humanCountry, eqpName)
            } else {
                ""
            }
        if (humanCountry != null && eqpName.isNotBlank()) {
            val flag = addTag(row, "div")
            flag.className = "osadaFlag"
            flag.style.backgroundImage = "url('resources/ui/flags/${Equipment.UNITED_NAME}/flags_med.png')"
            flag.style.backgroundPosition = "${-StartMenuListToolbar.FLAG_SPRITE_WIDTH * humanCountry}px 0px"
            flag.title = countryName
            // The flag belongs before the text column, which is already appended.
            row.insertBefore(flag, text)
        }

        val note = addTag(row, "div")
        val file = (if (scenario != null) scenario[0] else null) as? String ?: ""
        val isPlayed = file.isNotBlank() && file in played
        note.className =
            "osadaListRowNote" + if (isPlayed) " osadaListRowNote--played" else " osadaListRowNote--new"
        note.textContent = I18n.t(if (isPlayed) "scenario.status.played" else "scenario.status.new")

        // Side FILTER covers every country playable in the scenario, not just the human's
        // default (id 0) — a scenario like Battle of Sesena (Soviet Union vs Spain) must be
        // findable under "Spain" too, since the scenario dossier's own AI/human toggles let
        // you take either side. All country names go into the search text for the same reason.
        val allCountries = allCountriesOf(scenario)
        val allCountryNames =
            allCountries.mapNotNull {
                Equipment.getCountryName(it).takeIf { n ->
                    n.isNotBlank() &&
                        n != "Unknown"
                }
            }
        StartMenuListToolbar.tagRow(
            row,
            index,
            title,
            "$title $group ${allCountryNames.joinToString(" ")}",
            sides = allCountries.mapNotNull { StartMenuListToolbar.countryDisplayLabel(it) },
        )
    }

    /** The country of player id 0 — the one the scenario screen makes HUMAN by default. Scans
     *  both side arrays (scenariolist entries put side 0 at index 3, side 1 at index 4), since
     *  the human player is not always on side 0. */
    fun humanCountryOf(scenario: dynamic): Int? {
        if (scenario == null) return null
        val human =
            (0..1)
                .mapNotNull { side -> scenario[3 + side] as? Array<dynamic> }
                .flatMap { it.toList() }
                .firstOrNull { (it.id as? Int ?: -1) == 0 }
        return human?.country as? Int
    }

    /** Every country playable in [scenario] — both sides, every player slot, distinct — for the
     *  side filter and search text. Unlike [humanCountryOf] this is NOT limited to player id 0:
     *  the scenario dossier's own AI/human toggles let you take any player's side, so a scenario
     *  must be findable by whichever country you'd actually play, not just the default human one.
     *  Same raw indexing as [humanCountryOf] (scenariolist `country` indexes Equipment.countryNames
     *  and the flag sheet directly — no ±1 shift). Also includes each player's `support` countries
     *  (tools/eqp-merge/add_support_countries.py, backfilled from the scenario XML's own
     *  `<player support="...">`) — an extra playable nationality fighting under the same player,
     *  e.g. Makhno's Black Army alongside Red Russia in "1920 Siege of Perekop". Without this, a
     *  scenario whose own blurb says "you will also command Makhno's Black Army" was unfindable by
     *  filtering for that faction (2026-07-14 user report) since scenariolist.js's `country` field
     *  only ever recorded each side's primary nationality. */
    fun allCountriesOf(scenario: dynamic): List<Int> {
        if (scenario == null) return emptyList()
        val result = mutableListOf<Int>()
        for (side in 0..1) {
            val players = scenario[3 + side] as? Array<dynamic> ?: continue
            players.forEach { player -> addPlayerCountries(player, result) }
        }
        return result
    }

    private fun addPlayerCountries(
        player: dynamic,
        result: MutableList<Int>,
    ) {
        (player.country as? Int)?.let { if (it !in result) result.add(it) }
        (player.support as? Array<dynamic>)?.forEach { s ->
            (s as? Int)?.let { if (it !in result) result.add(it) }
        }
    }

    private fun playedScenarios(): Set<String> {
        val raw = localStorage.getItem(PLAYED_SCENARIOS_KEY) ?: return emptySet()
        return try {
            val arr = JSON.parse<Array<String>>(raw)
            arr.toSet()
        } catch (_: Throwable) {
            emptySet()
        }
    }

    fun markScenarioPlayed(file: String) {
        if (file.isBlank()) return
        val updated = playedScenarios() + file
        localStorage.setItem(PLAYED_SCENARIOS_KEY, JSON.stringify(updated.toTypedArray()))
    }
}
