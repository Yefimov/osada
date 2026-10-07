package org.osada.rules

import org.osada.model.GameUnit
import org.osada.model.Hex

/**
 * The parts of an engineering chip shared by an orderable job and a greyed BUILD chip
 * ([UnitActionAvailability]): what the work would cost and take, and which hex a build job needs.
 * Split out so the availability object stays inside the project's size limit.
 */
internal object ConstructionChips {
    /** Which hex each build job needs, as the reason shown when the sapper's own hex is not one. */
    val SITE_REASONS: Map<EngineeringWork, ActionBlockReason> =
        mapOf(
            EngineeringWork.BRIDGE to ActionBlockReason.BRIDGE_NEEDS_CROSSING,
            EngineeringWork.FORTIFICATION to ActionBlockReason.FORTIFICATION_NEEDS_GROUND,
            EngineeringWork.AIRFIELD to ActionBlockReason.AIRFIELD_NEEDS_CLEAR,
            EngineeringWork.PORT to ActionBlockReason.PORT_NEEDS_COAST,
            EngineeringWork.STATION to ActionBlockReason.STATION_NEEDS_RAIL,
        )

    /**
     * A BUILD chip for a sapper standing where that job cannot be done: applicable, never enabled,
     * with the reason the hex refuses it, after the turn [reasons] the caller has already found.
     * Null -- leave the chip out -- for anything that is not a build job, for a unit that cannot
     * build at all, with Build and Repair off, or where the efile's `build_mask` forbids the job
     * everywhere: no hex would ever take it, so there is nothing for the player to learn by seeing it.
     */
    fun unsuitable(
        action: UnitActionId,
        job: EngineeringWork?,
        unit: GameUnit,
        reasons: MutableList<ActionBlock>,
    ): ActionAvailability? {
        val builder = Engineering.enabled() && Engineering.isSapper(unit)
        val work = job?.takeIf { builder && it in SITE_REASONS && it.permittedByEfileMask() }
        val hex = unit.getHex()
        if (work == null || hex == null) return null
        if (!Engineering.authorisedByScenario(work)) {
            reasons += ActionBlock(ActionBlockReason.SCENARIO_FORBIDS_ENGINEERING)
        }
        reasons += siteBlock(hex, work)
        return ActionAvailability(action, true, false, reasons, effects(unit, work, Engineering.startsAfterMove()))
    }

    /** Why [hex] will not take [work]: work already under way here, or the wrong kind of hex. */
    private fun siteBlock(
        hex: Hex,
        work: EngineeringWork,
    ): ActionBlock =
        if (Engineering.underConstruction(hex)) {
            ActionBlock(ActionBlockReason.ENGINEERING_IN_PROGRESS, hex.constructionTurns)
        } else {
            ActionBlock(SITE_REASONS.getValue(work))
        }

    /** Cost, duration and start rule of [work] for [unit], as the chip lists them. */
    fun effects(
        unit: GameUnit,
        work: EngineeringWork,
        afterMove: Boolean,
    ): List<ActionEffect> {
        val effects = mutableListOf<ActionEffect>()
        if (work.costFor() > 0) effects += ActionEffect(ActionEffectKind.PRESTIGE_COST_ONCE, work.costFor())
        effects +=
            if (work.turnsForBuilder(unit) == 0) {
                ActionEffect(ActionEffectKind.DEMOLISH_NOW)
            } else {
                ActionEffect(ActionEffectKind.BUILD_TURNS, work.turnsForBuilder(unit))
            }
        effects +=
            ActionEffect(
                if (afterMove) {
                    ActionEffectKind.ENGINEERING_START_AFTER_MOVE
                } else {
                    ActionEffectKind.ENGINEERING_START_UNSPENT
                },
            )
        effects += ActionEffect(ActionEffectKind.ENDS_UNIT_ACTION)
        return effects
    }
}
