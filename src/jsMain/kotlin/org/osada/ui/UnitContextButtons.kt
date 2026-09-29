package org.osada.ui

import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.isInitialDeploymentWindow
import org.osada.multiplayer.client.OsadaMultiplayer
import org.osada.rules.ActionAvailability
import org.osada.rules.UnitActionAvailability
import org.osada.rules.UnitActionContext
import org.osada.rules.UnitActionId
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent

/**
 * [UnitContextMenu]'s action-chip strip (Mount/Embark/Resupply/Reinforce/Overstrength/Undo/Sleep).
 * Split out purely to keep [UnitContextMenu] within the project's function-count/class-size limits
 * -- not expected to be called from elsewhere.
 *
 * The strip renders exactly what [UnitActionAvailability] declares applicable, in its fixed order:
 * an action that can never apply to this formation is absent, and one that is merely blocked right
 * now stays visible and disabled so its reason can be read
 * (`docs/design/action-affordances-and-objectives.md` §§1, 3) -- except during the opening
 * deployment, where a blocked chip is omitted so the strip fits (owner request 2026-09-29).
 */
internal class UnitContextButtons(
    private val ui: UI,
    private val onAction: (String, GameUnit) -> Unit,
) {
    fun build(
        unit: GameUnit,
        map: GameMap,
        currentPlayer: Player,
    ): Int {
        val row = addTag("unit-context", "div")
        row.className = "osada-actions"
        val context = context(unit, map, currentPlayer)
        // The opening deployment adds Withdraw to a strip that only has room for six chips, and
        // most of the rest are blocked there anyway (nothing has moved, nothing is spent): showing
        // them disabled pushed usable ones off the end. Inside that window a blocked chip is left
        // out; everywhere else it stays visible so its reason can be read (see the class doc).
        val deploying = map.isInitialDeploymentWindow(currentPlayer)
        val applicable =
            UnitActionAvailability.all(context).filter { it.applicable && (!deploying || it.enabled) }
        applicable.forEach { availability -> addButton(row, availability, unit, map, context.asleep) }
        return applicable.size
    }

    /** The UI-owned half of the availability question: whose turn it is and what the ready-unit
     *  navigator thinks of this unit. */
    private fun context(
        unit: GameUnit,
        map: GameMap,
        currentPlayer: Player,
    ): UnitActionContext =
        UnitActionContext(
            map = map,
            unit = unit,
            currentPlayer = currentPlayer,
            localTurn = OsadaMultiplayer.acceptsLocalCommands(),
            hasAnyAction = ui.hasAnyAction(unit),
            asleep = ui.isUnitAsleep(unit),
        )

    // Labeled action chip (glyph + text) instead of a bare floating glyph button: the player must
    // never guess what a context action does, nor why it is greyed out.
    private fun addButton(
        row: HTMLElement,
        availability: ActionAvailability,
        unit: GameUnit,
        map: GameMap,
        asleep: Boolean,
    ) {
        val view = UnitActionPresenter.view(availability, unit, map, asleep)
        val action = availability.action
        val button = addTag(row, "div")
        button.className = "osada-action" + extraClass(action, availability.enabled, asleep)
        button.setAttribute("data-action", action.id)
        button.setAttribute("data-action-variant", UnitActionPresenter.variantKey(action, unit, asleep))
        // The rich anchored panel replaces the native one-line title entirely (§4).
        button.title = ""
        button.setAttribute("role", "button")
        button.setAttribute("aria-label", view.label)
        button.setAttribute("aria-disabled", (!availability.enabled).toString())
        button.setAttribute("tabindex", if (availability.enabled) "0" else "-1")
        val glyph = addTag(button, "span")
        glyph.className = "osada-action__glyph"
        glyph.innerHTML = view.glyph
        val label = addTag(button, "span")
        label.className = "osada-action__label"
        label.textContent = view.label
        UnitActionTooltip.attach(button, action, unit, map, asleep)
        if (availability.enabled) attachActivation(button, action, unit)
    }

    private fun attachActivation(
        button: HTMLElement,
        action: UnitActionId,
        unit: GameUnit,
    ) {
        button.onclick = { _: MouseEvent -> onAction(action.id, unit) }
        button.onkeydown = { event ->
            val key = event.asDynamic().key as? String
            if (key == "Enter" || key == " ") {
                event.preventDefault()
                onAction(action.id, unit)
            }
        }
    }

    private fun extraClass(
        action: UnitActionId,
        enabled: Boolean,
        asleep: Boolean,
    ): String {
        val parts = mutableListOf<String>()
        // Undo is the single rescue action -- styled distinctly (brass border, spec).
        if (action == UnitActionId.UNDO) parts += "osada-action--undo"
        if (action == UnitActionId.SLEEP && asleep) parts += "osada-action--active"
        if (!enabled) parts += "osada-action--disabled"
        return if (parts.isEmpty()) "" else " " + parts.joinToString(" ")
    }
}
