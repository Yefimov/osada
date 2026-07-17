package org.osada.ui

import org.osada.GameHolder
import org.osada.model.GameUnit
import org.osada.outcomeNames
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

    fun simulateDossier(): dynamic {
        val game = gameRef()
        val player = game?.scenario?.map?.currentPlayer ?: return null
        val units = game.scenario?.map?.getUnits() ?: emptyArray<GameUnit>()
        for (unit in units) {
            if (Random.nextDouble() > 0.5) player.addDestroyedUnitToDossier(unit)
        }
        val campaignData = game.campaign?.getCampaignData()?.unsafeCast<Array<dynamic>>()
        campaignData?.forEachIndexed { index, _ ->
            val outcome = when (Random.nextInt(3)) {
                0 -> "victory"
                1 -> "tactical"
                else -> "briliant"
            }
            val name = game.campaign?.getScenarioNameFromId(index) ?: ""
            player.addOutcomeToDossier(outcome, name)
        }
        return player.dossier
    }

    fun showCampaignEnd(outcome: String, text: String, callback: (() -> Unit)?): Boolean {
        if (!showDossier(false, callback)) return false
        val dossier = byId("dossier") ?: return false
        val banner = addTag(dossier, "div")
        banner.className = "osada-dsr-endbanner osada-dsr-endbanner--${if (outcome == "lose") "defeat" else "victory"}"
        val title = addTag(banner, "div")
        title.className = "osada-dsr-endbanner__title"
        title.textContent = if (outcome == "lose") "Defeat" else "Victory"
        val subtitle = addTag(banner, "div")
        subtitle.className = "osada-dsr-endbanner__subtitle"
        subtitle.textContent = "CAMPAIGN FINISHED"
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
    fun showDossier(docked: Boolean, callback: (() -> Unit)? = null): Boolean {
        val game = gameRef()
        if (game?.campaign == null) return false
        // Read campaign fields through the typed Game (not the dynamic `game`): Kotlin/JS mangles
        // property backing fields (name -> name_1), so `game.campaign.name` via `dynamic` is undefined.
        val campaign = GameHolder.instance?.campaign
        val dossier = byId("dossier") ?: return false
        clearTag(dossier)
        dossier.className = "dossier osada-dsr"
        dossier.style.display = "flex"
        val player = game.getCampaignPlayer() ?: return false
        val dossierData = player.dossier
        if (dossierData == null || dossierData.units == js("undefined")) player.initDossier()

        val header = addTag(dossier, "div")
        header.className = "osada-dsr-header"
        val titleBlock = addTag(header, "div")
        titleBlock.className = "osada-dsr-titleblock"
        val title = addTag(titleBlock, "div")
        title.className = "osada-dsr-title"
        title.textContent = "${campaign?.name} — ${player.getCountryName()}"
        val sub = addTag(titleBlock, "div")
        sub.className = "osada-dsr-sub"
        sub.textContent = "Campaign Dossier"
        val closeButton = addTag(header, "span")
        closeButton.id = "dossierCloseBut"
        closeButton.className = "osada-ico osada-ico--close osada-dsr-close"
        closeButton.title = "Close"
        closeButton.onclick = { _: MouseEvent ->
            closeDossier()
            callback?.invoke()
        }

        val body = addTag(dossier, "div")
        body.className = "osada-dsr-body"

        // ---- Casualties: one card per unit class, replacing the old fixed-column <table> (which
        // didn't scale past the original 8-class efiles either) with a wrapping grid. ----
        val casSection = addTag(body, "div")
        casSection.className = "osada-dsr-section"
        val casTitle = addTag(casSection, "div")
        casTitle.className = "osada-dsr-section-title"
        casTitle.textContent = "Casualties"
        val grid = addTag(casSection, "div")
        grid.className = "osada-dsr-cas-grid"
        UIBuilder.eqClassButtons.forEach { entry ->
            val uclass = entry.key
            val glyph = entry.value.first
            val classTitle = entry.value.second
            val killed = dossierData.units.killed[uclass] as? Int ?: 0
            val lostCore = dossierData.units.lostcore[uclass] as? Int ?: 0
            val lostAux = dossierData.units.lostaux[uclass] as? Int ?: 0
            val card = addTag(grid, "div")
            card.className = "osada-dsr-cas-card"
            card.title = classTitle
            val icon = addTag(card, "div")
            icon.className = "osada-dsr-cas-icon"
            icon.textContent = glyph
            val stats = addTag(card, "div")
            stats.className = "osada-dsr-cas-stats"
            fun stat(label: String, value: Int) {
                val row = addTag(stats, "div")
                row.className = "osada-dsr-cas-stat"
                row.innerHTML = "<span>$label</span><b>$value</b>"
            }
            stat("Inflicted", killed)
            stat("Lost (Core)", lostCore)
            stat("Lost (Aux)", lostAux)
        }

        // ---- Military Awards: CSS-drawn medal badges (gold/silver/bronze by outcome tier)
        // replace the old per-country PNGs (resources/ui/dossier/{country}_{outcome}.png), which
        // only exist for the original 8 PM countries — every OG-imported campaign showed a
        // broken image here otherwise. ----
        val medSection = addTag(body, "div")
        medSection.className = "osada-dsr-section"
        val medTitle = addTag(medSection, "div")
        medTitle.className = "osada-dsr-section-title"
        medTitle.textContent = "Military Awards"
        var hasMedals = false
        val outcomeOrder = listOf("briliant", "victory", "tactical", "lose")
        val medalMod = mapOf("briliant" to "gold", "victory" to "silver", "tactical" to "bronze", "lose" to "none")
        for (outcome in outcomeOrder) {
            val list = dossierData.outcomes[outcome] as? Array<dynamic> ?: continue
            if (list.isEmpty()) continue
            if (outcome != "lose") hasMedals = true
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
        if (!hasMedals) {
            val empty = addTag(medSection, "div")
            empty.className = "osada-dsr-empty"
            empty.textContent = "No medals awarded yet."
        }

        return true
    }

    fun closeDossier() {
        byId("dossier")?.style?.display = "none"
    }
}
