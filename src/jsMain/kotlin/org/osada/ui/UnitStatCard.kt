package org.osada.ui

import kotlinx.browser.document
import org.osada.MovMethod
import org.osada.UNIT_MAX_EXPERIENCE
import org.osada.UNIT_NAME_MAX_LENGTH
import org.osada.UnitClass
import org.osada.hero.HeroCampaign
import org.osada.hero.HeroEventDisplay
import org.osada.i18n.GameText
import org.osada.i18n.I18n
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.Leaders
import org.osada.model.getCountryName
import org.osada.rules.GameRules
import org.osada.rules.airGroundedByWeather
import org.osada.rules.isAir
import org.osada.rules.unitUsesFuel
import org.osada.uiSettings
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.MouseEvent

/**
 * [UnitInfoPanel]'s live-unit stat card (`#unit-info` filled from a [GameUnit]) and its inline
 * rename affordance. Split out purely to keep [UnitInfoPanel] within the project's
 * function-count/class-size limits -- not expected to be called from elsewhere. The
 * equipment-catalogue (browse-only) variant lives in [EquipmentStatCard].
 */
internal class UnitStatCard(
    private val ui: UI,
    private val hoverForecast: UnitHoverForecast,
) {
    private var renameActive = false

    fun showUnitInfo(unit: GameUnit?) {
        // Never repaint the card out from under an in-progress rename — the inline input
        // would be destroyed mid-typing (blur/commit paths call back in here themselves).
        // Auto-show on selection (modern HUD): only the explicit Inspect Unit toggle
        // (unitInfoVisibility=false) suppresses the panel, not its current visibility.
        val canShow = unit != null && !renameActive && (equipmentWindowOpen() || uiSettings.unitInfoVisibility)
        if (unit == null || !canShow) return
        makeVisible("unit-info")
        byId("inspectunit")?.let { toggleButton(it, true) }

        clearTag("uTransport")
        clearTag("uCarrier")
        delTag(byId("leaderInfo"))
        byId("uLeader")?.className = "uc-leader-slot"
        byId("uLeader")?.textContent = ""
        byId("uTransport")?.className = ""
        byId("uCarrier")?.className = ""
        byId("statsRow")?.let { makeVisible(it.id) }
        // Fresh selection invalidates any in-flight hover forecast context (it referred to
        // whatever unit was selected before); force the next mouse-move to recompute.
        hoverForecast.resetHoverCache()

        val data = if (unit.carrier > 0 && !unit.isMounted) unit.unitData(true) else unit.unitData()
        if (data.gunrange == 0) data.gunrange = 1

        fillUnitHeader(unit, data)
        fillUnitStatFields(unit, data)
        fillUnitCarrierSlot(unit)
        fillUnitTransportSlot(unit)
        fillUnitLeaderSlot(unit)
        fillFormationDetail(unit)
        fillUnitStatBars(unit, data)
        fillUnitBadges(unit)

        // Bottom-zone state: an own unit is now showing in the player-card slot.
        if (unit.player?.id ==
            ui.game.scenario
                ?.map
                ?.currentPlayer
                ?.id
        ) {
            BottomZoneBuilder.setState("own")
        }
    }

    private fun fillUnitHeader(
        unit: GameUnit,
        data: EquipmentData,
    ) {
        val ordinal = if (data.uclass != UnitClass.FORTIFICATION.value) UIBuilder.unitIDToOrdinal(unit.id) else ""
        val coreText = if (unit.isCore) " (Core Unit)" else ""
        val leaderText = if (HeroCampaign.hasAnyCommander(unit)) " (Leader)" else ""

        byId("uImage")?.style?.backgroundImage =
            "url(${UnitIconResolver.forCurrentScenario(data.eqid, data.icon)})"
        byId("uSmallFlag")?.style?.backgroundPosition = "${-FLAG_SPRITE_WIDTH * (unit.flag - 1)}px 0px"
        byId("uFlag")?.style?.backgroundImage =
            "url('resources/ui/flags/${Equipment.UNITED_NAME}/flag_big_${unit.flag}.png')"
        byId("uFlag")?.textContent = Equipment.getCountryName(unit.flag - 1)
        val equipmentLabel = "$ordinal ${data.name} ${GameText.unitClass(data.uclass)}$coreText$leaderText"
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
        EquipmentMarkings.render(byId("osadaUcMarkings"), data, unit, extended = true)

        // Rename affordance (Stage 3.5, Task 2): own units only — never enemies, never
        // browsed equipment entries (showEquipmentInfo hides it).
        byId("ucRename")?.let { btn ->
            val own =
                unit.player?.id ==
                    ui.game.scenario
                        ?.map
                        ?.currentPlayer
                        ?.id
            btn.style.display = if (own) "" else "none"
            btn.onclick = { e: MouseEvent ->
                e.stopPropagation()
                startRename(unit)
            }
        }
    }

    private fun fillUnitStatFields(
        unit: GameUnit,
        data: EquipmentData,
    ) {
        val ammo = unit.getAmmo()
        var ammoDisplay = ammo.toString()
        var fuelStr = "-"
        if (GameRules.unitUsesFuel(unit)) fuelStr = unit.getFuel().toString()
        if (ammo < data.ammo / LOW_SUPPLY_WARNING_DIVISOR) ammoDisplay = htmlRed(ammo)
        if (fuelStr != "-" && (fuelStr.toIntOrNull() ?: 0) < data.fuel / LOW_SUPPLY_WARNING_DIVISOR) {
            fuelStr = htmlRed(fuelStr)
        }

        byId("uTarget")?.textContent = GameText.unitType(data.target)
        byId("uMoveType")?.textContent =
            if (data.uclass <= UnitClass.AIR_DEFENCE.value &&
                data.movmethod == MovMethod.DEEP_NAVAL.value
            ) {
                I18n.t("game.movement_type.rail_road")
            } else {
                GameText.movementType(data.movmethod)
            }
        byId("uStr")?.textContent = "${unit.strength}/10"
        byId("uFuel")?.innerHTML = fuelStr
        byId("uAmmo")?.innerHTML = ammoDisplay
        byId("uGunRange")?.textContent = data.gunrange.toString()
        byId("uMovement")?.textContent = data.movpoints.toString()
        byId("uExp")?.textContent = unit.experience.toString()
        byId("uEnt")?.textContent = unit.entrenchment.toString()
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
        // Must run LAST: it rewrites the chips above to their effective values (DEFERRED.md §1.4).
        UnitStatAttachmentMarks.apply(unit)
    }

    private fun fillUnitCarrierSlot(unit: GameUnit) {
        if (unit.carrier <= 0) return
        val carrierBtn = byId("uCarrier")
        carrierBtn?.className = "osada-slot"
        carrierBtn?.innerHTML = "2"
        carrierBtn?.title = I18n.t("unit_info.carrier.help")
        carrierBtn?.onclick = { _: MouseEvent ->
            Equipment.getEquipment(unit.carrier)?.let { ui.showEquipmentInfo(it) }
        }
    }

    private fun fillUnitTransportSlot(unit: GameUnit) {
        unit.transport ?: return
        val transportBtn = byId("uTransport")
        transportBtn?.className = "osada-slot"
        transportBtn?.innerHTML = if (unit.isMounted) "9" else "8"
        transportBtn?.title =
            if (unit.isMounted) {
                I18n.t("unit_info.transport.mounted.help")
            } else {
                I18n.t("unit_info.transport.dismounted.help")
            }
        val switchMount = !unit.isMounted
        transportBtn?.onclick = { _: MouseEvent ->
            val wasMounted = unit.isMounted
            unit.isMounted = switchMount
            showUnitInfo(unit)
            unit.isMounted = wasMounted
        }
    }

    // Hero slot (§14.2): a campaign core unit whose formation has an emerged hero shows their name,
    // rank and traits — and CLICKS INTO THE DOSSIER (§14.4, §29.8). A core formation without a hero
    // shows its recognition status (§7.1). Scenario-only units keep the legacy integer-leader
    // tooltip. Unit veteran experience stays in its own stat/stars, distinct from hero effects (§4.6).
    @Suppress("LongMethod")
    private fun fillUnitLeaderSlot(unit: GameUnit) {
        val leaderBtn = byId("uLeader")
        val dossier = HeroCampaign.dossier(unit)
        if (dossier != null) {
            leaderBtn?.textContent = ""
            leaderBtn?.classList?.add("uc-leader-slot--filled", "uc-leader-slot--hero")
            val traits = dossier.traits.joinToString(", ") { it.title }
            leaderBtn?.title =
                buildString {
                    append("${dossier.rank} ${dossier.name}\n${dossier.potential}")
                    if (traits.isNotEmpty()) append("\n$traits")
                    append("\n${I18n.t("unit_info.leader.open_dossier.help")}")
                }
            leaderBtn?.onclick = { e: MouseEvent ->
                e.stopPropagation()
                LeaderDossierPresenter.openForUnit(unit)
            }
            return
        }
        leaderBtn?.onclick = null
        if (unit.leader >= 0) {
            leaderBtn?.textContent = ""
            leaderBtn?.classList?.add("uc-leader-slot--filled")
            val descriptions = Leaders.getUnitLeaderDescriptions(unit)
            leaderBtn?.title =
                if (descriptions.isNotEmpty()) {
                    descriptions.joinToString("\n") { "${it.first}: ${it.second}" }
                } else {
                    I18n.t("combat.enemy.commander.fallback")
                }
        } else {
            val progress = HeroCampaign.recognitionProgress(unit)
            if (progress != null) {
                leaderBtn?.classList?.add("uc-leader-slot--recognition")
                leaderBtn?.textContent =
                    "●".repeat(progress.filledStages) + "○".repeat(RECOGNITION_STAGES - progress.filledStages)
                val chanceLine =
                    when {
                        progress.recognition < progress.target ->
                            I18n.t("unit_info.leader.checks_unlock", mapOf("target" to progress.target))

                        progress.drought >= progress.guaranteedAfterFailures ->
                            I18n.t("unit_info.leader.guaranteed")

                        else ->
                            I18n.t(
                                "unit_info.leader.chance",
                                mapOf("chance" to progress.chancePercent),
                            )
                    }
                leaderBtn?.title =
                    I18n.t(
                        "unit_info.leader.candidate.help",
                        mapOf(
                            "recognition" to progress.recognition,
                            "chanceLine" to chanceLine,
                            "drought" to progress.drought,
                            "guaranteedAfter" to progress.guaranteedAfterFailures,
                        ),
                    )
            } else {
                leaderBtn?.textContent = ""
                leaderBtn?.title = I18n.t("unit_info.leader.empty.help")
            }
        }
    }

    // Three labeled bars (spec): Strength green, Ammo brass, Fuel brass (hidden if none).
    // Numbers live ON the bar (in the track); the row's tooltip explains only what the stat
    // MEANS — the number is already visible, repeating it in the tooltip would be redundant.
    private fun fillUnitStatBars(
        unit: GameUnit,
        data: EquipmentData,
    ) {
        byId("uStrBarFill")?.style?.width =
            "${(unit.strength / FULL_STRENGTH * PERCENT_SCALE).coerceIn(0.0, PERCENT_SCALE)}%"
        byId("uStrBarFillValue")?.textContent = "${unit.strength}/10"
        val ammo = unit.getAmmo()
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
    }

    // Experience stars: a simple 5-star scale over the existing raw experience value (the
    // number itself still lives in #uExp, in the "All stats" expander) — display only,
    // no rule change.
    private fun fillUnitBadges(unit: GameUnit) {
        val experience = unit.experience
        val entrenchment = unit.entrenchment
        val starCount = ((experience.toDouble() / UNIT_MAX_EXPERIENCE) * 5).toInt().coerceIn(0, 5)
        byId("osadaUcStars")?.textContent = "★".repeat(starCount) + "☆".repeat(MAX_EXPERIENCE_STARS - starCount)
        byId("osadaUcStars")?.title =
            I18n.t(
                "unit_info.experience.value",
                mapOf("experience" to experience, "max" to UNIT_MAX_EXPERIENCE),
            )
        byId("osadaUcEnt")?.textContent = if (entrenchment > 0) "⛨$entrenchment" else ""
        byId("osadaUcEnt")?.title =
            I18n.t("unit_info.entrenchment.value", mapOf("value" to entrenchment))
        val grounded = GameRules.isAir(unit) && GameRules.airGroundedByWeather(unit)
        byId("osadaUcWeather")?.textContent = if (grounded) "⚠" else ""
        byId("osadaUcWeather")?.title =
            if (grounded) I18n.t("unit_info.grounded.help") else ""
    }

    /** Swaps #uName for a text input in place (no modal). Enter/blur commit, Esc cancels;
     *  committing an empty value clears the custom name (back to the equipment label).
     *  Sanitizing: trim + 24-char cap. */
    private fun startRename(unit: GameUnit) {
        val nameEl = byId("uName")
        val parent = nameEl?.parentElement
        if (renameActive || nameEl == null || parent == null) return
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
            // ...and the identity layer on top of it. Without this the rename repainted the card
            // through THIS class alone, and #uLeader is written by both: `fillUnitLeaderSlot`
            // leaves it as the equipment-catalogue star SLOT, `UnitIdentityPresenter` turns it
            // back into the commander LINE. Skipping the presenter left the star behind --
            // reported as *"the #uLeader icon only appears if you press rename"*. It also left
            // the commander line, status chips and badge row showing the previous unit's state.
            UnitIdentityPresenter.present(ui, unit)
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
    private fun equipmentCardTooltip(
        unit: GameUnit,
        data: EquipmentData,
        fullName: String,
    ): String {
        val cls = GameText.unitClass(data.uclass)
        val country = Equipment.getCountryName(unit.flag - 1)
        val header = "$fullName\n$cls · $country · ${equipmentAvailabilityText(data)}"
        val description = equipmentDescriptionOrNull(data)
        val mechanics = equipmentMechanicsNote(data)
        return listOfNotNull(header, description, mechanics).joinToString("\n\n")
    }

    private fun htmlRed(value: Any): String = "<span style='color: #FF6347'>$value</span>"

    companion object {
        private const val LOW_SUPPLY_WARNING_DIVISOR = 4
        private const val FLAG_SPRITE_WIDTH = 21
        private const val FULL_STRENGTH = 10.0
        private const val PERCENT_SCALE = 100.0
        private const val MAX_EXPERIENCE_STARS = 5
        private const val RECOGNITION_STAGES = 3
    }
}

/** Exact recognition and recent formation history live visibly in All Stats. */
private fun fillFormationDetail(unit: GameUnit) {
    delTag(byId("osadaFormationDetail"))
    val container = byId("statsRowContainer")
    val formation = HeroCampaign.formationFor(unit)
    val progress = HeroCampaign.recognitionProgress(unit)
    if (container == null || (formation == null && progress == null)) return
    val detail = addTag(container, "div")
    detail.id = "osadaFormationDetail"
    detail.className = "osada-formation-detail"
    val headline = addTag(detail, "div")
    headline.className = "osada-formation-detail__headline"
    headline.textContent =
        if (progress != null) {
            if (progress.recognition < progress.target) {
                "Recognition ${progress.recognition} — hero emergence checks unlock at ${progress.target}"
            } else {
                "Recognition ${progress.recognition} — next chance ${progress.chancePercent}% · " +
                    "protection ${progress.drought}/${progress.guaranteedAfterFailures}"
            }
        } else {
            "Formation record — heroic commander present"
        }
    formation?.history?.takeLast(RECENT_FORMATION_EVENTS)?.forEach { event ->
        val row = addTag(detail, "div")
        row.className = "osada-formation-detail__event"
        row.textContent =
            HeroEventDisplay.title(event.eventId) +
            HeroEventDisplay.context(event.scenarioId, event.turn, event.date, event.location)
    }
}

private const val RECENT_FORMATION_EVENTS = 3
