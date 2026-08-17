package org.osada.ui

import org.osada.GameHolder
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.multiplayer.client.OsadaMultiplayer
import org.osada.rules.UnitActionAvailability
import org.osada.rules.UnitActionContext
import org.osada.rules.UnitActionId
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.MouseEvent

/**
 * Opens the anchored explanation panel for one action chip -- mouse hover and keyboard focus on
 * desktop, tap/long-press on coarse input (`docs/design/action-affordances-and-objectives.md` §4).
 *
 * The availability is resolved when the panel opens, not when the chip was built, so a number the
 * player reads is the number the command would produce at that moment. A hidden mirror of the same
 * text is exposed through `aria-describedby` so the explanation is not sight-only.
 */
internal object UnitActionTooltip {
    const val TIP_ID = "osadaActionTip"

    /** Matches `MapPointerController`'s own long-press threshold. */
    private const val LONG_PRESS_MS = 400

    private var pressTimer: Int = 0

    fun attach(
        button: HTMLElement,
        action: UnitActionId,
        unit: GameUnit,
        map: GameMap,
        asleep: Boolean,
    ) {
        val describedById = "ucActionDesc-${action.id}"
        val description = addTag(button, "span")
        description.id = describedById
        description.className = "osada-sr-only"
        button.setAttribute("aria-describedby", describedById)

        fun open() {
            val view = resolve(action, unit, map, asleep) ?: return
            description.textContent = view.semanticText()
            AnchoredTip.show(
                TIP_ID,
                button,
                AnchoredTip.html(
                    view.label,
                    view.status,
                    view.enabled,
                    view.description,
                    view.lines.map { it.kind to it.text },
                    view.keyCap,
                ),
            )
        }
        // Populate the accessible mirror immediately; the panel itself waits for hover/focus.
        resolve(action, unit, map, asleep)?.let { description.textContent = it.semanticText() }

        button.onmouseenter = { _: MouseEvent -> open() }
        button.onmouseleave = { _: MouseEvent -> hide() }
        button.asDynamic().onfocus = { _: Event -> open() }
        button.asDynamic().onblur = { _: Event -> hide() }
        attachCoarsePointer(button, ::open)
    }

    fun hide() {
        cancelPressTimer()
        AnchoredTip.hide(TIP_ID)
    }

    /** On a touch screen there is no hover: a long press opens the panel, and any pointer release
     *  or cancel closes it. A short tap falls through to the chip's own click handler. */
    private fun attachCoarsePointer(
        button: HTMLElement,
        open: () -> Unit,
    ) {
        button.asDynamic().onpointerdown = { event: dynamic ->
            if (event.pointerType != "mouse") {
                cancelPressTimer()
                pressTimer = kotlinx.browser.window.setTimeout({ open() }, LONG_PRESS_MS)
            }
            Unit
        }
        button.asDynamic().onpointerup = { _: dynamic -> hide() }
        button.asDynamic().onpointercancel = { _: dynamic -> hide() }
    }

    private fun cancelPressTimer() {
        if (pressTimer != 0) {
            kotlinx.browser.window.clearTimeout(pressTimer)
            pressTimer = 0
        }
    }

    private fun resolve(
        action: UnitActionId,
        unit: GameUnit,
        map: GameMap,
        asleep: Boolean,
    ): UnitActionPresenter.View? {
        val currentPlayer = map.currentPlayer ?: return null
        val ui = GameHolder.instance?.ui
        val availability =
            UnitActionAvailability.forAction(
                action,
                UnitActionContext(
                    map = map,
                    unit = unit,
                    currentPlayer = currentPlayer,
                    localTurn = OsadaMultiplayer.acceptsLocalCommands(),
                    hasAnyAction = ui?.hasAnyAction(unit) ?: true,
                    asleep = asleep,
                ),
            )
        return UnitActionPresenter.view(availability, unit, map, asleep)
    }
}
