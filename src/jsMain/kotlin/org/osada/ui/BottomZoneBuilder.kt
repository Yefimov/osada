package org.osada.ui

import kotlinx.browser.window
import org.osada.*
import org.osada.model.*
import org.osada.rules.GameRules
import org.w3c.dom.HTMLElement

/**
 * Builds and drives the bottom zone (Task 3): the player unit card (`#unit-info`, reparented
 * here, content still filled by [UnitInfoPanel]), the attack forecast strip, and the enemy unit
 * card. Owns the bottom-zone state machine (hidden / own-selected / hover-forecast / enemy-alone)
 * and the ~2s hover-persistence timer.
 *
 * Fog-of-war discipline: [renderEnemyCard] and [renderForecast] only ever receive a [GameUnit]
 * that the caller has ALREADY cleared through the existing spotting checks (same units the
 * cursor forecast / click-to-inspect paths already reveal) — no new information is surfaced here.
 */
internal object BottomZoneBuilder {

    private const val HOVER_PERSIST_MS = 2000

    private var hoverPersistTimer: Int = 0

    fun build() {
        restructurePlayerCard()
        buildForecastSkeleton()
        buildEnemyCardSkeleton()
        // #unit-context keeps its id/handlers; only reparented into the card's action-row slot.
        byId("uc-actions")?.let { slot -> byId("unit-context")?.let { slot.appendChild(it) } }
        setState("hidden")
    }

    // ---- Player card: compact ~90px layout (was a 150px wide ornate frame) ----

    private fun restructurePlayerCard() {
        val root = byId("unit-info") ?: return
        fun move(id: String, into: HTMLElement) { byId(id)?.let { into.appendChild(it) } }

        // #unit-info's OWN display is still toggled by makeVisible/makeHidden (inline style),
        // which would fight a stylesheet `display:flex` on the same id (inline style always
        // wins over a non-!important rule). So the real flex row lives on this INNER wrapper —
        // an id no code ever calls makeVisible/makeHidden on — and #unit-info stays a plain box
        // that just contains it, sidestepping the conflict entirely.
        val inner = addTag(root, "div")
        inner.id = "uc-inner"

        val portrait = addTag(inner, "div")
        portrait.id = "uc-portrait"
        move("uImageBg", portrait)

        val main = addTag(inner, "div")
        main.id = "uc-main"

        val nameLine = addTag(main, "div")
        nameLine.id = "uc-nameline"
        move("uName", nameLine)
        // Rename pencil (Stage 3.5, Task 2): built once here; UnitInfoPanel.showUnitInfo
        // shows/hides it per unit (own units only) and wires the click to the inline editor.
        val rename = addTag(nameLine, "span")
        rename.id = "ucRename"
        rename.className = "osada-rename-btn"
        rename.innerHTML = "&#9998;"   // ✎
        rename.title = "Rename"
        rename.style.display = "none"
        val stars = addTag(nameLine, "span")
        stars.id = "osadaUcStars"
        stars.className = "uc-stars"
        val ent = addTag(nameLine, "span")
        ent.id = "osadaUcEnt"
        ent.className = "uc-ent"
        // Filled only for a selected air unit grounded by the scenario's current weather
        // (CombatResolver.airGroundedByWeather) — explains an otherwise-silent empty attack range.
        val weather = addTag(nameLine, "span")
        weather.id = "osadaUcWeather"
        weather.className = "uc-weather"
        // Leader slot: non-clickable per spec ("future hook") — tooltip only, no onclick here;
        // UnitInfoPanel.showUnitInfo sets the title/filled-state, never an onclick, going forward.
        move("uLeader", nameLine)
        move("uTransport", nameLine)
        move("uCarrier", nameLine)

        val bars = addTag(main, "div")
        bars.id = "uc-bars"
        // Numbers live ON the bar (a value span inside the track); the row's tooltip explains
        // only what the stat MEANS (durability, resupply, movement) — never repeats the number
        // that's already visible, and hovering the label or the track both trigger it since
        // `title` is set on the whole row.
        fun bar(id: String, label: String, modifier: String, tooltip: String, iconFile: String? = null): HTMLElement {
            val row = addTag(bars, "div")
            row.id = "${id}Row"
            row.className = "osada-bar osada-bar--$modifier"
            row.title = tooltip
            if (iconFile != null) {
                val icon = addTag(row, "img")
                icon.setAttribute("src", "resources/ui/osada/$iconFile")
                icon.setAttribute("class", "osada-bar__ico")
                icon.setAttribute("alt", "")
            }
            val lbl = addTag(row, "span")
            lbl.className = "osada-bar__label"
            lbl.textContent = label
            val track = addTag(row, "div")
            track.className = "osada-bar__track"
            val fill = addTag(track, "div")
            fill.id = id
            fill.className = "osada-bar__fill"
            val value = addTag(track, "span")
            value.id = "${id}Value"
            value.className = "osada-bar__value"
            return row
        }
        // Helmet, not the chevron-stack alternative: `hud_icons_grid`'s "upgrade" icon is already an
        // arrow over chevrons, so a chevron STR icon would repeat that motif at a glance.
        bar("uStrBarFill", "STR", "str", "Unit durability and firepower", "ico_stat_str.png")
        bar("uAmmoBarFill", "AMMO", "ammo", "Remaining ammunition — resupply when low", "ico_stat_ammo.png")
        bar("uFuelBarFill", "FUEL", "fuel", "Remaining fuel — the unit can't move without it", "ico_stat_fuel.png")

        val actions = addTag(inner, "div")
        actions.id = "uc-actions"

        val expandBtn = addTag(inner, "div")
        expandBtn.id = "uc-expand"
        expandBtn.textContent = "All stats ▸"
        expandBtn.title = "Show every stat for this unit"
        expandBtn.onclick = { _: org.w3c.dom.events.MouseEvent ->
            root.classList.toggle("uc--expanded")
        }

        // #statsRowContainer (the full ~19-stat chip grid) becomes the "All stats" expander:
        // repositioned as an overlay ABOVE the compact card, shown only while uc--expanded.
        // Its own children/values are untouched — UnitInfoPanel.showUnitInfo still fills them.
        move("statsRowContainer", root)
        // #statsRowTop is now an empty legacy shell (everything inside it was reparented above).
        byId("statsRowTop")?.style?.display = "none"
        move("uFlag", root)
    }

    // ---- Forecast strip ----

    private fun buildForecastSkeleton() {
        val root = byId("osadaForecast") ?: return
        val atkDef = addTag(root, "div")
        atkDef.id = "fcAtkDef"
        atkDef.className = "osada-fc-atkdef"
        val losses = addTag(root, "div")
        losses.id = "fcLosses"
        losses.className = "osada-fc-losses"
        val strengths = addTag(root, "div")
        strengths.id = "fcStrengths"
        strengths.className = "osada-fc-strengths"
    }

    /** Reuses [GameRules.calculateCombatResults] — the SAME function and call shape the existing
     *  cursor-side attack forecast already uses ([CursorRenderer.generateAttackCursor]) — so the
     *  bottom-zone numbers always agree with the tooltip shown at the cursor. */
    fun renderForecast(attacker: GameUnit, defender: GameUnit, ownSide: Int) {
        cancelHoverPersistTimer()
        val map = GameHolder.instance?.scenario?.map
        val units = map?.getUnits()?.toList() ?: emptyList()
        val result = GameRules.calculateCombatResults(attacker, defender, units, false, true)

        val attackerData = attacker.unitData(true)
        val defenderData = defender.unitData(true)
        val attackValue = when {
            GameRules.isAir(defender) -> attackerData.airatk
            defenderData.target == UnitType.HARD.value -> attackerData.hardatk
            else -> attackerData.softatk
        }
        val defenseValue = if (GameRules.isAir(attacker)) defenderData.airdef else defenderData.grounddef

        byId("fcAtkDef")?.textContent = "ATK $attackValue · DEF $defenseValue"

        val attackerOwn = attacker.player?.side == ownSide
        val defenderOwn = defender.player?.side == ownSide
        val lossesEl = byId("fcLosses")
        lossesEl?.let {
            clearTag(it)
            val a = addTag(it, "span")
            a.className = "osada-fc-loss" + if (attackerOwn) " osada-fc-loss--own" else " osada-fc-loss--enemy"
            a.textContent = "−${result.losses}"
            val sep = addTag(it, "span")
            sep.className = "osada-fc-vs"
            sep.textContent = "vs"
            val d = addTag(it, "span")
            d.className = "osada-fc-loss" + if (defenderOwn) " osada-fc-loss--own" else " osada-fc-loss--enemy"
            d.textContent = "−${result.kills}"
        }
        byId("fcStrengths")?.textContent = "${attacker.strength} vs ${defender.strength}"

        renderEnemyCard(defender, ownSide)
        setState("hover")
    }

    /** Called every hover-move when the cursor leaves a valid attack target. Persists the
     *  currently-shown forecast/enemy card for ~2s (spec) before reverting. */
    fun onHoverLeft() {
        val bz = byId("osada-bottomzone") ?: return
        if (!bz.classList.contains("bz--hover")) return
        cancelHoverPersistTimer()
        armHoverPersistTimer()
    }

    private fun armHoverPersistTimer() {
        hoverPersistTimer = window.setTimeout({
            // Don't clobber the enemy card out from under someone actively reading its "All
            // stats" panel — re-check instead of reverting, so it only auto-hides once they
            // close the panel (or move back onto a target, which cancels the timer entirely).
            if (byId("osadaEnemyCard")?.classList?.contains("ec--expanded") == true) {
                armHoverPersistTimer()
            } else {
                setState(if (GameHolder.instance?.scenario?.map?.currentUnit != null) "own" else "hidden")
            }
        }, HOVER_PERSIST_MS)
    }

    private fun cancelHoverPersistTimer() {
        if (hoverPersistTimer != 0) {
            window.clearTimeout(hoverPersistTimer)
            hoverPersistTimer = 0
        }
    }

    // ---- Enemy card ----

    /** Every stat known about a spotted enemy unit that ISN'T fog-of-war-sensitive: static
     *  equipment-catalogue values (target/move type, movement, range, initiative, spot range,
     *  the four attack values, the four defense values). Deliberately excludes ammo/fuel/
     *  experience/entrenchment — those are per-unit LIVE state we have no visibility into for an
     *  enemy, unlike the player's own card (same discipline the class doc comment already states:
     *  nothing surfaced here beyond what spotting already reveals). Glyph chars/titles match
     *  [UIBuilder.unitStats]'s entries for the same stat so the two cards read consistently. */
    // Grouped (Attack/Defence/Mobility & Recon) for the "All stats" expander — same treatment as
    // the player card's #statsRow (UIBuilder.unitStats' `group` field), just a separate, smaller,
    // local list since this one has no live-state entries at all (see class doc above).
    private val ecStatGroups: List<Pair<String, List<Triple<String, String, String>>>> = listOf(
        "Attack" to listOf(
            Triple("ecAHard", "{", "Power vs Hard targets"),
            Triple("ecASoft", "\$", "Power vs Soft targets"),
            Triple("ecAAir", "&", "Power vs Air targets"),
            Triple("ecANaval", "}", "Power vs Naval targets")
        ),
        "Defence" to listOf(
            Triple("ecDHard", "5", "Defence vs ground attacker"),
            Triple("ecDAir", "3", "Defence vs air attacker"),
            Triple("ecDClose", "6", "Defence in close combat"),
            Triple("ecDRange", "7", "Defence in ranged combat")
        ),
        "Mobility & Recon" to listOf(
            Triple("ecTarget", "`", "Target type"),
            Triple("ecMoveType", "~", "Movement type"),
            Triple("ecMovement", "?", "Movement range"),
            Triple("ecGunRange", ">", "Firing range"),
            Triple("ecIni", "|", "Combat initiative"),
            Triple("ecSpot", "'", "Spotting range")
        )
    )

    private fun buildEnemyCardSkeleton() {
        val root = byId("osadaEnemyCard") ?: return
        val portrait = addTag(root, "div")
        portrait.id = "ecPortrait"
        portrait.className = "uc-portrait-img"
        val main = addTag(root, "div")
        main.id = "ecMain"
        val name = addTag(main, "div")
        name.id = "ecName"
        name.className = "osada-ec-name"
        val sub = addTag(main, "div")
        sub.id = "ecSub"
        sub.className = "osada-ec-sub"
        val bar = addTag(main, "div")
        bar.id = "ecStrRow"
        bar.className = "osada-bar osada-bar--enemy"
        val track = addTag(bar, "div")
        track.className = "osada-bar__track"
        val fill = addTag(track, "div")
        fill.id = "ecStrBarFill"
        fill.className = "osada-bar__fill"
        val stat = addTag(main, "div")
        stat.id = "ecStat"
        stat.className = "osada-ec-stat"

        val expandBtn = addTag(root, "div")
        expandBtn.id = "ec-expand"
        expandBtn.textContent = "All stats ▸"
        expandBtn.title = "Show every known stat for this unit"
        expandBtn.onclick = { _: org.w3c.dom.events.MouseEvent ->
            root.classList.toggle("ec--expanded")
        }

        // A SEPARATE stats grid from the player card's #statsRowContainer — that element belongs
        // to the player's own card, and during the "hover" state BOTH cards are visible at the
        // same time, so they can never share one. Same .statsGlyph/.statsText chip look though.
        val statsContainer = addTag(root, "div")
        statsContainer.id = "ecStatsContainer"
        ecStatGroups.forEach { (groupName, entries) ->
            val section = addTag(statsContainer, "div")
            section.className = "osada-stat-group"
            val label = addTag(section, "div")
            label.className = "osada-stat-group__label"
            label.textContent = groupName
            val grid = addTag(section, "div")
            grid.className = "osada-stat-group__grid"
            entries.forEach { (id, glyph, title) ->
                val chip = addTag(grid, "div")
                chip.className = "statsGlyph"
                chip.title = title
                chip.textContent = glyph
                val valueDiv = addTag(chip, "div")
                valueDiv.id = id
                valueDiv.className = "statsText"
            }
        }
    }

    /** Same one-line stat format the old sidebar hover inspector used ("STR x/10 · DEF g/a") —
     *  reused verbatim rather than inventing a new summary; the "All stats" expander below adds
     *  the rest of the (fog-of-war-safe) picture without replacing this quick read. */
    fun renderEnemyCard(unit: GameUnit, ownSide: Int) {
        val data = unit.unitData(true)
        byId("ecPortrait")?.style?.backgroundImage = "url(${data.icon})"
        val className = unitClassNames.getOrNull(data.uclass) ?: ""
        byId("ecName")?.textContent = if (data.name == className) className else "${data.name} $className"
        // unit.flag (like the player card's #uFlag), NOT data.country: the equipment record's
        // country is a catalogue index (0 = shared/generic) that maps to "Unknown" here.
        byId("ecSub")?.textContent = "Enemy · ${Equipment.getCountryName(unit.flag - 1)}"
        byId("ecStrBarFill")?.style?.width = "${(unit.strength / 10.0 * 100).coerceIn(0.0, 100.0)}%"
        byId("ecStat")?.textContent = "STR ${unit.strength}/10 · DEF ${data.grounddef} ground / ${data.airdef} air"

        val gunRange = if (data.gunrange == 0) 1 else data.gunrange
        byId("ecTarget")?.textContent = unitTypeNames.getOrNull(data.target) ?: ""
        byId("ecMoveType")?.textContent = if (data.uclass <= UnitClass.AIR_DEFENCE.value && data.movmethod == MovMethod.DEEP_NAVAL.value)
            "Rail Road" else movMethodNames.getOrNull(data.movmethod) ?: ""
        byId("ecMovement")?.textContent = data.movpoints.toString()
        byId("ecGunRange")?.textContent = gunRange.toString()
        byId("ecIni")?.textContent = data.initiative.toString()
        byId("ecSpot")?.textContent = data.spotrange.toString()
        byId("ecAHard")?.textContent = data.hardatk.toString()
        byId("ecASoft")?.textContent = data.softatk.toString()
        byId("ecAAir")?.textContent = data.airatk.toString()
        byId("ecANaval")?.textContent = data.navalatk.toString()
        byId("ecDHard")?.textContent = data.grounddef.toString()
        byId("ecDAir")?.textContent = data.airdef.toString()
        byId("ecDClose")?.textContent = data.closedef.toString()
        byId("ecDRange")?.textContent = data.rangedefmod.toString()
    }

    /** "Enemy clicked, nothing own selected" — the enemy card takes the player-card's slot. */
    fun showEnemyAlone(unit: GameUnit, ownSide: Int) {
        cancelHoverPersistTimer()
        renderEnemyCard(unit, ownSide)
        setState("enemyAlone")
    }

    // ---- State machine ----

    fun setState(mode: String) {
        val bz = byId("osada-bottomzone") ?: return
        bz.classList.remove("bz--visible", "bz--hover", "bz--enemy-only")
        when (mode) {
            "hidden" -> { /* leave all state classes off; CSS hides when none are present */ }
            "own" -> bz.classList.add("bz--visible")
            "hover" -> bz.classList.add("bz--visible", "bz--hover")
            "enemyAlone" -> bz.classList.add("bz--visible", "bz--enemy-only")
        }
    }
}
