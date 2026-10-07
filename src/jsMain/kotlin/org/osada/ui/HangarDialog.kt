package org.osada.ui

import org.osada.i18n.I18n
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.isInitialDeploymentWindow
import org.osada.model.launchFromHangar
import org.osada.model.openHangarLaunchTargeting
import org.osada.multiplayer.client.OsadaMultiplayer
import org.osada.rules.CarrierHangars
import org.osada.rules.HangarStowage
import org.osada.rules.UnitPredicates
import org.w3c.dom.HTMLElement

/**
 * The hangar window of a carrier or an airbase, and the garrison window of a fortification
 * (2026-10-07): what is inside, and the order to take off or come out. The two differ only in their
 * words ([hangarKey]); a land formation always comes out onto a hex beside the fortification.
 *
 * OG opens a list, the player picks an aircraft and presses Launch, then says where it flies. Here
 * the hangar is drawn as its PLACES -- one card per place, filled or free -- so capacity is the
 * shape of the window rather than a number to read. A card shows the formation, its strength, fuel
 * and ammunition, and whether it can take off now; selecting one arms the footer.
 *
 * **Take-off.** An aircraft rises straight from the container's own hex and arrives SELECTED, with
 * its move range drawn, so the next click is its destination -- the "where to fly" step. When that
 * air slot is taken (or for a land formation, which can never share the container's hex) the map
 * offers the hexes it may step out to instead ([org.osada.model.openHangarLaunchTargeting]).
 *
 * During the opening deployment a formation stowed there can also be sent back to the reserve.
 */
internal object HangarDialog {
    private const val BOX_ID = "uiHangarBox"
    private const val SHADE_ID = "uiHangarShade"

    private class Session(
        val ui: UI,
        val map: GameMap,
        val container: GameUnit,
        var selected: GameUnit? = null,
    )

    private var session: Session? = null

    fun isOpen(): Boolean = byId(BOX_ID) != null

    fun open(
        ui: UI,
        map: GameMap,
        container: GameUnit,
    ) {
        close()
        val body = byId("mainbody") ?: return
        val shade = addTag(body, "div")
        shade.id = SHADE_ID
        shade.className = "osada-hgr-shade"
        shade.onclick = { close() }
        val box = addTag(body, "div")
        box.id = BOX_ID
        box.className = "osada-hgr"
        box.setAttribute("role", "dialog")
        box.setAttribute("aria-modal", "true")
        val ready = container.hangar.firstOrNull { CarrierHangars.canLaunch(map, it, container) }
        session = Session(ui, map, container, ready)
        rerender()
    }

    fun close() {
        session = null
        delTag(byId(BOX_ID))
        delTag(byId(SHADE_ID))
    }

    private fun rerender() {
        val box = byId(BOX_ID) ?: return
        val current = session ?: return
        clearTag(box)
        buildHeader(box, current)
        val lead = addTag(box, "div")
        lead.className = "osada-hgr__lead"
        lead.textContent = I18n.t(hangarKey(current.container, "lead"))
        HangarPlaceCards.build(box, current.map, current.container, current.selected) { picked ->
            current.selected = picked
            rerender()
        }
        buildFooter(box, current)
    }

    private fun buildHeader(
        box: HTMLElement,
        current: Session,
    ) {
        val header = addTag(box, "div")
        header.className = "osada-hgr__header"
        val title = addTag(header, "div")
        title.className = "osada-hgr__title"
        val name = current.container.customName ?: current.container.unitData(true).name
        title.textContent = I18n.t(hangarKey(current.container, "title"), mapOf("name" to name))
        val count = addTag(header, "div")
        count.className = "osada-hgr__count"
        count.textContent =
            I18n.t(
                "hangar.count",
                mapOf("used" to current.container.hangar.size, "max" to CarrierHangars.capacity(current.container)),
            )
        val closeButton = addTag(header, "span")
        closeButton.className = "osada-hgr__close"
        closeButton.innerHTML = "&#10005;"
        closeButton.setAttribute("role", "button")
        closeButton.setAttribute("tabindex", "0")
        closeButton.setAttribute("aria-label", I18n.t("hangar.close"))
        closeButton.onclick = { close() }
    }

    private fun buildFooter(
        box: HTMLElement,
        current: Session,
    ) {
        val footer = addTag(box, "div")
        footer.className = "osada-hgr__footer"
        val note = addTag(footer, "div")
        note.className = "osada-hgr__note"
        val passenger = current.selected
        val blocked = launchBlock(current, passenger)
        note.textContent = I18n.t(hangarKey(current.container, blocked ?: "launch.hint"))
        if (passenger != null && canReturnToReserve(current, passenger)) {
            addButton(footer, I18n.t("hangar.reserve"), primary = false) {
                HangarStowage.returnToReserve(passenger)
                current.selected = null
                current.ui.updateEquipmentWindow(passenger.unitData(true).uclass)
                rerender()
            }
        }
        addButton(footer, I18n.t("hangar.close"), primary = false) { close() }
        val launch =
            addButton(footer, I18n.t(hangarKey(current.container, "launch")), primary = true) { launch(current) }
        if (blocked != null) {
            launch.classList.add("osada-hgr__button--disabled")
            launch.setAttribute("aria-disabled", "true")
            launch.onclick = null
        }
    }

    private fun addButton(
        footer: HTMLElement,
        text: String,
        primary: Boolean,
        action: () -> Unit,
    ): HTMLElement {
        val button = addTag(footer, "button")
        button.className = "osada-hgr__button" + if (primary) " osada-hgr__button--primary" else ""
        button.textContent = text
        button.onclick = { action() }
        return button
    }

    /** Why the selected formation cannot leave now, as a [hangarKey] key, or null when it can. */
    private fun launchBlock(
        current: Session,
        passenger: GameUnit?,
    ): String? {
        val ownTurn =
            current.map.currentPlayer?.id == current.container.owner && OsadaMultiplayer.acceptsLocalCommands()
        return when {
            passenger == null -> if (current.container.hangar.isEmpty()) "empty" else "pick"
            !ownTurn -> "blocked.turn"
            current.map.currentPlayer?.let { current.map.isInitialDeploymentWindow(it) } == true ->
                "blocked.deployment"
            CarrierHangars.landedThisTurn(current.map, passenger) -> "blocked.landed"
            CarrierHangars.launchCells(current.map, passenger, current.container).isEmpty() ->
                "blocked.no_room"
            else -> null
        }
    }

    /** Only a formation the tray can hold again: one on the player's roster, during the opening deployment. */
    private fun canReturnToReserve(
        current: Session,
        passenger: GameUnit,
    ): Boolean {
        val player = current.map.currentPlayer ?: return false
        return player.id == current.container.owner &&
            current.map.isInitialDeploymentWindow(player) &&
            player.getCoreUnitList().any { it === passenger }
    }

    /**
     * An aircraft whose container's air slot is free rises straight from it, selected and ready to
     * fly; anything else gets its take-off hexes offered on the map.
     */
    private fun launch(current: Session) {
        val passenger = current.selected ?: return
        val map = current.map
        val container = current.container
        val home = container.getHex()
        val pos = container.getPos()
        val straightUp =
            UnitPredicates.isAir(passenger) &&
                home != null &&
                home in CarrierHangars.launchCells(map, passenger, container)
        close()
        when {
            pos == null -> Unit
            straightUp && map.launchFromHangar(container, passenger, home) -> {
                current.ui.render.cacheImages {
                    current.ui.showUnitInfo(passenger)
                    current.ui.buildUnitContext(passenger)
                    current.ui.render.render(pos.row, pos.col, getUnitRenderRadius(passenger))
                }
                current.ui.showAlert(pos.row, pos.col, I18n.t("hangar.launched.fly"), true)
            }
            !straightUp -> {
                map.openHangarLaunchTargeting(container, passenger)
                current.ui.buildUnitContext(null)
                current.ui.render.render(pos.row, pos.col, 2)
                current.ui.showAlert(pos.row, pos.col, I18n.t("hangar.launch.pick_hex"), true)
            }
        }
    }
}
