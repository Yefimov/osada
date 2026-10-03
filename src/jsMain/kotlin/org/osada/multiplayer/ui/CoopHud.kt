@file:Suppress("TooManyFunctions", "ReturnCount")

package org.osada.multiplayer.ui

import kotlinx.browser.document
import org.osada.GameHolder
import org.osada.hero.HeroDisplay
import org.osada.hero.HeroPortraitArt
import org.osada.hero.PortraitComposerV2
import org.osada.i18n.I18n
import org.osada.model.GameUnit
import org.osada.model.getUnitById
import org.osada.multiplayer.coop.CoopAbilityCatalogue
import org.osada.multiplayer.coop.CoopCommander
import org.osada.multiplayer.coop.CoopRuntime
import org.osada.multiplayer.coop.CoopState
import org.osada.ui.PortraitRenderer
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement

/**
 * The in-battle commander panel of a cooperative room (`docs/design/cooperative-commanders-multiplayer.md`
 * §6, §9): who is in the room, who is done, which commander holds the SELECTED unit and why a chosen
 * ability does or does not apply to it, pending transfers, and the host's "continue without" control.
 *
 * Everything shown is also stated in words -- a seat number, "done", "away" -- never by colour alone,
 * and nothing here is hover-only: the unit explanation is a visible block so touch players see it too.
 * It only renders; every action goes back through the model's callbacks as an ordinary order.
 */
object CoopHud {
    private const val PANEL_ID = "osadaCoopPanel"

    class Model(
        val state: CoopState,
        val selfCommanderId: String?,
        val isHost: Boolean,
        val connected: Map<String, Boolean>,
        val gameEnded: Boolean,
        val checkpointDurable: Boolean,
        val onExportCheckpoint: () -> Unit,
        val onToggleDone: () -> Unit,
        val onTransferProposal: (unitId: Int, toCommanderId: String?) -> Unit,
        val onTransferAnswer: (unitId: Int, accept: Boolean) -> Unit,
        val onSetLoadout: (abilityIds: List<String>) -> Unit,
        val onReserveUnit: (unitId: Int, reserve: Boolean) -> Unit,
        val onExcuse: (commanderId: String, excused: Boolean, delegateTo: String?) -> Unit,
    )

    private var lastModel: Model? = null
    private var selectedUnitId: Int? = null
    private var collapsed = false
    private var reportShownFor: String? = null

    fun render(model: Model) {
        lastModel = model
        val panel = ensurePanel()
        panel.innerHTML = ""
        panel.classList.toggle("is-collapsed", collapsed)
        panel.appendChild(header(model))
        if (!collapsed) {
            missionBanner(model)?.let(panel::appendChild)
            panel.appendChild(commanderList(model))
            loadoutEditor(model)?.let(panel::appendChild)
            panel.appendChild(doneRow(model))
            selectedUnitBlock(model)?.let(panel::appendChild)
            transferBlock(model)?.let(panel::appendChild)
            panel.appendChild(CoopEconomyView.render(model.state, model.selfCommanderId))
        }
        if (model.gameEnded && reportShownFor != model.state.ledger.windowKey + model.state.turnKey) {
            reportShownFor = model.state.ledger.windowKey + model.state.turnKey
            CoopReportView.show(model.state)
        }
    }

    /** The unit card asks for this when the selection changes, so the explanation tracks it. */
    fun onUnitSelected(unit: GameUnit?) {
        selectedUnitId = unit?.id
        lastModel?.let(::render)
    }

    fun remove() {
        lastModel = null
        selectedUnitId = null
        reportShownFor = null
        document.getElementById(PANEL_ID)?.remove()
        CoopReportView.hide()
    }

    // -- Pieces --------------------------------------------------------------------------------

    private fun header(model: Model): HTMLElement {
        val head = div("osada-coop-hud-head")
        head.appendChild(
            text(I18n.t("multiplayer.coop.hud.title")).apply { className = "osada-coop-hud-title" },
        )
        val report = button(I18n.t("multiplayer.coop.hud.report"))
        report.onclick = { CoopReportView.show(model.state) }
        head.appendChild(report)
        val export = button(I18n.t("multiplayer.coop.recovery.export"))
        export.id = "coopCheckpointExport"
        export.onclick = { model.onExportCheckpoint() }
        head.appendChild(export)
        if (!model.checkpointDurable) {
            head.appendChild(text(I18n.t("multiplayer.coop.recovery.storage_failed")))
        }
        val toggle = button(I18n.t(if (collapsed) "multiplayer.coop.hud.expand" else "multiplayer.coop.hud.collapse"))
        toggle.setAttribute("aria-expanded", (!collapsed).toString())
        toggle.onclick = {
            collapsed = !collapsed
            render(model)
        }
        head.appendChild(toggle)
        return head
    }

    private fun commanderList(model: Model): HTMLElement {
        val list = div("osada-coop-hud-list")
        val state = model.state
        state.commanders.forEach { commander ->
            val row = div("osada-coop-hud-row" + if (commander.id == model.selfCommanderId) " is-self" else "")
            row.appendChild(text("${commander.seat + 1}").apply { className = "osada-coop-seat" })
            row.appendChild(portrait(commander, "osada-coop-portrait osada-coop-portrait--small"))
            val info = div("osada-coop-hud-info")
            info.appendChild(text(commander.name).apply { className = "osada-coop-hud-name" })
            info.appendChild(
                text(
                    I18n.t(
                        "multiplayer.coop.hud.units",
                        mapOf("count" to state.unitsOf(commander.id).size),
                    ),
                ).apply { className = "osada-coop-hud-sub" },
            )
            row.appendChild(info)
            row.appendChild(text(statusOf(model, commander)).apply { className = "osada-coop-hud-state" })
            list.appendChild(row)
            if (model.isHost && model.connected[commander.participantId] == false && commander.id !in state.excused) {
                list.appendChild(excuseControl(model, commander))
            }
        }
        return list
    }

    private fun statusOf(
        model: Model,
        commander: CoopCommander,
    ): String =
        when {
            commander.id in model.state.excused -> I18n.t("multiplayer.coop.hud.excused")
            model.connected[commander.participantId] == false -> I18n.t("multiplayer.coop.hud.away")
            commander.id in model.state.done -> I18n.t("multiplayer.coop.hud.done")
            else -> I18n.t("multiplayer.coop.hud.planning")
        }

    /** The host's explicit decision to carry on without an absent seat; never automatic (§10). */
    private fun excuseControl(
        model: Model,
        absent: CoopCommander,
    ): HTMLElement {
        val box = div("osada-coop-hud-excuse")
        val substitute = model.state.commanders.firstOrNull { it.id != absent.id && it.id !in model.state.excused }
        val label =
            if (substitute != null) {
                I18n.t("multiplayer.coop.hud.excuse.delegate", mapOf("name" to absent.name, "to" to substitute.name))
            } else {
                I18n.t("multiplayer.coop.hud.excuse", mapOf("name" to absent.name))
            }
        val go = button(label)
        go.onclick = { model.onExcuse(absent.id, true, substitute?.id) }
        box.appendChild(go)
        return box
    }

    private fun doneRow(model: Model): HTMLElement {
        val row = div("osada-coop-hud-done")
        val self = model.selfCommanderId
        val state = model.state
        val remaining = state.requiredCommanderIds().filter { it !in state.done }.mapNotNull(state::commanderById)
        row.appendChild(
            text(
                if (remaining.isEmpty()) {
                    I18n.t("multiplayer.coop.hud.all_done")
                } else {
                    I18n.t("multiplayer.coop.hud.waiting_for", mapOf("names" to remaining.joinToString { it.name }))
                },
            ).apply { className = "osada-coop-hud-sub" },
        )
        if (self != null) {
            val isDone = self in state.done
            val toggle =
                button(I18n.t(if (isDone) "multiplayer.coop.hud.undo_done" else "multiplayer.coop.hud.mark_done"))
            toggle.id = "coopDoneButton"
            toggle.classList.add("osada-mp-button--primary")
            toggle.setAttribute("aria-pressed", isDone.toString())
            toggle.onclick = { model.onToggleDone() }
            row.appendChild(toggle)
        }
        return row
    }

    // -- Mission and loadout -------------------------------------------------------------------

    /** Where the campaign stands, and the one thing a player must be told: the host carries it on. */
    private fun missionBanner(model: Model): HTMLElement? {
        val state = model.state
        val lines = mutableListOf<String>()
        if (state.campaignTotal > 0) {
            lines +=
                I18n.t(
                    "multiplayer.coop.mission.progress",
                    mapOf("number" to state.missionIndex + 1, "total" to state.campaignTotal),
                )
        }
        if (model.gameEnded) {
            lines +=
                I18n.t(
                    if (model.isHost) "multiplayer.coop.mission.over.host" else "multiplayer.coop.mission.over.guest",
                )
        }
        if (lines.isEmpty()) return null
        val box = div("osada-coop-hud-unit")
        lines.forEach { box.appendChild(text(it).apply { className = "osada-coop-hud-sub" }) }
        return box
    }

    /**
     * Abilities can be changed until the mission's first order, which is how a newly earned slot gets
     * filled by its player's own choice. Once the battle begins the same block simply shows what is
     * locked in, so nobody wonders why the buttons went away.
     */
    private fun loadoutEditor(model: Model): HTMLElement? {
        val self = model.state.commanderById(model.selfCommanderId) ?: return null
        val state = model.state
        val box = div("osada-coop-hud-unit")
        val cap = state.slotCap()
        if (!state.preBattle()) {
            box.appendChild(text(I18n.t("multiplayer.coop.loadout.locked")).apply { className = "osada-coop-hud-sub" })
            return box
        }
        box.appendChild(
            text(I18n.t("multiplayer.coop.abilities.title", mapOf("used" to self.abilityIds.size, "cap" to cap)))
                .apply { className = "osada-coop-hud-name" },
        )
        if (state.unlocksEarned > 0 && self.abilityIds.size < cap) {
            box.appendChild(
                text(I18n.t("multiplayer.coop.loadout.new_slot")).apply { className = "osada-coop-hud-sub" },
            )
        }
        CoopAbilityCatalogue.ALL.forEach { ability ->
            val chosen = ability.id in self.abilityIds
            val line = HeroDisplay.trait(ability.leader, "")
            val button = button(line.title)
            button.id = "coopMissionAbility-${ability.id}"
            button.setAttribute("aria-pressed", chosen.toString())
            button.title = line.effect
            button.disabled = !chosen && self.abilityIds.size >= cap
            button.onclick = {
                model.onSetLoadout(if (chosen) self.abilityIds - ability.id else self.abilityIds + ability.id)
            }
            box.appendChild(button)
        }
        return box
    }

    // -- The selected unit ---------------------------------------------------------------------

    private fun selectedUnitBlock(model: Model): HTMLElement? {
        val unitId = selectedUnitId ?: return null
        val coop = model.state
        val unit =
            GameHolder.instance
                ?.scenario
                ?.map
                ?.getUnitById(unitId) ?: return null
        if (unit.owner != coop.humanPlayerId) return null
        val box = div("osada-coop-hud-unit")
        val ownerId = coop.ownerOf(unit.id)
        val owner = coop.commanderById(ownerId)
        box.appendChild(
            text(unit.unitData(true).name).apply { className = "osada-coop-hud-name" },
        )
        box.appendChild(
            text(
                if (owner != null) {
                    I18n.t("multiplayer.coop.unit.commanded_by", mapOf("name" to owner.name, "seat" to owner.seat + 1))
                } else {
                    I18n.t("multiplayer.coop.unit.unassigned")
                },
            ).apply { className = "osada-coop-hud-sub" },
        )
        // The commander whose abilities would apply: the owner, or -- for an unclaimed unit -- the
        // viewer, who would claim it with a first order. Previewing never commits a claim.
        val lens = owner ?: coop.commanderById(model.selfCommanderId)
        lens?.let { box.appendChild(abilityExplanation(it, unit, claimed = owner != null)) }
        box.appendChild(transferControls(model, unit, owner))
        reserveControls(model, unit, owner)?.let(box::appendChild)
        return box
    }

    /** Before the first order: reserve this unit for yourself, or give it back, without moving it. */
    private fun reserveControls(
        model: Model,
        unit: GameUnit,
        owner: CoopCommander?,
    ): HTMLElement? {
        val self = model.selfCommanderId
        if (self == null || !model.state.preBattle()) return null
        val row = div("osada-coop-hud-transfer")
        when {
            owner == null -> {
                val reserve = button(I18n.t("multiplayer.coop.board.reserve"))
                reserve.onclick = { model.onReserveUnit(unit.id, true) }
                row.appendChild(reserve)
            }

            owner.id == self -> {
                val release = button(I18n.t("multiplayer.coop.board.release"))
                release.onclick = { model.onReserveUnit(unit.id, false) }
                row.appendChild(release)
            }
        }
        return row
    }

    private fun abilityExplanation(
        commander: CoopCommander,
        unit: GameUnit,
        claimed: Boolean,
    ): HTMLElement {
        val box = div("osada-coop-hud-abilities")
        box.appendChild(
            text(
                I18n.t(
                    if (claimed) "multiplayer.coop.unit.abilities" else "multiplayer.coop.unit.abilities_preview",
                    mapOf("name" to commander.name),
                ),
            ).apply { className = "osada-coop-hud-sub" },
        )
        val uclass = unit.unitData().uclass
        if (commander.abilityIds.isEmpty()) {
            box.appendChild(text(I18n.t("multiplayer.coop.abilities.none")).apply { className = "osada-coop-hud-sub" })
        }
        commander.abilityIds.mapNotNull(CoopAbilityCatalogue::byId).forEach { ability ->
            val line = HeroDisplay.trait(ability.leader, "")
            val applies = CoopAbilityCatalogue.appliesTo(ability, uclass)
            val row = div("osada-coop-hud-ability" + if (applies) " is-active" else " is-inactive")
            row.appendChild(text(line.title).apply { className = "osada-coop-hud-name" })
            row.appendChild(
                text(
                    if (applies) {
                        I18n.t("multiplayer.coop.unit.ability_active", mapOf("when" to line.activation))
                    } else {
                        I18n.t("multiplayer.coop.unit.ability_inactive")
                    },
                ).apply { className = "osada-coop-hud-sub" },
            )
            box.appendChild(row)
        }
        return box
    }

    private fun transferControls(
        model: Model,
        unit: GameUnit,
        owner: CoopCommander?,
    ): HTMLElement {
        val row = div("osada-coop-hud-transfer")
        val self = model.selfCommanderId ?: return row
        val state = model.state
        if (owner == null || state.transfers.any { it.unitId == unit.id }) return row
        if (owner.id == self) {
            // Offer to a teammate. Both must agree and it lands at the next friendly turn.
            state.commanders.filter { it.id != self }.forEach { teammate ->
                val offer = button(I18n.t("multiplayer.coop.transfer.offer", mapOf("name" to teammate.name)))
                offer.onclick = { model.onTransferProposal(unit.id, teammate.id) }
                row.appendChild(offer)
            }
        } else {
            val ask = button(I18n.t("multiplayer.coop.transfer.ask", mapOf("name" to owner.name)))
            ask.onclick = { model.onTransferProposal(unit.id, null) }
            row.appendChild(ask)
        }
        return row
    }

    private fun transferBlock(model: Model): HTMLElement? {
        val self = model.selfCommanderId ?: return null
        val state = model.state
        val map = GameHolder.instance?.scenario?.map
        val mine = state.transfers.filter { it.fromId == self || it.toId == self }
        if (mine.isEmpty()) return null
        val box = div("osada-coop-hud-transfers")
        box.appendChild(
            text(I18n.t("multiplayer.coop.transfer.title")).apply { className = "osada-coop-hud-name" },
        )
        mine.forEach { transfer ->
            val unitName = map?.getUnitById(transfer.unitId)?.unitData(true)?.name ?: "#${transfer.unitId}"
            val from = state.commanderById(transfer.fromId)?.name.orEmpty()
            val to = state.commanderById(transfer.toId)?.name.orEmpty()
            val key =
                when {
                    transfer.accepted -> "multiplayer.coop.transfer.agreed"
                    transfer.awaitingId == self -> "multiplayer.coop.transfer.awaiting_you"
                    else -> "multiplayer.coop.transfer.awaiting_other"
                }
            val row = div("osada-coop-hud-row")
            row.appendChild(
                text(I18n.t(key, mapOf("unit" to unitName, "from" to from, "to" to to)))
                    .apply { className = "osada-coop-hud-sub" },
            )
            if (!transfer.accepted && transfer.awaitingId == self) {
                val accept = button(I18n.t("multiplayer.coop.transfer.accept"))
                accept.onclick = { model.onTransferAnswer(transfer.unitId, true) }
                row.appendChild(accept)
            }
            val decline =
                button(
                    I18n.t(
                        if (transfer.awaitingId ==
                            self
                        ) {
                            "multiplayer.coop.transfer.decline"
                        } else {
                            "multiplayer.coop.transfer.withdraw"
                        },
                    ),
                )
            decline.onclick = { model.onTransferAnswer(transfer.unitId, false) }
            row.appendChild(decline)
            box.appendChild(row)
        }
        return box
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

    private fun ensurePanel(): HTMLElement {
        (document.getElementById(PANEL_ID) as? HTMLElement)?.let { return it }
        val panel = div("osada-coop-hud")
        panel.id = PANEL_ID
        panel.setAttribute("role", "region")
        panel.setAttribute("aria-label", I18n.t("multiplayer.coop.hud.title"))
        document.body?.appendChild(panel)
        return panel
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

    @Suppress("unused")
    private fun runtimeActive(): Boolean = CoopRuntime.active
}
