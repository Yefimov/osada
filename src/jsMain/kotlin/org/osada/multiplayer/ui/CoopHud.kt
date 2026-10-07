@file:Suppress("TooManyFunctions", "ReturnCount")

package org.osada.multiplayer.ui

import kotlinx.browser.document
import org.osada.GameHolder
import org.osada.hero.HeroDisplay
import org.osada.hero.HeroPortraitArt
import org.osada.hero.PortraitComposerV2
import org.osada.i18n.I18n
import org.osada.multiplayer.coop.CoopAbilityCatalogue
import org.osada.multiplayer.coop.CoopCommander
import org.osada.multiplayer.coop.CoopSeatSymbols
import org.osada.multiplayer.coop.CoopState
import org.osada.ui.PortraitRenderer
import org.osada.ui.updateTurnControls
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement

/**
 * The cooperative room's in-battle state (`docs/multiplayer/cooperative-commanders.md` §6,
 * §9). The players themselves are shown by [CoopCommanderRoster], opened from the headquarters
 * button or a unit card's commander line, rather than by a panel living on the map.
 *
 * Nothing stays on screen during play. The only thing that pops up by itself is the ability window,
 * and only when a campaign milestone has actually opened a slot the player has not filled yet.
 * Everything shown is also stated in words, never by colour alone.
 */
object CoopHud {
    private const val SLOTS_ID = "osadaCoopSlots"

    class Model(
        val state: CoopState,
        val selfCommanderId: String?,
        val isHost: Boolean,
        val connected: Map<String, Boolean>,
        val gameEnded: Boolean,
        val onSetLoadout: (abilityIds: List<String>) -> Unit,
        val onSetSymbol: (symbol: String) -> Unit,
        val onExcuse: (commanderId: String, excused: Boolean, delegateTo: String?) -> Unit,
    )

    private var lastModel: Model? = null
    private var reportShownFor: String? = null
    private var slotsDismissedFor: String? = null

    fun render(model: Model) {
        lastModel = model
        CoopCommanderRoster.refresh(model)
        offerNewSlot(model)
        // The End Turn button speaks for this room's Done state, so it repaints with it.
        GameHolder.instance?.ui?.updateTurnControls()
        if (model.gameEnded && reportShownFor != model.state.ledger.windowKey + model.state.turnKey) {
            reportShownFor = model.state.ledger.windowKey + model.state.turnKey
            CoopReportView.show(model.state)
        }
    }

    /** Opened by the headquarters button. */
    fun open() {
        val model = lastModel ?: return
        CoopCommanderRoster.openRoster(model)
    }

    fun remove() {
        lastModel = null
        reportShownFor = null
        slotsDismissedFor = null
        CoopCommanderRoster.close()
        document.getElementById(SLOTS_ID)?.remove()
        CoopReportView.hide()
    }

    /**
     * What the End Turn button should say once THIS commander is done and the army is still waiting
     * for others, or null when the plain button applies. The ✗ beside it takes the "done" back.
     */
    fun waitingForOthers(): String? {
        val model = lastModel ?: return null
        val self = model.selfCommanderId ?: return null
        val state = model.state
        if (self !in state.done) return null
        val remaining = state.requiredCommanderIds().filter { it !in state.done }.mapNotNull(state::commanderById)
        if (remaining.isEmpty()) return null
        return I18n.t("multiplayer.coop.hud.waiting_for", mapOf("names" to remaining.joinToString { it.name }))
    }

    fun isRosterOpen(): Boolean = CoopCommanderRoster.isOpen()

    fun closeRoster() = CoopCommanderRoster.close()

    /** The players' dossier, opened by tapping the commander line on a unit card. */
    fun openCommander(commanderId: String) {
        val model = lastModel ?: return
        CoopCommanderDossier.open(model, commanderId)
    }

    /**
     * A clickable symbol picker: the seat number plus every [CoopSeatSymbols] glyph. Used by the
     * lobby card and by your own dossier.
     */
    internal fun symbolPicker(
        seat: Int,
        current: String,
        onPick: (String) -> Unit,
    ): HTMLElement {
        val box = div("osada-coop-symbols")
        (listOf("") + CoopSeatSymbols.ALL).forEach { symbol ->
            val pick = button("")
            pick.className = "osada-coop-seat osada-coop-seat--button osada-coop-symbol"
            fillMark(pick, seat, symbol)
            pick.setAttribute("aria-pressed", (current == symbol).toString())
            pick.title =
                I18n.t(if (symbol.isEmpty()) "multiplayer.coop.symbol.number" else "multiplayer.coop.symbol.$symbol")
            pick.setAttribute("aria-label", pick.title)
            pick.onclick = { event ->
                event.stopPropagation()
                onPick(symbol)
            }
            box.appendChild(pick)
        }
        return box
    }

    /** A commander's map mark as a DOM chip: their chosen glyph, else the seat number. */
    internal fun seatMark(
        commander: CoopCommander,
        className: String,
    ): HTMLElement {
        val el = (document.createElement("span") as HTMLElement).also { it.className = className }
        fillMark(el, commander.seat, commander.symbol)
        return el
    }

    internal fun fillMark(
        el: HTMLElement,
        seat: Int,
        symbol: String,
    ) {
        val svg = CoopSeatSymbols.svg(symbol)
        if (svg != null) el.innerHTML = svg else el.textContent = "${seat + 1}"
    }

    // -- New ability slot ----------------------------------------------------------------------

    /**
     * Abilities are chosen in the lobby. The only time they are asked for again is when a campaign
     * milestone has opened a slot this commander has not filled, before the mission's first order.
     */
    private fun offerNewSlot(model: Model) {
        val state = model.state
        val self = state.commanderById(model.selfCommanderId)
        val key = "${state.missionIndex}:${state.slotCap()}"
        val wanted =
            self != null && state.preBattle() && state.unlocksEarned > 0 && self.abilityIds.size < state.slotCap()
        if (self == null || !wanted) {
            document.getElementById(SLOTS_ID)?.remove()
            return
        }
        if (slotsDismissedFor == key) return
        document.getElementById(SLOTS_ID)?.remove()
        val overlay = div("osada-coop-report osada-coop-roster")
        overlay.id = SLOTS_ID
        overlay.setAttribute("role", "dialog")
        overlay.setAttribute("aria-modal", "true")
        val sheet = div("osada-coop-report-sheet")
        overlay.appendChild(sheet)
        sheet.appendChild(
            text(I18n.t("multiplayer.coop.loadout.new_slot")).apply { className = "osada-coop-report-title" },
        )
        sheet.appendChild(
            text(
                I18n.t(
                    "multiplayer.coop.abilities.title",
                    mapOf("used" to self.abilityIds.size, "cap" to state.slotCap()),
                ),
            ).apply { className = "osada-coop-hud-name" },
        )
        CoopAbilityCatalogue.ALL.forEach { ability ->
            val chosen = ability.id in self.abilityIds
            val line = HeroDisplay.trait(ability.leader, "")
            val pick = button(line.title)
            pick.id = "coopMissionAbility-${ability.id}"
            pick.setAttribute("aria-pressed", chosen.toString())
            pick.title = line.effect
            pick.disabled = !chosen && self.abilityIds.size >= state.slotCap()
            pick.onclick = {
                model.onSetLoadout(if (chosen) self.abilityIds - ability.id else self.abilityIds + ability.id)
            }
            sheet.appendChild(pick)
        }
        val done = button(I18n.t("multiplayer.coop.report.close"))
        done.onclick = {
            slotsDismissedFor = key
            overlay.remove()
        }
        sheet.appendChild(done)
        document.body?.appendChild(overlay)
    }

    // -- Helpers -------------------------------------------------------------------------------

    internal fun portrait(
        commander: CoopCommander,
        className: String,
    ): HTMLElement {
        val frame = div(className)
        frame.textContent = commander.name.take(1).uppercase()
        frame.setAttribute("role", "img")
        frame.setAttribute("aria-label", I18n.t("multiplayer.coop.portrait.alt", mapOf("name" to commander.name)))
        if (commander.portrait.isValid()) {
            PortraitRenderer.render(
                frame,
                PortraitComposerV2.pathsFor(commander.portrait.layerIds),
                commander.portrait.seed,
                artPath = HeroPortraitArt.pathFor(commander.portrait.artId),
            )
        }
        return frame
    }

    internal fun div(className: String): HTMLElement =
        (document.createElement("div") as HTMLElement).also { it.className = className }

    internal fun text(value: String): HTMLElement =
        (document.createElement("div") as HTMLElement).also { it.textContent = value }

    internal fun button(value: String): HTMLButtonElement =
        (document.createElement("button") as HTMLButtonElement).also {
            it.type = "button"
            it.textContent = value
            it.className = "osada-mp-button"
        }
}
