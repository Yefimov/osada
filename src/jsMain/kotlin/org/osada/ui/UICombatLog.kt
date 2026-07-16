package org.osada.ui

import kotlinx.browser.document
import org.osada.*
import org.osada.model.*
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.MouseEvent
import kotlin.math.roundToInt
import org.osada.CombatLog
import org.osada.uiSettings

/**
 * The "Turn Report" window (`#combatLog`): a proper window frame (header + summary stat tiles +
 * a scrollable, grouped event feed) instead of the legacy single flowing block of prose. Two
 * display modes share this same content, controlled by `fromStatusBar`:
 *  - true  (sidebar toggle / Esc): the FULL report, height budgeted up to the unit-info card.
 *  - false (automatic at turn start, [MenuController.showStatusExtension]): a short teaser
 *    capped at ~220px with an "expand to full report" banner — the same content, just clipped.
 */
object UICombatLog {

    init {
        val global: dynamic = js("window.UICombatLog = window.UICombatLog || {}")
        global.toggleCombatLog = { toggleCombatLog() }
    }

    private var outsideClickListener: ((Event) -> Unit)? = null

    fun toggleCombatLog(forceShow: Boolean = false, fromStatusBar: Boolean = false) {
        val game = gameRef()
        val currentPlayer = game?.scenario?.map?.currentPlayer ?: return
        if (currentPlayer.type != PlayerType.HUMAN_LOCAL && currentPlayer.type != PlayerType.AI_SCRIPTED) return
        if (forceShow || !isVisible("combatLog")) {
            showCombatLog(fromStatusBar)
            toggleButton("combatLogButton", true)
            attachOutsideClickToClose()
        } else {
            makeHidden("combatLog")
            toggleButton("combatLogButton", false)
            detachOutsideClickToClose()
        }
        UIBuilder.closeDossier()
    }

    /** Click-outside-to-close: previously the ONLY reliable way to close this window was the
     *  sidebar toggle button (Esc also worked, via MenuController.handleGlobalEscape, but wasn't
     *  discoverable) — the "g"/"@" header buttons looked like close controls but weren't, or
     *  closed it as an unrelated side effect of opening something else. "mousedown" (not "click")
     *  is used so the SAME click that opens the log can never immediately re-close it: that click
     *  event's mousedown phase already fired before this listener gets attached. */
    private fun attachOutsideClickToClose() {
        detachOutsideClickToClose()
        val listener: (Event) -> Unit = { event ->
            val target = event.target
            val insideLog = byId("combatLog")?.asDynamic()?.contains(target) == true
            val onToggleButton = byId("combatLogButton")?.asDynamic()?.contains(target) == true
            if (!insideLog && !onToggleButton) toggleCombatLog()
        }
        outsideClickListener = listener
        document.addEventListener("mousedown", listener)
    }

    private fun detachOutsideClickToClose() {
        outsideClickListener?.let { document.removeEventListener("mousedown", it) }
        outsideClickListener = null
    }

    /** Hard-close for non-toggle code paths (end turn, new scenario) that used to hide the log
     *  by calling `makeHidden("combatLog")` directly. Those must ALSO clear the button's selected
     *  state and detach the outside-click listener — otherwise a stray listener stays armed and
     *  fires toggleCombatLog() (reopening the now-invisible log) on the next unrelated click. */
    fun forceClose() {
        if (!isVisible("combatLog")) return
        makeHidden("combatLog")
        toggleButton("combatLogButton", false)
        detachOutsideClickToClose()
    }

    fun showCombatLog(fromStatusBar: Boolean) {
        val logContainer = byId("combatLog") ?: return
        val map = gameRef()?.scenario?.map?.map ?: return
        val gameMap = gameRef()?.scenario?.map as? GameMap ?: return
        clearTag(logContainer)
        logContainer.className = "osada-tr"

        val header = buildHeader(logContainer)
        val summary = buildSummaryTiles(logContainer, gameMap)
        val feed = addTag(logContainer, "div")
        feed.className = "osada-tr-feed"
        // Teaser-mode marker: attachGroup's header click reads it (group headers expand the
        // teaser to the full report instead of collapsing — a collapse inside a 220px clipped
        // strip isn't a meaningful interaction anyway; user request).
        if (fromStatusBar) logContainer.classList.remove("osada-tr--teaser")
        else logContainer.classList.add("osada-tr--teaser")
        val expandButton = if (!fromStatusBar) buildExtendButton(feed) else null

        val groups = listOf(
            buildCombatGroup(map),
            buildObjectiveGroup(map),
            buildResupplyGroup(),
            buildReinforceGroup(),
            buildLeadersGroup()
        )
        if (groups.none { it.count > 0 }) {
            val empty = addTag(feed, "div")
            empty.className = "osada-tr-empty"
            empty.textContent = "All quiet this turn — no combat, objectives, or reinforcements to report."
        } else {
            groups.forEach { if (it.count > 0) attachGroup(feed, it) }
        }

        // NOT makeVisible(): that sets inline display:inline, which — being an inline style —
        // would always beat the stylesheet's display:flex (the same trap #equipment/#unit-info
        // hit earlier). isVisible()/makeHidden() only care that the inline value is non-empty and
        // non-"none", so setting "flex" directly here keeps isVisible("combatLog") working.
        logContainer.style.display = "flex"
        byId("game")?.focus()
        val unitInfoPos = getPosition("unit-info")
        var height = ((unitInfoPos.top as Double - 30.0) / uiSettings.uiScale).roundToInt()
        // Degenerate guard: with #unit-info hidden (display:none — e.g. nothing selected, or the
        // Inspect pin off), getPosition reads 0 → height goes NEGATIVE → style.height = "-30px"
        // is invalid CSS the browser silently ignores, so the window kept whatever inline height
        // it had before — after a full-report open, that's the BIG height, making the next teaser
        // "open big". Fall back to a sane viewport fraction instead of a garbage input.
        if (height <= 0) height = (windowInnerHeight() * 0.6).roundToInt()
        // Natural content height: header/summary are fixed, but the feed is its own scroll
        // container, so its SCROLLHEIGHT (full content) is what tells us whether everything
        // already fits without scrolling — clientHeight would just report back whatever we're
        // about to set.
        val naturalHeight = header.offsetHeight + summary.offsetHeight + feed.scrollHeight
        if (height > naturalHeight) height = naturalHeight
        if (fromStatusBar) {
            logContainer.style.height = "${height}px"
        } else {
            // Quiet turns: if the whole report (minus the expand button itself, incl. its 8px
            // margin) already fits inside the teaser cap, there is nothing to expand — a
            // "Show Full Turn Report" button whose click visibly changes nothing reads as the
            // button being broken (user report). Drop it and show the complete report directly.
            if (expandButton != null && naturalHeight - expandButton.offsetHeight - 8 <= 220) {
                delTag(expandButton)
                val fitsHeight = header.offsetHeight + summary.offsetHeight + feed.scrollHeight
                if (height > fitsHeight) height = fitsHeight
            }
            if (height > 220) height = 220
            logContainer.style.height = "${height}px"
            feed.scrollTop = 0.0
        }
    }

    private fun buildExtendButton(container: HTMLElement): HTMLElement {
        val button = addTag(container, "div")
        button.className = "osada-tr-expand"
        button.innerHTML = "Show Full Turn Report<span class=\"osada-ico osada-ico--map osada-tr-expand__ico\"></span>"
        button.onclick = { _: MouseEvent -> showCombatLog(true) }
        return button
    }

    private fun buildHeader(container: HTMLElement): HTMLElement {
        val game = gameRef()
        val map = game?.scenario?.map as? GameMap
        val currentPlayer = map?.currentPlayer
        val header = addTag(container, "div")
        header.className = "osada-tr-header"

        val titleBlock = addTag(header, "div")
        titleBlock.className = "osada-tr-titleblock"
        val titleRow = addTag(titleBlock, "div")
        titleRow.className = "osada-tr-title-row"
        val title = addTag(titleRow, "div")
        title.className = "osada-tr-title"
        title.textContent = if (currentPlayer != null && map != null)
            "${currentPlayer.getCountryName()} — Turn ${map.turn} of ${map.maxTurns}"
        else "Turn Report"

        val sub = addTag(titleBlock, "div")
        sub.className = "osada-tr-sub"
        val atmos = game?.scenario?.atmosferic as? Int ?: 0
        val ground = game?.scenario?.ground as? Int ?: 0
        sub.innerHTML = "<i>${game?.scenario?.name}</i>, ${game?.scenario?.date?.toDateString()} · " +
            "${weatherIconImg(atmos, "osada-tr-weather-img")}${weatherConditionNames.getOrNull(atmos) ?: ""} · " +
            "${groundIconImg(ground, "osada-tr-weather-img")}${groundConditionNames.getOrNull(ground) ?: ""}"

        // Briefing lives with Dossier in the actions cluster (both "view info" actions, grouped
        // together, ahead of Close) rather than beside the title — user feedback, reversing an
        // earlier placement that itself was working around a legacy-CSS overlap bug now fixed at
        // its actual root cause (.osada-tr-header .combatLogInfoButton no longer position:absolute).
        val actions = addTag(header, "div")
        actions.className = "osada-tr-actions"
        val descButton = addTag(actions, "span")
        descButton.className = "smallButtonMenu combatLogInfoButton osada-tr-briefing-btn"
        descButton.title = "Scenario Briefing"
        descButton.style.fontSize = "16px"
        descButton.textContent = "g"
        descButton.onclick = { _: MouseEvent ->
            // Campaign battles reopen the full dialogue/orders screen. Standalone scenarios and
            // older states without a cached briefing keep the existing narrative message fallback.
            val reopened = game?.ui?.reopenScenarioBriefing() as? Boolean ?: false
            if (!reopened) {
                UIBuilder.message(
                    game?.scenario?.name ?: "",
                    game?.scenario?.getDescription() ?: "",
                    narrative = true
                )
            }
        }
        if (game?.campaign != null) {
            val dossierButton = addTag(actions, "span")
            dossierButton.className = "smallButtonMenu combatLogInfoButton"
            dossierButton.title = "Campaign Dossier"
            dossierButton.textContent = "@"
            dossierButton.onclick = { _: MouseEvent ->
                forceClose()
                UIBuilder.showDossier(true, null)
            }
        }
        val closeButton = addTag(actions, "span")
        closeButton.className = "osada-ico osada-ico--close combatLogCloseBut"
        closeButton.title = "Close (Esc)"
        closeButton.onclick = { _: MouseEvent -> toggleCombatLog() }

        return header
    }

    private fun buildSummaryTiles(container: HTMLElement, map: GameMap): HTMLElement {
        val game = gameRef()
        val currentPlayer = map.currentPlayer
        val row = addTag(container, "div")
        row.className = "osada-tr-summary"
        if (currentPlayer == null) return row

        fun tile(label: String, value: String, sub: String? = null) {
            val t = addTag(row, "div")
            t.className = "osada-tr-tile"
            val v = addTag(t, "div")
            v.className = "osada-tr-tile__value"
            v.innerHTML = value
            val l = addTag(t, "div")
            l.className = "osada-tr-tile__label"
            l.textContent = label
            if (sub != null) {
                val s = addTag(t, "div")
                s.className = "osada-tr-tile__sub"
                s.innerHTML = sub
            }
        }

        val objectivesLeft = map.sidesVictoryHexes.getOrElse(currentPlayer.side) { mutableListOf<Cell>() }.size
        tile("Objectives Left", objectivesLeft.toString())

        val currentTurn = map.turn
        // Only the NEAREST upcoming victory tier is shown (spec: compact, not the old three-tier
        // sentence) — the further-out tiers stop mattering once a closer one is reachable.
        val tiers = listOfNotNull(
            ((map.victoryTurns.getOrNull(0) as? Int ?: 0) - currentTurn + 1).takeIf { it > 0 }
                ?.let { it to (outcomeNames["briliant"] ?: "Brilliant") },
            ((map.victoryTurns.getOrNull(1) as? Int ?: 0) - currentTurn + 1).takeIf { it > 0 }
                ?.let { it to (outcomeNames["victory"] ?: "Victory") },
            ((map.victoryTurns.getOrNull(2) as? Int ?: 0) - currentTurn + 1).takeIf { it > 0 }
                ?.let { it to (outcomeNames["tactical"] ?: "Tactical") }
        )
        tiers.minByOrNull { it.first }?.let { (turns, outcome) -> tile("Turns to $outcome", turns.toString()) }

        tile("Score", currentPlayer.score.toString())
        val nextTurnPrestige = currentPlayer.prestigePerTurn.getOrNull(map.turn + 1) ?: 0
        tile(
            "Prestige",
            "${currentPlayer.prestige}&nbsp;${UIBuilder.currencyIcon}",
            if (nextTurnPrestige != 0) "+$nextTurnPrestige next turn" else null
        )

        // Casualties: summed from this turn's combat log for the viewing side — the same data
        // the Combat group below itemizes per-unit, just totaled for an at-a-glance read.
        var inflicted = 0
        var taken = 0
        val game2 = game
        if (game2 != null) {
            val combatKeys = js("Object.keys")(CombatLog.log.combat) as Array<String>
            for (key in combatKeys) {
                val entry = CombatLog.log.combat[key] ?: continue
                if (entry.side != game2.spotSide) continue
                inflicted += entry.kills as? Int ?: 0
                taken += entry.losses as? Int ?: 0
            }
        }
        tile("Inflicted", inflicted.toString())
        tile("Losses", taken.toString())

        return row
    }

    // ---- Event feed: grouped, collapsible sections of scannable rows ----

    private class FeedGroup(val icoMod: String, val label: String, val rowsBody: HTMLElement, val count: Int)

    private fun emptyGroup(icoMod: String, label: String): FeedGroup {
        val body = document.createElement("div") as HTMLElement
        body.className = "osada-tr-group__rows"
        return FeedGroup(icoMod, label, body, 0)
    }

    private fun attachGroup(container: HTMLElement, group: FeedGroup) {
        val groupEl = addTag(container, "div")
        groupEl.className = "osada-tr-group"
        val header = addTag(groupEl, "div")
        header.className = "osada-tr-group__header"
        val icon = addTag(header, "span")
        icon.className = "osada-ico osada-ico--${group.icoMod} osada-tr-group__ico"
        val label = addTag(header, "span")
        label.className = "osada-tr-group__label"
        label.textContent = group.label
        val badge = addTag(header, "span")
        badge.className = "osada-tr-group__badge"
        badge.textContent = group.count.toString()
        header.onclick = { _: MouseEvent ->
            // In the turn-start teaser, a group header is an "open the full report" affordance
            // (same as the expand button — user request): collapsing a group inside a 220px
            // clipped strip isn't meaningful. Full mode keeps the collapse toggle.
            if (byId("combatLog")?.classList?.contains("osada-tr--teaser") == true) {
                showCombatLog(true)
            } else {
                groupEl.classList.toggle("osada-tr-group--collapsed")
            }
        }
        groupEl.appendChild(group.rowsBody)
    }

    /** One feed row: icon + bold title line + dim detail line. Clickable (jumps to `pos`) when a
     *  position is available; the raw "(col,row)" lives only in the tooltip, same treatment as
     *  the sidebar log ([HudLog.addAt]). */
    private fun addFeedRow(
        container: HTMLElement,
        icon: String,
        title: String,
        detail: String,
        isCore: Boolean,
        isDestroyed: Boolean,
        pos: Cell?
    ): HTMLElement {
        // Named rowEl, NOT row: `jsObject { row = pos.row; col = pos.col }` below builds a
        // dynamic {row, col} literal via an implicit assignment — a local `val row` in this same
        // scope shadows that and the compiler tries to reassign the val instead (type/reassign
        // error), rather than setting the js object's property.
        val rowEl = addTag(container, "div")
        rowEl.className = "osada-tr-row" +
            (if (isCore) " osada-tr-row--core" else "") +
            (if (isDestroyed) " osada-tr-row--destroyed" else "")
        val iconBox = addTag(rowEl, "div")
        iconBox.className = "osada-tr-row__icon"
        // background-image at natural size, NOT an <img src>: unit icons are multi-frame sprite
        // STRIPS — an <img> (or background-size:contain) would scale the WHOLE strip into the
        // box (a row of tiny units), same bug already fixed for #ecPortrait. Natural size +
        // position 0 0 shows only the first frame.
        if (icon.isNotEmpty()) iconBox.style.backgroundImage = "url($icon)" else iconBox.style.display = "none"
        val body = addTag(rowEl, "div")
        body.className = "osada-tr-row__body"
        val titleDiv = addTag(body, "div")
        titleDiv.className = "osada-tr-row__title"
        titleDiv.innerHTML = title
        if (detail.isNotEmpty()) {
            val detailDiv = addTag(body, "div")
            detailDiv.className = "osada-tr-row__detail"
            detailDiv.innerHTML = detail
        }
        if (pos != null) {
            rowEl.title = "Jump to (${pos.col},${pos.row})"
            rowEl.classList.add("osada-tr-row--clickable")
            rowEl.onclick = { _: MouseEvent ->
                gameRef()?.ui?.uiSetCellOnViewPort(jsObject { row = pos.row; col = pos.col })
            }
        }
        return rowEl
    }

    private fun buildCombatGroup(map: Array<Array<Hex>>): FeedGroup {
        val game = gameRef() ?: return emptyGroup("attack", "Combat")
        val body = document.createElement("div") as HTMLElement
        body.className = "osada-tr-group__rows"
        val combatKeys = js("Object.keys")(CombatLog.log.combat) as Array<String>
        data class Built(val el: HTMLElement, val isCore: Boolean)
        val built = mutableListOf<Built>()
        for (key in combatKeys) {
            val entry = CombatLog.log.combat[key] ?: continue
            if (entry.side != game.spotSide) continue
            val eqData = Equipment.equipment[entry.eqid as Int]
            val uclass = eqData.uclass as? Int ?: 0
            val icon = if (uclass > UnitClass.AIR_TRANSPORT.value) UIBuilder.navalReplacementIcon else eqData.icon as? String ?: ""
            val isCore = entry.isCore as? Boolean == true
            val pos = entry.pos as? Cell ?: Cell(0, 0)
            val hexName = map[pos.row][pos.col].name
            val terrain = map[pos.row][pos.col].terrain
            val location = if (hexName.isNotEmpty()) hexName else (terrainNames.getOrNull(terrain) ?: "")

            val assaults = entry.assaults as? Int ?: 0
            val defends = entry.defends as? Int ?: 0
            val supports = entry.supports as? Int ?: 0
            val kills = entry.kills as? Int ?: 0
            val losses = entry.losses as? Int ?: 0
            val str = entry.str as? Int ?: 0
            val xp = entry.xp as? Int ?: 0
            val ammo = entry.ammo as? Int ?: 0
            val entrench = entry.entrench as? Int ?: 0
            val entrenchLost = entry.entrenchLost as? Int ?: 0
            val maxAmmo = eqData.ammo as? Int ?: 0

            val actionVerb = when {
                assaults >= defends || supports >= defends -> if (supports >= assaults) "provided fire support from" else "assaulted from"
                else -> "defended against an attack near"
            }
            val corePrefix = if (isCore) numSpan("Core ") else ""
            val title = "$corePrefix${UIBuilder.unitIDToOrdinal(entry.id as Int)} <b>${eqData.name}</b> $actionVerb $location"

            val detailParts = mutableListOf<String>()
            if (kills > 0) detailParts.add("inflicted ${numSpan(kills)} casualties")
            if (losses != 0) {
                detailParts.add(if (str > 0) "lost ${numSpan("-$losses")} (${numSpan(str)} remain)" else "lost ${numSpan("-$losses")} — destroyed")
            }
            if (assaults > 0) detailParts.add("$assaults assaults")
            if (defends > 0) detailParts.add("defended $defends attacks")
            if (xp != 0 && str > 0) detailParts.add("+${numSpan(xp)} XP")
            if (ammo < maxAmmo / 4) detailParts.add("low ammo ${numSpan("$ammo/$maxAmmo")}")
            if (entrenchLost > 0) detailParts.add("${numSpan(entrenchLost)} entrenchments lost (${numSpan(entrench)} remain)")
            else if (entrench > 0) detailParts.add("${numSpan(entrench)} entrenchments")

            val row = addFeedRow(body, icon, title, detailParts.joinToString(" · "), isCore, str == 0, pos)
            built.add(Built(row, isCore))
        }
        // Core units surface first (same grouping the legacy layout pinned via DOM insertion
        // order) — re-appending an already-attached child MOVES it, so this reorders in place.
        built.sortedByDescending { it.isCore }.forEach { body.appendChild(it.el) }
        return FeedGroup("attack", "Combat", body, built.size)
    }

    private fun buildResupplyGroup(): FeedGroup {
        val game = gameRef() ?: return emptyGroup("supply", "Resupply")
        val body = document.createElement("div") as HTMLElement
        body.className = "osada-tr-group__rows"
        val keys = js("Object.keys")(CombatLog.log.resupply) as Array<String>
        var count = 0
        for (key in keys) {
            val entry = CombatLog.log.resupply[key] ?: continue
            if (entry.side != game.spotSide) continue
            val eqData = Equipment.equipment[entry.eqid as Int]
            val uclass = eqData.uclass as? Int ?: 0
            val icon = if (uclass > UnitClass.AIR_TRANSPORT.value) UIBuilder.navalReplacementIcon else eqData.icon as? String ?: ""
            val isCore = entry.isCore as? Boolean == true
            val corePrefix = if (isCore) numSpan("Core ") else ""
            val title = "$corePrefix${UIBuilder.unitIDToOrdinal(key.toInt())} <b>${eqData.name}</b>"
            val ammo = entry.ammo as? Int ?: 0
            val fuel = entry.fuel as? Int ?: 0
            val maxAmmo = eqData.ammo as? Int ?: 0
            val maxFuel = eqData.fuel as? Int ?: 0
            val detailParts = mutableListOf<String>()
            if (ammo > 0) detailParts.add("${numSpan("$ammo/$maxAmmo")} ammo")
            if (fuel > 0 && maxFuel > 0) detailParts.add("${numSpan("$fuel/$maxFuel")} fuel")
            addFeedRow(body, icon, title, "Resupplied automatically: " + detailParts.joinToString(" · "), isCore, false, null)
            count++
        }
        return FeedGroup("supply", "Resupply", body, count)
    }

    private fun buildReinforceGroup(): FeedGroup {
        val game = gameRef() ?: return emptyGroup("upgrade", "Reinforcements")
        val list = CombatLog.log.reinforce as? Array<dynamic> ?: return emptyGroup("upgrade", "Reinforcements")
        val body = document.createElement("div") as HTMLElement
        body.className = "osada-tr-group__rows"
        var count = 0
        for (i in 0 until list.size) {
            val entry = list[i]
            if (entry.side != game.spotSide) continue
            val eqData = Equipment.equipment[entry.eqid as Int]
            val uclass = eqData.uclass as? Int ?: 0
            val icon = if (uclass > UnitClass.AIR_TRANSPORT.value) UIBuilder.navalReplacementIcon else eqData.icon as? String ?: ""
            val pos = entry.pos as? Cell ?: Cell(0, 0)
            val title = "<b>${eqData.name}</b> arrived as reinforcement"
            addFeedRow(body, icon, title, "", false, false, pos)
            count++
        }
        return FeedGroup("upgrade", "Reinforcements", body, count)
    }

    private fun buildLeadersGroup(): FeedGroup {
        val game = gameRef() ?: return emptyGroup("star", "Unit Leaders")
        val list = CombatLog.log.leaders as? Array<dynamic> ?: return emptyGroup("star", "Unit Leaders")
        val body = document.createElement("div") as HTMLElement
        body.className = "osada-tr-group__rows"
        var count = 0
        for (i in 0 until list.size) {
            val entry = list[i]
            if (entry.side != game.spotSide) continue
            val eqData = Equipment.equipment[entry.eqid as Int]
            val isCore = entry.isCore as? Boolean == true
            val corePrefix = if (isCore) numSpan("Core ") else ""
            val title = "$corePrefix${UIBuilder.unitIDToOrdinal(entry.id as Int)} <b>${eqData.name}</b> received a new leader"
            val pos = entry.pos as? Cell ?: Cell(0, 0)
            val classLeader = LeaderType.values().find { it.value == entry.classLeader as? Int }
            val unitLeader = LeaderType.values().find { it.value == entry.leader as? Int }
            val classDesc = classLeader?.let { Leaders.description[it]?.first } ?: ""
            val leaderDesc = unitLeader?.let { Leaders.description[it]?.first } ?: ""
            val detail = "${numSpan(classDesc)} and ${numSpan(leaderDesc)} abilities"
            addFeedRow(body, eqData.icon as? String ?: "", title, detail, isCore, false, pos)
            count++
        }
        return FeedGroup("star", "Unit Leaders", body, count)
    }

    private fun buildObjectiveGroup(map: Array<Array<Hex>>): FeedGroup {
        val game = gameRef() ?: return emptyGroup("map", "Objectives")
        val list = CombatLog.log.objectives as? Array<dynamic> ?: return emptyGroup("map", "Objectives")
        val body = document.createElement("div") as HTMLElement
        body.className = "osada-tr-group__rows"
        var count = 0
        for (i in 0 until list.size) {
            val entry = list[i]
            val pos = entry.pos as? Cell ?: Cell(0, 0)
            val hex = map.getOrNull(pos.row)?.getOrNull(pos.col) ?: continue
            val isFriendly = entry.side == game.spotSide
            val title: String
            val detail: String
            if (isFriendly) {
                title = "Captured <b>${hex.name}</b>"
                detail = "Prestige +${numSpan(prestigeGains["objectiveCapture"] ?: 0)}&nbsp;${UIBuilder.currencyIcon}"
            } else {
                title = "Lost <b>${hex.name}</b>"
                detail = ""
            }
            // Objectives have no per-unit icon; addFeedRow hides the icon box for an empty src.
            addFeedRow(body, "", title, detail, false, !isFriendly, pos)
            count++
        }
        return FeedGroup("map", "Objectives", body, count)
    }

    private fun numSpan(value: Any): String = "<span class='combatLogNum'>$value</span>"

    private fun gameRef(): dynamic = js("typeof game !== 'undefined' ? game : null")

    private fun windowInnerHeight(): Int = js("window.innerHeight") as Int
}
