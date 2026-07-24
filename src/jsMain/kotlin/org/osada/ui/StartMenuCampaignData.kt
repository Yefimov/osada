package org.osada.ui

import kotlinx.browser.localStorage
import org.osada.VERSION
import org.osada.difficultyModifiers
import org.osada.i18n.I18n
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
     * The fallback needs no existence check: CSS paints background layers front-to-back, and a
     * layer whose URL 404s simply paints nothing — so listing the per-campaign image ABOVE the
     * placeholder yields the art when it exists and the placeholder when it doesn't. (The two
     * gradients must stay on top: they're the scrim the overlaid title/subtitle text reads against.)
     */
    fun setTheaterArt(campaign: dynamic) {
        val theater = byId("smCampDossierHead")?.query(".osadaTheater") as? org.w3c.dom.HTMLElement ?: return
        val stem = (campaign?.file as? String)?.removeSuffix(".json") ?: ""
        // 50% 0%: show the TOP of the art (user request) — matches .osadaTheater's own CSS rule.
        val layers =
            listOfNotNull(
                "linear-gradient(180deg, rgba(10,11,13,0) 42%, rgba(8,9,11,.94) 100%)",
                "linear-gradient(rgba(0,0,0,.10), rgba(0,0,0,.14))",
                if (stem.isNotBlank()) "url('resources/ui/theater/$stem.jpg') 50% 0% / cover no-repeat" else null,
                "url('resources/dossier_map_placeholder.png') 50% 0% / cover no-repeat",
            )
        theater.style.background = layers.joinToString(", ")
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
    }

    /** The in-progress campaign from localStorage: (campaign file, 0-based scenario index).
     *  This is the SAME single-slot campaign block the main menu's Continue restores from —
     *  there is no per-campaign progress storage, so at most one campaign can be annotated. */
    fun activeCampaignProgress(): Pair<String, Int>? {
        val majorVersion = VERSION.split(".").take(2).joinToString(".")
        val raw = localStorage.getItem("osada-campaign-$majorVersion")
        return if (raw == null) {
            null
        } else {
            try {
                val data = JSON.parse<dynamic>(raw)
                val file = data.file as? String
                // Campaign.setScenarioById treats this id as the index into the campaign's
                // scenario array, so it doubles as the operation ordinal.
                if (file == null) null else Pair(file, data.scenario as? Int ?: 0)
            } catch (_: Throwable) {
                null
            }
        }
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
