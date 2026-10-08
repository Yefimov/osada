package org.osada.rules

import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.setMoveRange

/**
 * OG's **cautious move** (manual §6.13): *"the unit will move only by spotted hexes"*
 * (`docs/design/og-parity-followups.md` §1, built 2026-10-08).
 *
 * While it is on for the selected formation, [MoveRangeCalculation] treats every hex its side has
 * not spotted as impassable, so both the drawn range and the walked route stay on seen ground. The
 * move itself follows that range (`MoveExecutor` walks `GameMap.currentMoveRange`), so nothing else
 * has to know. It narrows the route; it does NOT promise safety -- a concealed enemy can still sit
 * on a spotted hex, and walking into it is still a surprise.
 *
 * **Per selection, never sticky** (the proposal's recommendation): the flag names ONE unit
 * ([GameMap.cautiousMoveUnitId]), so selecting another unit simply finds it off. UI state only --
 * never saved, never sent except as the `cautious` field of that unit's own `MoveUnit` order.
 * Ground formations only: an aircraft cannot walk into a hidden ground unit.
 */
object CautiousMove {
    fun isOn(
        map: GameMap,
        unit: GameUnit,
    ): Boolean = map.cautiousMoveUnitId == unit.id

    /** Whether the mode can matter for [unit]: a ground formation that can still move. */
    fun appliesTo(unit: GameUnit): Boolean =
        !UnitPredicates.isAir(unit) && !unit.hasMoved && !RailTransport.isEntrained(unit) && unit.getMovesLeft() > 0

    /** Switches the mode for [unit] and redraws its move range. */
    fun toggle(
        map: GameMap,
        unit: GameUnit,
    ) {
        map.cautiousMoveUnitId = if (isOn(map, unit)) null else unit.id
        map.setMoveRange(unit)
    }

    /** The action-strip chip: applicable while the formation can still move. */
    fun availability(context: UnitActionContext): ActionAvailability {
        if (!appliesTo(context.unit)) return ActionAvailability.notApplicable(UnitActionId.CAUTIOUS_MOVE)
        val reasons = mutableListOf<ActionBlock>()
        UnitActionAvailability.addTurnBlock(context, reasons)
        val effect =
            if (isOn(context.map, context.unit)) ActionEffectKind.NORMAL_ROUTE else ActionEffectKind.CAUTIOUS_ROUTE
        return ActionAvailability(
            UnitActionId.CAUTIOUS_MOVE,
            true,
            reasons.isEmpty(),
            reasons,
            listOf(ActionEffect(effect)),
        )
    }
}
