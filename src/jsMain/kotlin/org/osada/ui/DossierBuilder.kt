package org.osada.ui

import org.osada.GameHolder
import org.osada.getCampaignPlayer
import org.osada.hero.HeroCampaign
import org.osada.i18n.I18n
import org.osada.model.GameUnit
import org.osada.outcomeNames
import org.osada.scenario.Campaign
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent
import kotlin.random.Random

/**
 * Builds the campaign dossier (destroyed/lost unit tallies per class, awarded medals) and
 * the campaign-end screen, plus a debug dossier simulator. Extracted from the former
 * `UIBuilder` god-object; reads the equipment-class glyphs from the [UIBuilder] facade.
 *
 * Redesigned (OSADA): the previous version rendered a `<table>` from an `insertTemplate` HTML
 * shell and loaded per-country medal art (`resources/ui/dossier/{country}_{outcome}.png`) — art
 * that exists for only the original 8 PM countries. None of the ~90 countries brought in by the
 * OG campaign imports have it, so their dossier awards silently rendered as broken images. This
 * version builds its DOM directly (matching every other OSADA panel) and draws medals with CSS,
 * so every campaign — imported or original — renders identically.
 */
internal object DossierBuilder {
    private const val SIMULATED_LOSS_PROBABILITY = 0.5

    fun simulateDossier(): dynamic {
        val game = gameRef()
        val player = game?.scenario?.map?.currentPlayer ?: return null
        val units = game.scenario?.map?.getUnits() ?: emptyArray<GameUnit>()
        for (unit in units) {
            if (Random.nextDouble() > SIMULATED_LOSS_PROBABILITY) player.addDestroyedUnitToDossier(unit)
        }
        val campaignData = game.campaign?.getCampaignData()?.unsafeCast<Array<dynamic>>()
        campaignData?.forEachIndexed { index, _ ->
            val outcome =
                when (Random.nextInt(3)) {
                    0 -> "victory"
                    1 -> "tactical"
                    else -> "briliant"
                }
            val name = game.campaign?.getScenarioNameFromId(index) ?: ""
            player.addOutcomeToDossier(outcome, name)
        }
        return player.dossier
    }

    fun showCampaignEnd(
        outcome: String,
        text: String,
        callback: (() -> Unit)?,
    ): Boolean {
        // A completed campaign enshrines its notable commanders in the cross-campaign Hall of Fame (§14.6).
        harvestHallOfFame()
        val dossier = if (showDossier(false, callback)) byId("dossier") else null
        dossier ?: return false
        val banner = addTag(dossier, "div")
        banner.className = "osada-dsr-endbanner osada-dsr-endbanner--${if (outcome == "lose") "defeat" else "victory"}"
        val title = addTag(banner, "div")
        title.className = "osada-dsr-endbanner__title"
        title.textContent =
            I18n.t(if (outcome == "lose") "dossier.defeat.title" else "dossier.victory.title")
        val subtitle = addTag(banner, "div")
        subtitle.className = "osada-dsr-endbanner__subtitle"
        subtitle.textContent = I18n.t("dossier.finished")
        val message = addTag(banner, "div")
        message.className = "osada-dsr-endbanner__message"
        message.innerHTML = text
        // Banner goes first so it reads before the casualty/medal detail below it.
        dossier.insertBefore(banner, dossier.firstChild)
        return true
    }

    /** [docked] no longer changes layout (both modes share one floating-window treatment now —
     *  see class doc) but is kept so callers don't need updating; a close button always renders,
     *  where before "docked" mode (opened from the Turn Report's Dossier button) had none at all
     *  and could only be dismissed by reopening the Turn Report or loading a new scenario. */
    @Suppress("UnusedParameter")
    fun showDossier(
        docked: Boolean,
        callback: (() -> Unit)? = null,
    ): Boolean {
        // Everything here goes through the typed Game, never the dynamic `game`: Kotlin/JS mangles
        // property backing fields (name -> name_1), so `game.campaign.name` via `dynamic` is
        // undefined — AND `getCampaignPlayer` is an EXTENSION function (GameEndgame.kt), which
        // compiles to a top-level function rather than a method on the Game object, so calling it
        // dynamically threw "game.getCampaignPlayer is not a function" and crashed the campaign-end
        // screen on every defeat.
        val typedGame = GameHolder.instance
        val campaign = typedGame?.campaign
        val dossier = byId("dossier")
        if (campaign == null || dossier == null) return false
        clearTag(dossier)
        dossier.className = "dossier osada-dsr"
        dossier.style.display = "flex"
        val player = typedGame.getCampaignPlayer()
        return if (player == null) {
            false
        } else {
            buildDossierContent(dossier, campaign, player, callback)
            true
        }
    }

    private fun buildDossierContent(
        dossier: HTMLElement,
        campaign: Campaign?,
        player: dynamic,
        callback: (() -> Unit)?,
    ) {
        val dossierData = player.dossier
        if (dossierData == null || dossierData.units == js("undefined")) player.initDossier()

        buildDossierHeader(dossier, campaign, player, callback)

        val body = addTag(dossier, "div")
        body.className = "osada-dsr-body"
        buildCasualtiesSection(body, dossierData)
        buildAwardsSection(body, dossierData)
    }

    private fun buildDossierHeader(
        dossier: HTMLElement,
        campaign: Campaign?,
        player: dynamic,
        callback: (() -> Unit)?,
    ) {
        val header = addTag(dossier, "div")
        header.className = "osada-dsr-header"
        val titleBlock = addTag(header, "div")
        titleBlock.className = "osada-dsr-titleblock"
        val title = addTag(titleBlock, "div")
        title.className = "osada-dsr-title"
        title.textContent = "${campaign?.name} — ${player.getCountryName()}"
        val sub = addTag(titleBlock, "div")
        sub.className = "osada-dsr-sub"
        sub.textContent = I18n.t("dossier.subtitle")
        val closeButton = addTag(header, "span")
        closeButton.id = "dossierCloseBut"
        closeButton.className = "osada-ico osada-ico--close osada-dsr-close"
        closeButton.title = I18n.t("common.close.label")
        closeButton.onclick = { _: MouseEvent ->
            closeDossier()
            callback?.invoke()
        }
    }

    // ---- Casualties: one card per unit class, replacing the old fixed-column <table> (which
    // didn't scale past the original 8-class efiles either) with a wrapping grid. ----
    private fun buildCasualtiesSection(
        body: HTMLElement,
        dossierData: dynamic,
    ) {
        val casSection = addTag(body, "div")
        casSection.className = "osada-dsr-section"
        val casTitle = addTag(casSection, "div")
        casTitle.className = "osada-dsr-section-title"
        casTitle.textContent = I18n.t("dossier.casualties.title")
        val grid = addTag(casSection, "div")
        grid.className = "osada-dsr-cas-grid"
        UIBuilder.eqClassButtons.forEach { entry ->
            val uclass = entry.key
            val glyph = entry.value.first
            val classTitle = entry.value.second
            // Sum the tab's own class AND the classes merged into it (UIBuilder.eqClassTabGroups):
            // the grid has 8 cards for 21 classes, so without this, losses of merged classes
            // (fortifications, transports, level bombers) were tallied but never displayed.
            val tallied = UIBuilder.classesForTab(uclass.toIntOrNull() ?: 0).map { it.value.toString() }

            fun sum(bucket: dynamic) = tallied.sumOf { bucket[it] as? Int ?: 0 }
            val killed = sum(dossierData.units.killed)
            val captured = sum(dossierData.units.captured)
            val lostCore = sum(dossierData.units.lostcore)
            val lostAux = sum(dossierData.units.lostaux)
            val card = addTag(grid, "div")
            card.className = "osada-dsr-cas-card"
            card.title = classTitle
            val icon = addTag(card, "div")
            icon.className = "osada-dsr-cas-icon"
            icon.textContent = glyph
            val stats = addTag(card, "div")
            stats.className = "osada-dsr-cas-stats"

            fun stat(
                label: String,
                value: Int,
            ) {
                val row = addTag(stats, "div")
                row.className = "osada-dsr-cas-stat"
                row.innerHTML = "<span>$label</span><b>$value</b>"
            }
            // `captured` is a subset of `killed`, so show the destroyed remainder separately —
            // "Destroyed 12 / Surrendered 4" reads as the two distinct tactics that produced them.
            // The Surrendered row is omitted entirely when none were taken, to avoid a grid full
            // of zeroes in scenarios where encirclement never happened.
            stat(I18n.t("dossier.cas.enemy_destroyed"), killed - captured)
            if (captured > 0) stat(I18n.t("dossier.cas.enemy_surrendered"), captured)
            stat(I18n.t("dossier.cas.core_lost"), lostCore)
            stat(I18n.t("dossier.cas.aux_lost"), lostAux)
        }
    }

    // ---- Campaign Record: CSS-drawn outcome badges (gold/silver/bronze by outcome tier)
    // replace the old per-country PNGs (resources/ui/dossier/{country}_{outcome}.png), which
    // only exist for the original 8 PM countries — every OG-imported campaign showed a
    // broken image here otherwise. ----
    private fun buildAwardsSection(
        body: HTMLElement,
        dossierData: dynamic,
    ) {
        val medSection = addTag(body, "div")
        medSection.className = "osada-dsr-section"
        val medTitle = addTag(medSection, "div")
        medTitle.className = "osada-dsr-section-title"
        medTitle.textContent = I18n.t("dossier.awards.title")
        medTitle.title = I18n.t("dossier.awards.help")
        var hasResults = false
        val outcomeOrder = listOf("briliant", "victory", "tactical", "lose")
        val medalMod = mapOf("briliant" to "gold", "victory" to "silver", "tactical" to "bronze", "lose" to "none")
        for (outcome in outcomeOrder) {
            val list = dossierData.outcomes[outcome] as? Array<dynamic>
            if (list.isNullOrEmpty()) continue
            hasResults = true
            val row = addTag(medSection, "div")
            row.className = "osada-dsr-medal-row"
            val badge = addTag(row, "div")
            badge.className = "osada-dsr-medal osada-dsr-medal--${medalMod[outcome]}"
            val count = addTag(badge, "span")
            count.className = "osada-dsr-medal__count"
            count.textContent = list.size.toString()
            val info = addTag(row, "div")
            info.className = "osada-dsr-medal-info"
            val label = addTag(info, "div")
            label.className = "osada-dsr-medal-label"
            label.textContent = outcomeNames[outcome] ?: outcome
            val scenarios = addTag(info, "div")
            scenarios.className = "osada-dsr-medal-scenarios"
            scenarios.textContent = (0 until list.size).joinToString(" · ") { i -> list[i] as? String ?: "" }
        }
        if (!hasResults) {
            val empty = addTag(medSection, "div")
            empty.className = "osada-dsr-empty"
            empty.textContent =
                "No scenario results recorded yet. " +
                "Hero decorations are shown in each commander's dossier."
        }
    }

    fun closeDossier() {
        byId("dossier")?.style?.display = "none"
    }

    /** Enshrines this campaign's renowned, authored, and fallen commanders in the Hall of Fame (§14.6). */
    private fun harvestHallOfFame() {
        val campaignName = GameHolder.instance?.campaign?.name ?: return
        val entries =
            HeroCampaign.commanders().filter { it.notable }.map {
                HallOfFame.Entry(
                    name = it.name,
                    rank = it.rank,
                    renown = it.renown,
                    potential = it.potential,
                    status = it.statusLabel,
                    campaign = campaignName,
                )
            }
        HallOfFame.harvest(entries)
    }
}
