package org.osada.ui

import org.osada.Game
import org.osada.current
import org.osada.difficultyModifiers
import org.osada.i18n.I18n
import org.osada.save.CampaignRunMetadata
import org.osada.scenario.Campaign
import kotlin.math.roundToInt

/**
 * [StartMenuBuilder]'s campaign-screen data/display helpers: difficulty tuning, theater art,
 * prestige preview, saved-progress lookup, and the hidden-campaigns list. Split out purely to
 * keep [StartMenuCampaignScreen] within the project's function-count/class-size limits.
 */
internal object StartMenuCampaignData {
    const val DIFFICULTY_HISTORICAL = 0
    const val DIFFICULTY_TACTICAL = 1
    const val DIFFICULTY_OPERATIONAL = 2

    // ---- Hidden campaigns -------------------------------------------------------------------
    // These Kaiser-efile campaigns were imported "flipped to the Red side" (the player commands
    // the Bolshevik forces), but the underlying scenario TEXT/outcomes were authored for the
    // opposite (White/anti-Bolshevik) campaign path and were not rewritten — so a player winning
    // early missions can still be handed later briefings written for the historical losing side
    // (e.g. "White Army marches forward" after a Red victory). Hiding from Campaign Selection
    // only (user request) until the path is actually reworked; the individual scenarios remain
    // playable, and honestly presented, from Scenario Selection (scenariolist.js is untouched).
    val hiddenCampaignFiles =
        setOf(
            "volarm.json", // The Defeat of Denikin
            "simpob.json", // Sim Pobedishi! - The Red East
            "acampdf2.json", // Czech Legion - Siberian Anabasis
            "polsov.json", // The Polish-Soviet War: The Red Advance
        )

    /** Derived from the SAME [difficultyModifiers] the campaign actually applies (Campaign.kt /
     *  ScenarioLoader.kt / Player.kt) — never hand-copied numbers that could drift from the rules. */
    fun difficultyHint(difficulty: Int): String {
        val mod = difficultyModifiers[difficulty]
        val isFullDifficulty =
            mod != null &&
                mod.startPrestige == 0.0 &&
                mod.turnPrestige == 0.0 &&
                mod.extraTurns == 1.0 &&
                mod.scoreCoef == 1.0
        return when {
            mod == null -> ""
            isFullDifficulty -> I18n.t("campaign.difficulty.full.description")
            else -> {
                // roundToInt(), not toInt(): floating-point subtraction (e.g. 1.2 - 1.0) lands on
                // 0.19999999999999996, and plain truncation turned +20% into a wrong "+19%".
                val startPct = (mod.startPrestige * 100).roundToInt()
                val turnPct = (mod.turnPrestige * 100).roundToInt()
                val turnsPct = ((mod.extraTurns - 1.0) * 100).roundToInt()
                val scorePct = ((1.0 - mod.scoreCoef) * 100).roundToInt()
                I18n.t(
                    "campaign.difficulty.modifiers.summary",
                    mapOf(
                        "startPct" to startPct,
                        "turnPct" to turnPct,
                        "turnsPct" to turnsPct,
                        "scorePct" to scorePct,
                    ),
                )
            }
        }
    }

    /**
     * Point the campaign screen's theater banner at that campaign's own key art, falling back to
     * the shared placeholder. Art lives at `resources/ui/theater/<campaign-file-stem>.jpg`
     * (e.g. volarm.json -> volarm.jpg).
     *
     * JPEG, not PNG: these are photographic 1920x640 banners, and lossless PNG cost 2.1 MB each
     * (47 MB for the 22 campaigns) — a visible stall on every click in the campaign list, since the
     * browser fetches a banner the first time its campaign is selected. At q88 they are 212 KB and
     * indistinguishable on screen (the box renders at 948x222, so even a retina pass has pixels to
     * spare). Source PNGs live outside the served tree, in art-src/theater-spare/.
     *
     * Layering and placeholder fallback live in [StartMenuListToolbar.applyTheaterArt], shared
     * with the scenario screen's own banner.
     */
    fun setTheaterArt(campaign: dynamic) {
        val stem = (campaign?.file as? String)?.removeSuffix(".json") ?: ""
        StartMenuListToolbar.applyTheaterArt(
            byId("smCampDossierHead"),
            if (stem.isBlank()) null else "resources/ui/theater/$stem.jpg",
        )
    }

    /** 0b: campaign-select prestige display. Uses the exact computation campaign start applies
     *  ([Campaign.computeStartPrestige]) so the shown number always matches what Start grants. */
    fun updateCampaignPrestigeDisplay() {
        val selected = byId("smCamp")?.asDynamic()?.selectedCampaign as? Int ?: return
        val campaign = StartMenuBuilder.campaignList().getOrNull(selected) ?: return
        val base = campaign.prestige as? Int ?: 0
        val difficulty = byId("smCamp")?.asDynamic()?.selectedDifficulty as? Int ?: DIFFICULTY_HISTORICAL
        byId("smCampPrestige")?.innerHTML = "<b>${I18n.t("campaign.start_prestige.label")}</b><br/>" +
            I18n.formatNumber(Campaign.computeStartPrestige(base, difficulty)) + "&nbsp;" + UIBuilder.currencyIcon
        updatePlayButtonLabel(campaign.file as? String)
    }

    /** The Play button resumes an existing run rather than silently restarting it (design doc
     *  save-recovery.md sec 2: "Selecting an In progress campaign resumes its run directly"). Its
     *  label reflects that so the button never claims "Start" while it is actually about to resume. */
    private fun updatePlayButtonLabel(file: String?) {
        val playBut = byId("smCPlayBut") ?: return
        val hasRun = file != null && campaignRunsByFile().containsKey(file)
        playBut.title = I18n.t(if (hasRun) "campaign.resume.help" else "campaign.start.help")
        playBut.setAttribute("data-label", I18n.t(if (hasRun) "campaign.resume.label" else "campaign.start.label"))
    }

    /** Every campaign run currently in the browser repository, keyed by campaign file --
     *  replaces the old single-slot `activeCampaignProgress()`: every campaign row can now show
     *  its own independent progress instead of at most one campaign ever being annotated. */
    fun campaignRunsByFile(): Map<String, CampaignRunMetadata> =
        Game.current
            ?.state
            ?.listCampaignRuns()
            ?.associateBy { it.campaignFile }
            ?: emptyMap()

    /**
     * Localized note text + tooltip for one campaign row, shared by the initial render
     * ([StartMenuCampaignScreen]) and the language-switch re-render ([LiveLocalization]) so the
     * wording is defined in exactly one place.
     *
     * A finished run reports what it finished AS: a campaign that ended in defeat says so rather
     * than borrowing "Completed", which in both shipped locales reads as an accomplishment
     * ("Пройдена"). The tooltip carries the last-played timestamp, which the roadmap's P0 row item
     * asks for but which had nowhere to go on a single-line note.
     */
    fun progressNoteText(
        metadata: CampaignRunMetadata,
        operations: Int?,
    ): Pair<String, String> {
        val current = metadata.campaignScenario + 1
        val text =
            when {
                metadata.completed && metadata.outcome == "lose" -> I18n.t("campaign.progress.defeated")
                metadata.completed -> I18n.t("campaign.progress.completed")
                operations != null ->
                    I18n.t("campaign.progress.full", mapOf("current" to current, "total" to operations))
                else -> I18n.t("campaign.progress.short", mapOf("current" to current))
            }
        return text to lastPlayedTooltip(metadata)
    }

    /** "<what the note means> — Last played 16.08.2026 14:03", or just the former when the run
     *  predates the index carrying a timestamp (`lastPlayedAt` 0.0 = unknown, never epoch 1970). */
    private fun lastPlayedTooltip(metadata: CampaignRunMetadata): String {
        val help = I18n.t("campaign.progress.help")
        if (metadata.lastPlayedAt <= 0.0) return help
        val stamp = I18n.formatDateTime(metadata.lastPlayedAt)
        return "$help — " + I18n.t("campaign.progress.last_played", mapOf("when" to stamp))
    }

    /** Lazily computes and toggles the collapsible campaign-path (victory/defeat tree). */
    fun toggleCampaignPath() {
        val path = byId("smCampPath") ?: return
        val body = byId("smCampPathBody") ?: return
        val open = path.classList.toggle("osadaCollapse--open")
        if (open && body.innerHTML.isBlank()) {
            val selectedCampaign = byId("smCamp")?.asDynamic()?.selectedCampaign as? Int
            if (selectedCampaign != null) {
                var campaignRef: Campaign? = null
                val campaign =
                    Campaign(selectedCampaign, DIFFICULTY_HISTORICAL) {
                        campaignRef?.let { body.innerHTML = it.getCampaignFlow() }
                    }
                campaignRef = campaign
            }
        }
    }

    fun collapsePath(path: org.w3c.dom.HTMLElement) {
        path.classList.remove("osadaCollapse--open")
        byId("smCampPathBody")?.innerHTML = ""
    }
}
