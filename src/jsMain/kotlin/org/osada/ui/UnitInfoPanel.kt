package org.osada.ui

import kotlinx.browser.document
import org.osada.MovMethod
import org.osada.UNIT_MAX_EXPERIENCE
import org.osada.UNIT_NAME_MAX_LENGTH
import org.osada.UnitClass
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.Leaders
import org.osada.movMethodNames
import org.osada.rules.GameRules
import org.osada.uiSettings
import org.osada.unitClassNames
import org.osada.unitTypeNames
import org.w3c.dom.HTMLInputElement

/**
 * Displays unit/equipment statistics in the unit-info panel, builds the per-unit action
 * context menu, and executes those actions. Extracted from the former [UI] god-class (SRP).
 */
internal class UnitInfoPanel(private val ui: UI) {

    private val contextActionLabels = mapOf(
        "mount" to "Mount",
        "embark" to "Embark",
        "resupply" to "Resupply",
        "reinforce" to "Reinforce",
        "overstrength" to "Overstr.",
        "undo" to "Undo",
        "sleep" to "Sleep",
    )

    fun buildUnitContext(unit: GameUnit?) {
        clearTag("unit-context")
        val scenario = ui.game.scenario ?: return
        val map = scenario.map
        val currentPlayer = map.currentPlayer ?: return
        if (unit == null || unit.player?.id != currentPlayer.id) {
            makeHidden("unit-context")
            // Nothing of the player's own is selected — bottom zone fully hidden (spec), unless
            // an enemy-alone inspection is what's currently showing (that call happens AFTER
            // this one in the click handlers, so it correctly overrides this hide).
            BottomZoneBuilder.setState("hidden")
            AttackRingBuilder.clear()
            return
        }
        var count = 0
        fun addButton(
            action: String,
            glyph: String,
            title: String,
            labelOverride: String? = null,
            extraClass: String = "",
        ) {
            // Labeled action chip (glyph + text) instead of a bare floating glyph button:
            // the player must never guess what a context action does.
            val button = addTag("unit-context", "div")
            button.className = "osada-action" + if (extraClass.isNotEmpty()) " $extraClass" else ""
            button.title = title
            val g = addTag(button, "span")
            g.className = "osada-action__glyph"
            g.innerHTML = glyph
            val label = addTag(button, "span")
            label.className = "osada-action__label"
            label.textContent = labelOverride ?: contextActionLabels[action] ?: action
            button.onclick = { _: org.w3c.dom.events.MouseEvent -> executeUnitContext(action, unit) }
            count++
        }
        if (GameRules.canMount(unit)) {
            // Mount/Dismount is ONE button; its label reflects the unit's current state (spec).
            val label = if (unit.isMounted) "Dismount" else "Mount"
            addButton(
                "mount",
                UIBuilder.unitContextButtons["mount"] ?: "[",
                "Mount/Umount this unit in/from a transport",
                label,
            )
        }
        if (GameRules.canEmbark(map, unit) || GameRules.canDisembark(map, unit)) {
            addButton(
                "embark",
                UIBuilder.unitContextButtons["embark"] ?: "2",
                "Embark/DisEmbark this unit in/from a air/naval transport",
            )
        }
        if (GameRules.canResupply(map, unit)) {
            addButton(
                "resupply",
                UIBuilder.unitContextButtons["resupply"] ?: "!",
                "Resupply Ammo and Fuel for this unit",
            )
        }
        if (currentPlayer.prestige >= GameRules.calculateUnitCostPerStrength(unit)) {
            if (GameRules.canReinforce(map, unit, false)) {
                addButton("reinforce", UIBuilder.unitContextButtons["reinforce"] ?: "#", "Reinforce unit strength")
            }
            if (GameRules.canReinforce(map, unit, true)) {
                addButton("overstrength", UIBuilder.unitContextButtons["overstrength"] ?: "J", "Overstrength unit")
            }
        }
        if (map.canUndoMove(unit)) {
            // The single rescue action — styled distinctly (brass border, spec).
            addButton(
                "undo",
                UIBuilder.unitContextButtons["undo"] ?: "_",
                "Undo last move",
                extraClass = "osada-action--undo",
            )
        }
        if (ui.hasAnyAction(unit)) {
            // Removes the unit from the ready-unit navigator/its count for the rest of this turn
            // (still counted by the End Turn nag, so it can't be silently forgotten) — see
            // TurnSleep. Offered whenever the unit could still act, whether or not it already has
            // (a moved-but-not-fired unit can still be put to sleep).
            val asleep = ui.isUnitAsleep(unit)
            addButton(
                "sleep",
                UIBuilder.unitContextButtons["sleep"] ?: "t",
                if (asleep) "Wake this unit" else "Put this unit to sleep for the rest of the turn",
                labelOverride = if (asleep) "Wake" else "Sleep",
                extraClass = if (asleep) "osada-action--active" else "",
            )
        }
        if (count > 0) makeVisible("unit-context") else makeHidden("unit-context")
    }

    private fun executeUnitContext(action: String, unit: GameUnit) {
        val map = ui.game.scenario?.map ?: return
        val radius = getUnitRenderRadius(unit)
        val pos = unit.getPos() ?: return
        when (action) {
            "mount" -> if (unit.isMounted) map.unmountUnit(unit) else map.mountUnit(unit)
            "embark" -> if (unit.carrier > 0) map.disembarkUnit(unit) else map.embarkUnit(unit)
            "resupply" -> {
                val supply = map.resupplyUnit(unit)
                val parts = mutableListOf<String>()
                if (supply.ammo > 0) parts.add("+${supply.ammo} ammo")
                if (supply.fuel > 0) parts.add("+${supply.fuel} fuel")
                val message = if (parts.isEmpty()) "Can't resupply" else parts.joinToString(" ")
                ui.showAlert(pos.row, pos.col, message, true)
            }
            "reinforce", "overstrength" -> {
                val overStrength = action == "overstrength"
                val result = map.reinforceUnit(unit, overStrength)
                val parts = mutableListOf<String>()
                val strength = result.strength as? Int ?: 0
                val ammo = result.ammo as? Int ?: 0
                val fuel = result.fuel as? Int ?: 0
                if (strength > 0) parts.add("+$strength units")
                if (ammo > 0) parts.add("+$ammo ammo")
                if (fuel > 0) parts.add("+$fuel fuel")
                val message = if (parts.isEmpty()) {
                    if (overStrength) "No overstrength" else "Can't reinforce"
                } else {
                    parts.joinToString(" ")
                }
                ui.showAlert(pos.row, pos.col, message, true)
            }
            "undo" -> map.undoLastMove()
            "sleep" -> ui.toggleUnitSleep(unit)
        }
        buildUnitContext(unit)
        showUnitInfo(unit)
        ui.render.render(pos.row, pos.col, radius)
        if (action == "mount" || action == "embark" || action == "undo") {
            // These three all can move the unit to a DIFFERENT hex than `pos` (undo teleports it
            // back to its pre-move origin) — the render above, centered on the pre-action position,
            // only partially redraws around the unit's NEW position, chopping its move-range
            // overlay at the edge of that box. A second render centered on the actual new position
            // fixes it, same as mount/embark already did.
            val newPos = unit.getPos() ?: pos
            val newRadius = getUnitRenderRadius(unit)
            ui.render.render(newPos.row, newPos.col, newRadius)
        }
    }

    /**
     * The unit-info panel normally only renders when the user has pinned it via "Inspect Unit"
     * (`unitInfoVisibility`). While the buy/deploy equipment window is open we force it visible so
     * selecting a unit always shows its stats (PM leaves this to the pinned toggle; we make it
     * automatic during purchase). [hideUnitInfoIfNotPinned] restores the pre-buy state on close.
     */
    private fun equipmentWindowOpen(): Boolean = isVisible("equipment") || isVisible("container-unitlist")

    fun hideUnitInfoIfNotPinned() {
        if (!uiSettings.unitInfoVisibility) {
            makeHidden("unit-info")
            byId("inspectunit")?.let { toggleButton(it, false) }
        }
    }

    fun showUnitInfo(unit: GameUnit?) {
        if (unit == null) return
        // Never repaint the card out from under an in-progress rename — the inline input
        // would be destroyed mid-typing (blur/commit paths call back in here themselves).
        if (renameActive) return
        // Auto-show on selection (modern HUD): only the explicit Inspect Unit toggle
        // (unitInfoVisibility=false) suppresses the panel, not its current visibility.
        if (!equipmentWindowOpen() && !uiSettings.unitInfoVisibility) return
        makeVisible("unit-info")
        byId("inspectunit")?.let { toggleButton(it, true) }

        clearTag("uTransport")
        clearTag("uCarrier")
        delTag(byId("leaderInfo"))
        byId("uLeader")?.className = "uc-leader-slot"
        byId("uTransport")?.className = ""
        byId("uCarrier")?.className = ""
        byId("statsRow")?.let { makeVisible(it.id) }
        // Fresh selection invalidates any in-flight hover forecast context (it referred to
        // whatever unit was selected before); force the next mouse-move to recompute.
        resetHoverCache()

        val data = if (unit.carrier > 0 && !unit.isMounted) unit.unitData(true) else unit.unitData()
        val ammo = unit.getAmmo()
        var ammoDisplay = ammo.toString()
        var fuelStr = "-"
        if (GameRules.unitUsesFuel(unit)) fuelStr = unit.getFuel().toString()
        val experience = unit.experience
        val entrenchment = unit.entrenchment

        if (ammo < data.ammo / 4) ammoDisplay = htmlRed(ammo)
        if (fuelStr != "-" && (fuelStr.toIntOrNull() ?: 0) < data.fuel / 4) fuelStr = htmlRed(fuelStr)
        if (data.gunrange == 0) data.gunrange = 1

        val ordinal = if (data.uclass != UnitClass.FORTIFICATION.value) UIBuilder.unitIDToOrdinal(unit.id) else ""
        val coreText = if (unit.isCore) " (Core Unit)" else ""
        val leaderText = if (unit.leader != -1) " (Leader)" else ""

        byId("uImage")?.style?.backgroundImage = "url(${data.icon})"
        byId("uSmallFlag")?.style?.backgroundPosition = "${-21 * (unit.flag - 1)}px 0px"
        byId("uFlag")?.style?.backgroundImage =
            "url('resources/ui/flags/${Equipment.unitedName}/flag_big_${unit.flag}.png')"
        byId("uFlag")?.textContent = Equipment.getCountryName(unit.flag - 1)
        val equipmentLabel = "$ordinal ${data.name} ${unitClassNames[data.uclass]}$coreText$leaderText"
        // A player-given name replaces the composite label on the card; the tooltip keeps the
        // equipment identity in parentheses so a renamed unit is never a mystery model.
        val fullNameLabel = unit.customName ?: equipmentLabel
        byId("uName")?.textContent = fullNameLabel
        // Full name (uName's own text can truncate at narrow card widths) plus class/country/
        // available-from/field-notes, same placeholder text as the equipment window's own bay.
        val tooltipName = unit.customName?.let { "$it ($equipmentLabel)" } ?: equipmentLabel
        val cardTooltip = equipmentCardTooltip(unit, data, tooltipName)
        byId("uImage")?.title = cardTooltip
        byId("uName")?.title = cardTooltip

        // Rename affordance (Stage 3.5, Task 2): own units only — never enemies, never
        // browsed equipment entries (showEquipmentInfo hides it).
        byId("ucRename")?.let { btn ->
            val own = unit.player?.id == ui.game.scenario?.map?.currentPlayer?.id
            btn.style.display = if (own) "" else "none"
            btn.onclick = { e: org.w3c.dom.events.MouseEvent ->
                e.stopPropagation()
                startRename(unit)
            }
        }
        byId("uTarget")?.textContent = unitTypeNames[data.target]
        byId("uMoveType")?.textContent =
            if (data.uclass <= UnitClass.AIR_DEFENCE.value &&
                data.movmethod == MovMethod.DEEP_NAVAL.value
            ) {
                "Rail Road"
            } else {
                movMethodNames[data.movmethod]
            }
        byId("uStr")?.textContent = "${unit.strength}/10"
        byId("uFuel")?.innerHTML = fuelStr
        byId("uAmmo")?.innerHTML = ammoDisplay
        byId("uGunRange")?.textContent = data.gunrange.toString()
        byId("uMovement")?.textContent = data.movpoints.toString()
        byId("uExp")?.textContent = experience.toString()
        byId("uEnt")?.textContent = entrenchment.toString()
        byId("uIni")?.textContent = data.initiative.toString()
        byId("uSpot")?.textContent = data.spotrange.toString()
        byId("uAHard")?.textContent = data.hardatk.toString()
        byId("uASoft")?.textContent = data.softatk.toString()
        byId("uAAir")?.textContent = data.airatk.toString()
        byId("uANaval")?.textContent = data.navalatk.toString()
        byId("uDHard")?.textContent = data.grounddef.toString()
        byId("uDAir")?.textContent = data.airdef.toString()
        byId("uDClose")?.textContent = data.closedef.toString()
        byId("uDRange")?.textContent = data.rangedefmod.toString()

        if (unit.carrier > 0) {
            val carrierBtn = byId("uCarrier")
            carrierBtn?.className = "osada-slot"
            carrierBtn?.innerHTML = "2"
            carrierBtn?.title = "Carrier — click to view"
            carrierBtn?.onclick = { _: org.w3c.dom.events.MouseEvent ->
                Equipment.getEquipment(unit.carrier)?.let { showEquipmentInfo(it) }
            }
        }
        unit.transport?.let {
            val transportBtn = byId("uTransport")
            transportBtn?.className = "osada-slot"
            transportBtn?.innerHTML = if (unit.isMounted) "9" else "8"
            transportBtn?.title =
                if (unit.isMounted) "Mounted — click to preview dismounted stats" else "In transport — click to preview mounted stats"
            val switchMount = !unit.isMounted
            transportBtn?.onclick = { _: org.w3c.dom.events.MouseEvent ->
                val wasMounted = unit.isMounted
                unit.isMounted = switchMount
                showUnitInfo(unit)
                unit.isMounted = wasMounted
            }
        }
        // Leader slot (spec): empty outline = no leader, filled = has leader; non-clickable for
        // now (future hook) — a tooltip with name/bonus is the only interaction, using data
        // Leaders.getUnitLeaderDescriptions already computes cheaply (no new lookups).
        val leaderBtn = byId("uLeader")
        if (unit.leader >= 0) {
            leaderBtn?.classList?.add("uc-leader-slot--filled")
            val descriptions = Leaders.getUnitLeaderDescriptions(unit)
            leaderBtn?.title = if (descriptions.isNotEmpty()) {
                descriptions.joinToString("\n") { "${it.first}: ${it.second}" }
            } else {
                "Leader"
            }
        } else {
            leaderBtn?.title = "Leader slot — empty"
        }
        // Three labeled bars (spec): Strength green, Ammo brass, Fuel brass (hidden if none).
        // Numbers live ON the bar (in the track); the row's tooltip explains only what the stat
        // MEANS — the number is already visible, repeating it in the tooltip would be redundant.
        byId("uStrBarFill")?.style?.width = "${(unit.strength / 10.0 * 100).coerceIn(0.0, 100.0)}%"
        byId("uStrBarFillValue")?.textContent = "${unit.strength}/10"
        val ammoPct = if (data.ammo > 0) (ammo / data.ammo.toDouble() * 100).coerceIn(0.0, 100.0) else 0.0
        byId("uAmmoBarFill")?.style?.width = "$ammoPct%"
        byId("uAmmoBarFillValue")?.textContent = "$ammo/${data.ammo}"
        val usesFuel = GameRules.unitUsesFuel(unit)
        byId("uFuelBarFillRow")?.style?.display = if (usesFuel) "flex" else "none"
        if (usesFuel) {
            val fuelPct = if (data.fuel > 0) (unit.getFuel() / data.fuel.toDouble() * 100).coerceIn(0.0, 100.0) else 0.0
            byId("uFuelBarFill")?.style?.width = "$fuelPct%"
            byId("uFuelBarFillValue")?.textContent = "${unit.getFuel()}/${data.fuel}"
        }
        // Experience stars: a simple 5-star scale over the existing raw experience value (the
        // number itself still lives in #uExp, in the "All stats" expander) — display only,
        // no rule change.
        val starCount = ((experience.toDouble() / UNIT_MAX_EXPERIENCE) * 5).toInt().coerceIn(0, 5)
        byId("osadaUcStars")?.textContent = "★".repeat(starCount) + "☆".repeat(5 - starCount)
        byId("osadaUcStars")?.title = "Experience: $experience/$UNIT_MAX_EXPERIENCE"
        byId("osadaUcEnt")?.textContent = if (entrenchment > 0) "⛨$entrenchment" else ""
        byId("osadaUcEnt")?.title = "Entrenchment: $entrenchment"
        val grounded = GameRules.isAir(unit) && GameRules.airGroundedByWeather(unit)
        byId("osadaUcWeather")?.textContent = if (grounded) "⚠" else ""
        byId("osadaUcWeather")?.title =
            if (grounded) "Grounded — bad weather prevents air units from attacking this turn" else ""

        // Bottom-zone state: an own unit is now showing in the player-card slot.
        if (unit.player?.id == ui.game.scenario?.map?.currentPlayer?.id) {
            BottomZoneBuilder.setState("own")
        }
    }

    // ---- Inline rename (Stage 3.5, Task 2) ----

    private var renameActive = false

    /** Swaps #uName for a text input in place (no modal). Enter/blur commit, Esc cancels;
     *  committing an empty value clears the custom name (back to the equipment label).
     *  Sanitizing: trim + 24-char cap. */
    private fun startRename(unit: GameUnit) {
        if (renameActive) return
        val nameEl = byId("uName") ?: return
        val parent = nameEl.parentElement ?: return
        renameActive = true
        val input = document.createElement("input") as HTMLInputElement
        input.className = "osada-rename-input"
        input.maxLength = UNIT_NAME_MAX_LENGTH
        input.value = unit.customName ?: ""
        input.placeholder = unit.unitData(true).name
        nameEl.style.display = "none"
        parent.insertBefore(input, nameEl)

        var done = false
        fun finish(commit: Boolean) {
            if (done) return
            done = true
            renameActive = false
            val value = input.value.trim().take(UNIT_NAME_MAX_LENGTH)
            input.onblur = null // removing a focused element fires blur; don't re-enter
            delTag(input)
            nameEl.style.display = ""
            if (commit) unit.customName = value.ifEmpty { null }
            showUnitInfo(unit)
            // The reserves/upgrade strip shows the same name — refresh it if it's open.
            if (isVisible("equipment")) {
                val eqclass = byId("eqUserSel")?.asDynamic()?.eqclass as? Int ?: UnitClass.TANK.value
                ui.updateEquipmentWindow(eqclass)
            }
        }
        input.onkeydown = { e ->
            // Typing must never trigger game hotkeys (H, P, M, Esc-menu…) — all of those hang
            // off document-level keydown listeners, so stop every key here.
            e.stopPropagation()
            when (e.asDynamic().key as? String) {
                "Enter" -> finish(true)
                "Escape" -> finish(false)
                else -> {}
            }
        }
        input.onblur = { finish(true) }
        input.onclick = { e -> e.stopPropagation() }
        input.focus()
        input.select()
    }

    /** uName/uImage hover tooltip: full name (uName's own #uc-nameline slot truncates with
     *  ellipsis at narrow card widths — the tooltip is the fallback to actually read it), class,
     *  country, available-from and field notes. unit.flag (not data.country) for the country:
     *  data.country is the equipment catalogue's own index, which reads "Unknown" for a live
     *  unit — the same pitfall the enemy card hit and was fixed for. */
    private fun equipmentCardTooltip(unit: GameUnit, data: EquipmentData, fullName: String): String {
        val cls = unitClassNames.getOrNull(data.uclass) ?: ""
        val country = Equipment.getCountryName(unit.flag - 1)
        val header = "$fullName\n$cls · $country · ${equipmentAvailabilityText(data)}"
        val description = equipmentDescriptionOrNull(data)
        return if (description != null) "$header\n\n$description" else header
    }

    fun showEquipmentInfo(eq: EquipmentData?) {
        if (eq == null) return
        if (!equipmentWindowOpen() && !uiSettings.unitInfoVisibility) return
        makeVisible("unit-info")
        byId("inspectunit")?.let { toggleButton(it, true) }

        clearTag("uTransport")
        clearTag("uCarrier")
        delTag(byId("leaderInfo"))
        byId("uLeader")?.className = "uc-leader-slot"
        byId("uLeader")?.title = "Leader slot — empty"
        byId("uTransport")?.className = ""
        byId("uCarrier")?.className = ""
        byId("statsRow")?.let { makeVisible(it.id) }

        val data = eq
        val ammo = data.ammo
        var fuelStr = "-"
        if (data.fuel > 0) fuelStr = data.fuel.toString()
        if (data.gunrange == 0) data.gunrange = 1

        byId("uImage")?.style?.backgroundImage = "url(${data.icon})"
        byId("uSmallFlag")?.style?.backgroundPosition = "${-21 * (data.country - 1)}px 0px"
        byId("uFlag")?.style?.backgroundImage =
            "url('resources/ui/flags/${Equipment.unitedName}/flag_big_${data.country}.png')"
        byId("uFlag")?.textContent = Equipment.getCountryName(data.country - 1)
        byId("uName")?.textContent = "${data.name} ${unitClassNames[data.uclass]}"
        byId("ucRename")?.style?.display = "none" // catalogue entries aren't renamable
        byId("uTarget")?.textContent = unitTypeNames[data.target]
        byId("uMoveType")?.textContent =
            if (data.uclass <= UnitClass.AIR_DEFENCE.value &&
                data.movmethod == MovMethod.DEEP_NAVAL.value
            ) {
                "Rail Road"
            } else {
                movMethodNames[data.movmethod]
            }
        byId("uStr")?.textContent = "10/10"
        byId("uFuel")?.innerHTML = fuelStr
        byId("uAmmo")?.innerHTML = ammo.toString()
        byId("uGunRange")?.textContent = data.gunrange.toString()
        byId("uMovement")?.textContent = data.movpoints.toString()
        byId("uExp")?.textContent = "0"
        byId("uEnt")?.textContent = "0"
        byId("uIni")?.textContent = data.initiative.toString()
        byId("uSpot")?.textContent = data.spotrange.toString()
        byId("uAHard")?.textContent = data.hardatk.toString()
        byId("uASoft")?.textContent = data.softatk.toString()
        byId("uAAir")?.textContent = data.airatk.toString()
        byId("uANaval")?.textContent = data.navalatk.toString()
        byId("uDHard")?.textContent = data.grounddef.toString()
        byId("uDAir")?.textContent = data.airdef.toString()
        byId("uDClose")?.textContent = data.closedef.toString()
        byId("uDRange")?.textContent = data.rangedefmod.toString()

        // Bars: a browsed equipment entry has no live unit state, so show full strength/ammo
        // (matches the "10/10" text above) and hide fuel unless this equipment type uses it.
        byId("uStrBarFill")?.style?.width = "100%"
        byId("uStrBarFillValue")?.textContent = "10/10"
        byId("uAmmoBarFill")?.style?.width = "100%"
        byId("uAmmoBarFillValue")?.textContent = "$ammo/$ammo"
        byId("uFuelBarFillRow")?.style?.display = if (data.fuel > 0) "flex" else "none"
        if (data.fuel > 0) {
            byId("uFuelBarFill")?.style?.width = "100%"
            byId("uFuelBarFillValue")?.textContent = "${data.fuel}/${data.fuel}"
        }
        byId("osadaUcStars")?.textContent = "☆☆☆☆☆"
        byId("osadaUcStars")?.title = "Experience: 0/$UNIT_MAX_EXPERIENCE"
        byId("osadaUcEnt")?.textContent = ""
    }

    private var lastHoverRow = -1
    private var lastHoverCol = -1

    /** Forces the next [updateHoverInfo] call to recompute even if the cursor hasn't moved
     *  (used when the SELECTION changes, since the cache only tracks cell position). */
    private fun resetHoverCache() {
        lastHoverRow = -1
        lastHoverCol = -1
    }

    /** Bottom-zone forecast+enemy-card on attack-target hover (Task 3). Reuses the EXACT same
     *  attack-availability check the existing cursor forecast and click-to-attack path already
     *  use (`hex.isAttackSel` + `getAttackableUnit`) — no new range/LOS math, and the bottom zone
     *  can only ever show what those paths already reveal (fog-of-war safe). Guarded by a
     *  last-cell cache so mousemove stays cheap. */
    fun updateHoverInfo(row: Int, col: Int) {
        if (row == lastHoverRow && col == lastHoverCol) return
        lastHoverRow = row
        lastHoverCol = col
        val map = ui.game.scenario?.map ?: return
        if (row < 0 || row >= map.rows || col < 0 || col >= map.cols) return
        val hex = map.map?.get(row)?.get(col) ?: return

        val selected = map.currentUnit
        val target = if (selected != null && hex.isAttackSel && !selected.hasFired) {
            hex.getAttackableUnit(selected, uiSettings.airMode)
        } else {
            null
        }

        if (selected != null && target != null) {
            val ownSide = map.currentPlayer?.side ?: return
            BottomZoneBuilder.renderForecast(selected, target, ownSide)
        } else {
            BottomZoneBuilder.onHoverLeft()
        }

        // Task 6 hover-preview extension (measured comfortably under the ~5ms budget — see
        // AttackRingBuilder's doc comment): while hovering a reachable MOVE hex (not an attack
        // target — a different hex category), preview which enemies would become attackable
        // from THAT hex; otherwise revert to the rings for the unit's actual current position.
        if (selected != null && hex.isMoveSel) {
            AttackRingBuilder.previewFromHover(row, col)
        } else {
            AttackRingBuilder.revertHoverPreview()
        }
    }

    /** Foreign-unit inspection (right-click, or a plain click with nothing own selected) — the
     *  enemy card, never `#unit-info` (which is now reserved for the player's own unit, Task 3). */
    fun showEnemyCard(unit: GameUnit) {
        val ownSide = ui.game.scenario?.map?.currentPlayer?.side ?: return
        BottomZoneBuilder.showEnemyAlone(unit, ownSide)
    }

    private fun htmlRed(value: Any): String = "<span style='color: #FF6347'>$value</span>"
}
