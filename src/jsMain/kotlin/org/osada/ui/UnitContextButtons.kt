package org.osada.ui

import org.osada.i18n.I18n
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
    ): Int = render(unit, map, currentPlayer, false, 0)

    private fun render(
        unit: GameUnit,
        map: GameMap,
        currentPlayer: Player,
        construction: Boolean,
        page: Int,
    ): Int {
        UnitActionTooltip.hide()
        clearTag("unit-context")
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
        val actions = applicable.associateBy { it.action.id }
        val building = actions.keys.filter { it in UnitActionPages.construction }
        val inBuildMenu = construction && building.isNotEmpty()
        val pages = UnitActionPages.pages(UnitActionPages.main(actions.keys.toList()))
        val pageIndex = page.coerceIn(pages.indices)
        val visible = if (inBuildMenu) building + UnitActionPages.BACK else pages[pageIndex]
        // Keyboard commands reveal their page before using the same chip handler as a click.
        // The callback belongs to this DOM row, so clearing the selection discards it too.
        row.asDynamic().revealAction = { actionId: String ->
            if (actionId in actions) {
                val targetPage = pages.indexOfFirst { actionId in it }.coerceAtLeast(0)
                render(unit, map, currentPlayer, actionId in building, targetPage)
            }
        }
        visible.forEach { id ->
            val availability = actions[id]
            if (availability != null) {
                addButton(row, availability, unit, map, context.asleep)
            } else {
                addNavigation(row, id) {
                    val nextPage =
                        when (id) {
                            UnitActionPages.MORE -> pageIndex + 1
                            UnitActionPages.BACK -> if (inBuildMenu) pageIndex else pageIndex - 1
                            else -> pageIndex
                        }
                    render(unit, map, currentPlayer, id == UnitActionPages.BUILD, nextPage)
                    val focusAction = if (id == UnitActionPages.BUILD) UnitActionPages.BACK else UnitActionPages.BUILD
                    val host = byId("unit-context")
                    val focus =
                        host?.querySelector("[data-action='$focusAction']") ?: host?.querySelector("[tabindex='0']")
                    (focus as? HTMLElement)?.focus()
                }
            }
        }
        return visible.size
    }

    private fun addNavigation(
        row: HTMLElement,
        id: String,
        navigate: () -> Unit,
    ) {
        val button = addTag(row, "div")
        button.className = "osada-action osada-action--navigation"
        button.setAttribute("data-action", id)
        button.setAttribute("role", "button")
        button.setAttribute("tabindex", "0")
        val text = I18n.t("unit_info.action.$id.label")
        button.setAttribute("aria-label", text)
        val glyph = addTag(button, "span")
        glyph.className = "osada-action__glyph"
        glyph.innerHTML =
            when (id) {
                UnitActionPages.BUILD -> ActionIcons.REPAIR
                UnitActionPages.BACK -> ActionIcons.UNDO
                else -> "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\"><path d=\"M8 3l9 9-9 9-2-2 7-7-7-7z\"/></svg>"
            }
        val label = addTag(button, "span")
        label.className = "osada-action__label"
        label.textContent = text
        button.onclick = { navigate() }
        button.onkeydown = { event ->
            if (event.key == "Enter" || event.key == " ") {
                event.preventDefault()
                navigate()
            }
        }
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
        label.textContent = UnitActionPages.shortLabel(action.id, view.label)
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
