@file:Suppress("TooManyFunctions")

package org.osada.multiplayer.ui

import kotlinx.browser.document
import org.osada.hero.HeroDisplay
import org.osada.hero.HeroPortraitArt
import org.osada.hero.PortraitComposerV2
import org.osada.i18n.I18n
import org.osada.multiplayer.coop.CoopAbility
import org.osada.multiplayer.coop.CoopAbilityCatalogue
import org.osada.multiplayer.coop.CoopCheckpoint
import org.osada.multiplayer.coop.CoopCommander
import org.osada.multiplayer.coop.CoopCommanderProfile
import org.osada.multiplayer.coop.CoopPortrait
import org.osada.multiplayer.coop.CoopProfile
import org.osada.multiplayer.coop.CoopTier
import org.osada.ui.PortraitRenderer
import org.osada.ui.RulesWindow
import org.osada.ui.RulesetSelection
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement

/** One commander card as the lobby shows it. */
data class CoopCardModel(
    val participantId: String,
    val displayName: String,
    val isHost: Boolean,
    val isSelf: Boolean,
    val ready: Boolean,
    val connected: Boolean,
    val profile: CoopCommanderProfile?,
)

/** One row of the campaign picker. */
data class CampaignChoice(
    val file: String,
    val title: String,
    val scenarios: Int,
)

class CoopLobbyModel(
    val roomCode: String?,
    val message: String?,
    val cards: List<CoopCardModel>,
    val capacity: Int,
    val minimum: Int,
    val draft: CoopCommanderProfile,
    val slotCap: Int,
    val loadoutIssues: List<String>,
    val selfReady: Boolean,
    val isHost: Boolean,
    val scenarios: List<ScenarioChoice>,
    val selectedScenarioFile: String?,
    val selectedScenarioName: String?,
    val campaigns: List<CampaignChoice>,
    val selectedCampaignFile: String?,
    val campaignDifficulty: Int,
    val contentChosen: Boolean,
    val startEnabled: Boolean,
    val onScenarioSelected: (String) -> Unit,
    val onCampaignSelected: (file: String, difficulty: Int) -> Unit,
    val onNameChanged: (String) -> Unit,
    val onPortraitChosen: (CoopPortrait) -> Unit,
    val onReroll: (era: String?, female: Boolean) -> Unit,
    val onAbilityToggled: (String) -> Unit,
    val onSymbolChosen: (String) -> Unit,
    val onToggleReady: () -> Unit,
    val onStart: () -> Unit,
    val onLeave: () -> Unit,
    val sides: List<MultiplayerSideChoice> = emptyList(),
    val selectedPlayerId: Int = 0,
    val onSideSelected: (Int) -> Unit = {},
    val onRestore: (CoopCheckpoint) -> Unit = {},
)

/**
 * The cooperative lobby (`docs/multiplayer/cooperative-commanders.md` §3.3, §4, §5).
 *
 * Everyone sees every commander card; only their own is editable, and the host alone chooses the
 * battle. The screen is rebuilt on every lobby update, so a teammate's edit would normally steal the
 * focus out of the name box mid-word -- [rebuild] therefore restores the focused control and its
 * caret, and the draft the player is typing lives in the model rather than in the DOM.
 */
object CoopLobbyScreen {
    private const val NAME_ID = "coopCommanderName"
    private var eraFilter: String = ""
    private var generatedFemale: Boolean? = null
    private var pickingSymbol = false
    private var profileFilter: CoopProfile? = null
    private var resumeSelection = false
    private var displayedRoom: String? = null
    private var resumeSession = CoopRecoveryScreen.Selection()

    private var lastModel: CoopLobbyModel? = null

    private fun rebuildFromLastModel() {
        lastModel?.let(::show)
    }

    fun show(model: CoopLobbyModel) {
        lastModel = model
        if (displayedRoom != model.roomCode) {
            displayedRoom = model.roomCode
            resumeSelection = false
            resumeSession = CoopRecoveryScreen.Selection()
        }
        val scrollTop = (document.querySelector("#smMultiplayerLocal .osada-mp-body") as? HTMLElement)?.scrollTop ?: 0.0
        val focusedId = (document.activeElement as? HTMLElement)?.id
        val caret = (document.activeElement as? HTMLInputElement)?.selectionStart
        rebuild(model)
        if (!focusedId.isNullOrEmpty()) {
            (document.getElementById(focusedId) as? HTMLElement)?.focus()
            if (caret !=
                null
            ) {
                (document.getElementById(focusedId) as? HTMLInputElement)?.setSelectionRange(caret, caret)
            }
        }
        (document.querySelector("#smMultiplayerLocal .osada-mp-body") as? HTMLElement)?.scrollTop = scrollTop
    }

    private fun rebuild(model: CoopLobbyModel) {
        val root = MultiplayerScreen.ensureRoot()
        root.innerHTML = ""
        MultiplayerScreen.appendHeader(
            root,
            I18n.t("multiplayer.coop.eyebrow"),
            I18n.t(if (resumeSelection) "multiplayer.coop.content.resume" else "multiplayer.coop.lobby.title"),
            I18n.t(
                if (resumeSelection) "multiplayer.coop.content.resume.help" else "multiplayer.coop.lobby.description",
            ),
        )
        val body = MultiplayerScreen.panel("osada-mp-body osada-mp-body--lobby osada-coop-lobby")
        root.appendChild(body)
        if (!resumeSelection) body.appendChild(roomStrip(model))
        model.message?.let {
            body.appendChild(
                MultiplayerScreen.text(it).apply { className = "osada-mp-alert osada-mp-alert--info" },
            )
        }
        body.appendChild(battleCard(model))
        if (!resumeSelection) {
            body.appendChild(cardsGrid(model))
            body.appendChild(editorCard(model))
        }
        root.appendChild(footer(model))
    }

    private fun roomStrip(model: CoopLobbyModel): HTMLElement {
        val room = MultiplayerScreen.panel("osada-mp-room-strip")
        room.appendChild(
            MultiplayerScreen
                .text(I18n.t("multiplayer.room_code.label").uppercase())
                .apply { className = "osada-mp-room-label" },
        )
        room.appendChild(MultiplayerScreen.text(model.roomCode ?: "—").apply { className = "osada-mp-room-code" })
        room.appendChild(
            MultiplayerScreen
                .text(I18n.t("multiplayer.coop.room_code.help", mapOf("min" to model.minimum, "max" to model.capacity)))
                .apply { className = "osada-mp-room-help" },
        )
        return room
    }

    private fun battleCard(model: CoopLobbyModel): HTMLElement {
        val campaignMode = model.selectedCampaignFile != null
        if (model.isHost && resumeSelection) return resumeCard(model, campaignMode)
        val card = MultiplayerScreen.panel("osada-mp-card osada-mp-scenario-card")
        card.appendChild(
            MultiplayerScreen.cardHeading(
                I18n.t("multiplayer.coop.battle.title"),
                I18n.t(if (model.isHost) "multiplayer.coop.battle.help.host" else "multiplayer.coop.battle.help.guest"),
            ),
        )
        if (model.isHost) {
            card.appendChild(contentTabs(model, campaignMode))
            card.appendChild(
                if (campaignMode) {
                    campaignControls(model)
                } else {
                    MultiplayerScreen.scenarioSelect(
                        model.scenarios,
                        model.selectedScenarioFile,
                        model.onScenarioSelected,
                    )
                },
            )
        } else {
            val title =
                model.selectedCampaignFile?.let { file ->
                    model.campaigns.firstOrNull { it.file == file }?.title ?: file
                }
                    ?: model.selectedScenarioName
            card.appendChild(
                MultiplayerScreen
                    .text(title ?: I18n.t("multiplayer.scenario.waiting"))
                    .apply { className = "osada-mp-scenario-name" },
            )
        }
        card.appendChild(
            MultiplayerScreen
                .text(
                    I18n.t(
                        if (campaignMode) "multiplayer.coop.campaign.note" else "multiplayer.coop.battle.campaign_note",
                    ),
                ).apply { className = "osada-mp-card-copy" },
        )
        RulesWindow.installButton(card, RulesetSelection.Surface.MULTIPLAYER, readOnlyWindow = !model.isHost)
        if (!campaignMode) {
            card.appendChild(
                MultiplayerScreen.sideSelect(model.sides, model.selectedPlayerId, model.isHost, model.onSideSelected),
            )
        }
        CoopScenarioAdvice.append(card, model.selectedScenarioFile, model.selectedPlayerId, campaignMode)
        return card
    }

    private fun resumeCard(
        model: CoopLobbyModel,
        campaignMode: Boolean,
    ): HTMLElement {
        val card = MultiplayerScreen.panel("osada-mp-card osada-mp-scenario-card")
        card.appendChild(contentTabs(model, campaignMode))
        CoopRecoveryScreen.appendPicker(
            card,
            { model.draft.name },
            true,
            { _, checkpoint -> model.onRestore(checkpoint) },
            resumeSession,
        )
        return card
    }

    /** Choose new content or a saved session to continue. */
    private fun contentTabs(
        model: CoopLobbyModel,
        campaignMode: Boolean,
    ): HTMLElement {
        val row = MultiplayerScreen.panel("osada-coop-filter")
        val kinds = listOf(false to "multiplayer.coop.content.battle", true to "multiplayer.coop.content.campaign")
        kinds.forEach { (isCampaign, key) ->
            val selected = !resumeSelection && campaignMode == isCampaign
            val tab = MultiplayerScreen.button(I18n.t(key), quiet = !selected)
            tab.id = if (isCampaign) "coopContentCampaign" else "coopContentBattle"
            tab.setAttribute("aria-pressed", selected.toString())
            tab.onclick = {
                resumeSelection = false
                if (isCampaign && !campaignMode) {
                    model.campaigns.firstOrNull()?.let { model.onCampaignSelected(it.file, model.campaignDifficulty) }
                } else if (!isCampaign && campaignMode) {
                    // Back to a single battle: the picker below asks for one; nothing is chosen until it does.
                    showBattlePicker(model)
                } else {
                    show(model)
                }
            }
            row.appendChild(tab)
        }
        val resume = MultiplayerScreen.button(I18n.t("multiplayer.coop.content.resume"), quiet = !resumeSelection)
        resume.id = "coopContentResume"
        resume.setAttribute("aria-pressed", resumeSelection.toString())
        resume.onclick = {
            resumeSelection = true
            show(model)
        }
        row.appendChild(resume)
        return row
    }

    private fun showBattlePicker(model: CoopLobbyModel) {
        model.scenarios.firstOrNull()?.let { model.onScenarioSelected(it.file) }
    }

    private fun campaignControls(model: CoopLobbyModel): HTMLElement {
        val box = MultiplayerScreen.panel("osada-coop-campaign")
        val select = document.createElement("select") as HTMLSelectElement
        select.className = "osada-mp-input osada-mp-scenario-select"
        select.id = "coopCampaignSelect"
        select.setAttribute("aria-label", I18n.t("multiplayer.coop.campaign.pick"))
        model.campaigns.forEach { campaign ->
            val option = document.createElement("option") as org.w3c.dom.HTMLOptionElement
            option.value = campaign.file
            option.textContent =
                I18n.t(
                    "multiplayer.coop.campaign.entry",
                    mapOf("title" to campaign.title, "count" to campaign.scenarios),
                )
            option.selected = campaign.file == model.selectedCampaignFile
            select.appendChild(option)
        }
        box.appendChild(select)
        val levels = document.createElement("select") as HTMLSelectElement
        levels.className = "osada-mp-input osada-coop-era"
        levels.id = "coopDifficultySelect"
        levels.setAttribute("aria-label", I18n.t("multiplayer.coop.campaign.difficulty"))
        listOf("historical", "tactical", "operational").forEachIndexed { index, key ->
            val option = document.createElement("option") as org.w3c.dom.HTMLOptionElement
            option.value = index.toString()
            option.textContent = I18n.t("campaign.difficulty.$key.label")
            option.selected = index == model.campaignDifficulty
            levels.appendChild(option)
        }
        // A server acknowledgement may not have rebuilt the model between two quick edits.
        // Both handlers submit the current pair of controls, not the last acknowledged values.
        select.onchange = { model.onCampaignSelected(select.value, levels.value.toIntOrNull() ?: 0) }
        levels.onchange = { model.onCampaignSelected(select.value, levels.value.toIntOrNull() ?: 0) }
        box.appendChild(levels)
        return box
    }

    // -- Commander cards -----------------------------------------------------------------------

    private fun cardsGrid(model: CoopLobbyModel): HTMLElement {
        val card = MultiplayerScreen.panel("osada-mp-card osada-coop-roster")
        card.appendChild(
            MultiplayerScreen.cardHeading(
                I18n.t("multiplayer.coop.roster.title"),
                I18n.t("multiplayer.roster.count", mapOf("count" to model.cards.size, "max" to model.capacity)),
            ),
        )
        val grid = MultiplayerScreen.panel("osada-coop-cards")
        model.cards.forEachIndexed { index, c -> grid.appendChild(commanderCard(index, c, model)) }
        repeat((model.capacity - model.cards.size).coerceAtLeast(0)) {
            grid.appendChild(
                MultiplayerScreen.panel("osada-coop-card osada-coop-card--empty").apply {
                    appendChild(MultiplayerScreen.text(I18n.t("multiplayer.participant.waiting")))
                },
            )
        }
        card.appendChild(grid)
        return card
    }

    private fun commanderCard(
        index: Int,
        card: CoopCardModel,
        model: CoopLobbyModel,
    ): HTMLElement {
        val el = MultiplayerScreen.panel("osada-coop-card" + if (card.isSelf) " osada-coop-card--self" else "")
        val profile = if (card.isSelf) model.draft else card.profile
        el.appendChild(seatChip(index, card, profile, model))
        el.appendChild(portraitFrame(profile?.portrait, profile?.name ?: card.displayName))
        el.appendChild(
            MultiplayerScreen
                .text(profile?.name?.ifBlank { null } ?: card.displayName)
                .apply { className = "osada-mp-player-name" },
        )
        val role =
            if (card.isHost) {
                card.displayName + I18n.t("multiplayer.participant.host")
            } else {
                card.displayName + " · " + I18n.t("multiplayer.participant.guest")
            }
        el.appendChild(MultiplayerScreen.text(role).apply { className = "osada-mp-player-role" })
        el.appendChild(abilityChips(profile?.abilityIds.orEmpty()))
        el.appendChild(readinessLabel(card))
        return el
    }

    /**
     * The seat chip in the card's corner. On your own card it is a button that opens the choice of
     * a map mark -- a drawn symbol instead of the seat number.
     */
    private fun seatChip(
        index: Int,
        card: CoopCardModel,
        profile: CoopCommanderProfile?,
        model: CoopLobbyModel,
    ): HTMLElement {
        val symbol = profile?.symbol.orEmpty()
        if (!card.isSelf) {
            return MultiplayerScreen.panel("osada-coop-seat").also { CoopHud.fillMark(it, index, symbol) }
        }
        val chip = CoopHud.button("")
        chip.className = "osada-coop-seat osada-coop-seat--button"
        CoopHud.fillMark(chip, index, symbol)
        chip.title = I18n.t("multiplayer.coop.symbol.change")
        chip.setAttribute("aria-label", chip.title)
        chip.setAttribute("aria-expanded", pickingSymbol.toString())
        chip.onclick = {
            pickingSymbol = !pickingSymbol
            rebuildFromLastModel()
        }
        val wrap = MultiplayerScreen.panel("osada-coop-seat-wrap")
        wrap.appendChild(chip)
        if (pickingSymbol) {
            wrap.appendChild(
                CoopHud.symbolPicker(index, symbol) {
                    pickingSymbol = false
                    model.onSymbolChosen(it)
                },
            )
        }
        return wrap
    }

    private fun readinessLabel(card: CoopCardModel): HTMLElement {
        val stateKey =
            when {
                !card.connected -> "multiplayer.connection.reconnecting"
                card.ready -> "multiplayer.participant.ready"
                else -> "multiplayer.participant.not_ready"
            }
        val ready = card.ready && card.connected
        return MultiplayerScreen.text(I18n.t(stateKey).uppercase()).apply {
            className = "osada-mp-ready-state" + if (ready) " osada-mp-ready-state--ready" else ""
        }
    }

    private fun abilityChips(abilityIds: List<String>): HTMLElement {
        val chips = MultiplayerScreen.panel("osada-coop-chips")
        if (abilityIds.isEmpty()) {
            chips.appendChild(
                MultiplayerScreen.text(I18n.t("multiplayer.coop.abilities.none")).apply {
                    className =
                        "osada-coop-chip osada-coop-chip--empty"
                },
            )
        }
        abilityIds.mapNotNull(CoopAbilityCatalogue::byId).forEach { ability ->
            val line = HeroDisplay.trait(ability.leader, "")
            chips.appendChild(
                MultiplayerScreen.text(line.title).apply {
                    className = "osada-coop-chip"
                    title = line.effect
                },
            )
        }
        return chips
    }

    // -- Own card editor -----------------------------------------------------------------------

    private fun editorCard(model: CoopLobbyModel): HTMLElement {
        val card = MultiplayerScreen.panel("osada-mp-card osada-coop-editor")
        card.appendChild(
            MultiplayerScreen.cardHeading(
                I18n.t("multiplayer.coop.editor.title"),
                I18n.t("multiplayer.coop.editor.help"),
            ),
        )
        val name = MultiplayerScreen.input(I18n.t("multiplayer.coop.name.label"), model.draft.name)
        name.id = NAME_ID
        name.maxLength = CoopCommander.MAX_NAME_LENGTH
        name.setAttribute("aria-label", I18n.t("multiplayer.coop.name.label"))
        name.oninput = { model.onNameChanged(name.value) }
        card.appendChild(MultiplayerScreen.field(I18n.t("multiplayer.coop.name.label"), name))
        card.appendChild(portraitPicker(model))
        card.appendChild(abilityPicker(model))
        return card
    }

    private fun portraitPicker(model: CoopLobbyModel): HTMLElement {
        val box = MultiplayerScreen.panel("osada-coop-portraits")
        box.appendChild(
            MultiplayerScreen
                .text(I18n.t("multiplayer.coop.portrait.title"))
                .apply { className = "osada-mp-field-label" },
        )
        val tools = MultiplayerScreen.panel("osada-coop-portrait-tools")
        tools.appendChild(
            portraitFrame(model.draft.portrait, model.draft.name).apply {
                classList.add("osada-coop-portrait--preview")
            },
        )
        val reroll = MultiplayerScreen.button(I18n.t("multiplayer.coop.portrait.generate"))
        reroll.id = "coopPortraitReroll"
        reroll.classList.add("osada-coop-reroll")
        reroll.title = I18n.t("multiplayer.coop.portrait.generate.help")
        reroll.insertBefore(curvedArrowIcon(), reroll.firstChild)
        reroll.onclick = {
            model.onReroll(eraFilter.takeIf(String::isNotEmpty), femaleFor(model))
        }
        tools.appendChild(reroll)
        tools.appendChild(genderToggle(model))
        tools.appendChild(eraSelect(model))
        box.appendChild(tools)

        val grid = MultiplayerScreen.panel("osada-coop-portrait-grid")
        grid.setAttribute("role", "listbox")
        grid.setAttribute("aria-label", I18n.t("multiplayer.coop.portrait.title"))
        HeroPortraitArt.ALL
            .filter { eraFilter.isEmpty() || it.era.name == eraFilter }
            .forEach { art ->
                val choice = CoopPortrait.legendary(art.id) ?: return@forEach
                val selected = model.draft.portrait.artId == art.id
                val option = MultiplayerScreen.button("")
                option.id = "coopPortrait-${art.id}"
                option.classList.add("osada-coop-portrait-option")
                option.setAttribute("role", "option")
                option.setAttribute("aria-selected", selected.toString())
                option.setAttribute("aria-label", I18n.t("multiplayer.coop.portrait.option", mapOf("id" to art.id)))
                if (selected) option.classList.add("is-selected")
                PortraitRenderer.render(option, emptyList(), 0, artPath = HeroPortraitArt.pathFor(art.id))
                option.onclick = { model.onPortraitChosen(choice) }
                grid.appendChild(option)
            }
        box.appendChild(grid)
        return box
    }

    private fun femaleFor(model: CoopLobbyModel): Boolean =
        generatedFemale ?: model.draft.portrait.layerIds
            .any { it.startsWith("face_female_") }

    private fun genderToggle(model: CoopLobbyModel): HTMLElement {
        val female = femaleFor(model)
        val key = "multiplayer.coop.portrait.gender." + if (female) "female" else "male"
        val toggle = MultiplayerScreen.button(if (female) "♀" else "♂")
        toggle.id = "coopPortraitGender"
        toggle.classList.add("osada-coop-gender")
        toggle.setAttribute("aria-label", I18n.t(key))
        toggle.setAttribute("aria-pressed", female.toString())
        toggle.title = I18n.t(key)
        toggle.onclick = {
            generatedFemale = !female
            show(model)
        }
        return toggle
    }

    private fun eraSelect(model: CoopLobbyModel): HTMLElement {
        val select = document.createElement("select") as HTMLSelectElement
        select.className = "osada-mp-input osada-coop-era"
        select.id = "coopEraFilter"
        select.setAttribute("aria-label", I18n.t("multiplayer.coop.portrait.era"))
        val all = document.createElement("option") as org.w3c.dom.HTMLOptionElement
        all.value = ""
        all.textContent = I18n.t("multiplayer.coop.portrait.era.all")
        select.appendChild(all)
        HeroPortraitArt.Era.entries.forEach { era ->
            val option = document.createElement("option") as org.w3c.dom.HTMLOptionElement
            option.value = era.name
            option.textContent = I18n.tOrNull("multiplayer.coop.portrait.era.${era.name.lowercase()}") ?: era.name
            option.selected = era.name == eraFilter
            select.appendChild(option)
        }
        select.onchange = {
            eraFilter = select.value
            show(model)
        }
        return select
    }

    private fun abilityPicker(model: CoopLobbyModel): HTMLElement {
        val box = MultiplayerScreen.panel("osada-coop-abilities")
        val chosen = model.draft.abilityIds
        box.appendChild(
            MultiplayerScreen
                .text(
                    I18n.t(
                        "multiplayer.coop.abilities.title",
                        mapOf("used" to chosen.size, "cap" to model.slotCap),
                    ),
                ).apply { className = "osada-mp-field-label" },
        )
        box.appendChild(
            MultiplayerScreen
                .text(I18n.t("multiplayer.coop.abilities.help"))
                .apply { className = "osada-mp-card-copy" },
        )
        box.appendChild(suggestionFilter(model))
        val list = MultiplayerScreen.panel("osada-coop-ability-list")
        CoopAbilityCatalogue.ALL
            .filter { profileFilter == null || profileFilter in it.suggestedFor }
            .forEach { ability -> list.appendChild(abilityToggle(ability, chosen, model)) }
        box.appendChild(list)
        model.loadoutIssues.forEach { issue ->
            box.appendChild(
                MultiplayerScreen.text(issue).apply { className = "osada-mp-alert osada-mp-alert--error" },
            )
        }
        if (chosen.size < model.slotCap) {
            box.appendChild(
                MultiplayerScreen
                    .text(I18n.t("multiplayer.coop.abilities.empty_slot"))
                    .apply { className = "osada-mp-card-copy" },
            )
        }
        return box
    }

    private fun suggestionFilter(model: CoopLobbyModel): HTMLElement {
        val row = MultiplayerScreen.panel("osada-coop-filter")
        val entries: List<CoopProfile?> = listOf<CoopProfile?>(null) + CoopProfile.entries
        entries.forEach { profile ->
            val label =
                if (profile == null) {
                    I18n.t("multiplayer.coop.profile.all")
                } else {
                    I18n.t("multiplayer.coop.profile.${profile.name.lowercase()}")
                }
            val chip = MultiplayerScreen.button(label, quiet = profileFilter != profile)
            chip.id = "coopFilter-${profile?.name ?: "ALL"}"
            chip.setAttribute("aria-pressed", (profileFilter == profile).toString())
            chip.onclick = {
                profileFilter = profile
                show(model)
            }
            row.appendChild(chip)
        }
        return row
    }

    private fun abilityToggle(
        ability: CoopAbility,
        chosen: List<String>,
        model: CoopLobbyModel,
    ): HTMLElement {
        val line = HeroDisplay.trait(ability.leader, "")
        val selected = ability.id in chosen
        val full = !selected && chosen.size >= model.slotCap
        val button = document.createElement("button") as HTMLButtonElement
        button.type = "button"
        button.id = "coopAbility-${ability.id}"
        button.className = "osada-coop-ability" + if (selected) " is-selected" else ""
        button.setAttribute("aria-pressed", selected.toString())
        button.disabled = full
        button.appendChild(MultiplayerScreen.text(line.title).apply { className = "osada-coop-ability-title" })
        button.appendChild(MultiplayerScreen.text(line.effect).apply { className = "osada-coop-ability-effect" })
        button.appendChild(
            MultiplayerScreen.text(line.activation).apply { className = "osada-coop-ability-when" },
        )
        if (ability.tier == CoopTier.RESTRICTED) {
            button.appendChild(
                MultiplayerScreen
                    .text(I18n.t("multiplayer.coop.abilities.restricted"))
                    .apply { className = "osada-coop-ability-tag" },
            )
        }
        button.onclick = { model.onAbilityToggled(ability.id) }
        return button
    }

    // -- Pieces --------------------------------------------------------------------------------

    private fun portraitFrame(
        portrait: CoopPortrait?,
        name: String,
    ): HTMLElement {
        val frame = MultiplayerScreen.panel("osada-coop-portrait")
        frame.textContent =
            name
                .split(Regex("\\s+"))
                .filter(String::isNotBlank)
                .take(2)
                .joinToString("") { it.take(1).uppercase() }
        frame.setAttribute("role", "img")
        frame.setAttribute("aria-label", I18n.t("multiplayer.coop.portrait.alt", mapOf("name" to name)))
        if (portrait != null && portrait.isValid()) {
            PortraitRenderer.render(
                frame,
                PortraitComposerV2.pathsFor(portrait.layerIds),
                portrait.seed,
                artPath = HeroPortraitArt.pathFor(portrait.artId),
            )
        }
        return frame
    }

    /** A curved arrow, drawn inline; the button's text label carries the meaning for assistive tech. */
    private fun curvedArrowIcon(): HTMLElement {
        val span = document.createElement("span") as HTMLElement
        span.className = "osada-coop-reroll-icon"
        span.setAttribute("aria-hidden", "true")
        span.innerHTML =
            "<svg viewBox=\"0 0 24 24\" width=\"18\" height=\"18\" fill=\"none\" stroke=\"currentColor\" " +
            "stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
            "<path d=\"M20 12a8 8 0 1 1-2.6-5.9\"/><path d=\"M20 4v5h-5\"/></svg>"
        return span
    }

    private fun footer(model: CoopLobbyModel): HTMLElement {
        val footer = MultiplayerScreen.panel("osada-mp-footer")
        val ready =
            MultiplayerScreen.button(
                I18n.t(if (model.selfReady) "multiplayer.ready.cancel.label" else "multiplayer.ready.label"),
            )
        ready.disabled = resumeSelection || model.loadoutIssues.isNotEmpty() || !model.contentChosen
        ready.onclick = { model.onToggleReady() }
        if (!resumeSelection) footer.appendChild(ready)
        if (model.isHost) {
            val start = MultiplayerScreen.button(I18n.t("multiplayer.start.label"), primary = true)
            start.disabled = resumeSelection || !model.startEnabled
            start.onclick = { model.onStart() }
            if (!resumeSelection) footer.appendChild(start)
        }
        val leave = MultiplayerScreen.button(I18n.t("multiplayer.leave.label"), quiet = true)
        leave.onclick = { model.onLeave() }
        footer.appendChild(leave)
        return footer
    }
}
