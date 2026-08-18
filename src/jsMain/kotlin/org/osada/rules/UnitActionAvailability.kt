package org.osada.rules

import org.osada.EmbarkType
import org.osada.PlayerType
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.UndoInvalidation
import org.osada.model.canUndoMove
import org.osada.model.undoInvalidationFor

/**
 * Everything the unit-context action strip needs to decide what to show, what to disable and what
 * to say -- resolved once, from the same predicates the commands themselves use
 * (`docs/design/action-affordances-and-objectives.md` §§2-3).
 *
 * The UI-owned facts an action depends on (is it the local player's turn, does the navigator still
 * consider the unit actionable, is it asleep) arrive in [UnitActionContext] rather than being
 * re-derived here.
 */
data class UnitActionContext(
    val map: GameMap,
    val unit: GameUnit,
    val currentPlayer: Player,
    /** False while another peer or the AI holds the turn; every command is then read-only. */
    val localTurn: Boolean = true,
    /** Whether the ready-unit navigator still counts this unit as able to do something. */
    val hasAnyAction: Boolean = true,
    val asleep: Boolean = false,
)

/** Resolves [ActionAvailability] for each of the seven unit-context commands. */
@Suppress("TooManyFunctions")
object UnitActionAvailability {
    private const val FULL_STRENGTH = 10
    private const val OVERSTRENGTH_MIN_EXPERIENCE = 100

    /** Every action, in the stable strip order, including the ones that are not applicable --
     *  callers filter on [ActionAvailability.applicable]. */
    fun all(context: UnitActionContext): List<ActionAvailability> =
        listOf(
            mount(context),
            embark(context),
            resupply(context),
            reinforce(context),
            overstrength(context),
            undo(context),
            sleep(context),
        )

    fun forAction(
        action: UnitActionId,
        context: UnitActionContext,
    ): ActionAvailability =
        when (action) {
            UnitActionId.MOUNT -> mount(context)
            UnitActionId.EMBARK -> embark(context)
            UnitActionId.RESUPPLY -> resupply(context)
            UnitActionId.REINFORCE -> reinforce(context)
            UnitActionId.OVERSTRENGTH -> overstrength(context)
            UnitActionId.UNDO -> undo(context)
            UnitActionId.SLEEP -> sleep(context)
        }

    // ---- Mount / dismount --------------------------------------------------------------------

    /** Applicable to any ground formation that could ever ride its own transport, so an infantry
     *  formation bought without one still shows a disabled Mount with the real reason rather than
     *  silently missing the action. A fortification can never mount and never shows it. */
    private fun mount(context: UnitActionContext): ActionAvailability {
        val unit = context.unit
        val transportable =
            UnitPredicates.isGround(unit) &&
                (
                    unit.isMounted ||
                        unit.transport != null ||
                        UnitPredicates.isTransportable(unit.eqid)
                )
        if (!transportable) return ActionAvailability.notApplicable(UnitActionId.MOUNT)
        val reasons = mutableListOf<ActionBlock>()
        addTurnBlock(context, reasons)
        if (!unit.isMounted && unit.transport == null) reasons += ActionBlock(ActionBlockReason.NO_ORGANIC_TRANSPORT)
        if (unit.hasMoved) reasons += ActionBlock(ActionBlockReason.ALREADY_MOVED)
        val effects =
            if (unit.isMounted) {
                listOf(
                    ActionEffect(ActionEffectKind.DISMOUNT_OWN_STATS),
                    ActionEffect(ActionEffectKind.LIMBER_TOGGLE_FREE),
                )
            } else {
                listOf(
                    ActionEffect(ActionEffectKind.MOUNT_TRANSPORT_STATS),
                    ActionEffect(ActionEffectKind.LIMBER_TOGGLE_FREE),
                )
            }
        return ActionAvailability(UnitActionId.MOUNT, true, reasons.isEmpty(), reasons, effects)
    }

    // ---- Embark / disembark ------------------------------------------------------------------

    /** Applicable to a formation whose equipment declares an air/naval embark class, or that is
     *  already being carried. */
    private fun embark(context: UnitActionContext): ActionAvailability {
        val unit = context.unit
        val carried = unit.carrier != 0
        if (!carried && unit.unitData().embark <= EmbarkType.NONE.value) {
            return ActionAvailability.notApplicable(UnitActionId.EMBARK)
        }
        return if (carried) disembark(context) else embarkOnto(context)
    }

    private fun disembark(context: UnitActionContext): ActionAvailability {
        val unit = context.unit
        val reasons = mutableListOf<ActionBlock>()
        addTurnBlock(context, reasons)
        val hexes = EmbarkRules.getDisembarkPositions(context.map, unit).size
        if (unit.hasMoved) reasons += ActionBlock(ActionBlockReason.ALREADY_MOVED)
        if (hexes == 0 && unit.carrier > 0) reasons += ActionBlock(ActionBlockReason.NO_DISEMBARK_HEX)
        // `carrier < 0` is the "boarded this turn, not yet committed" state: toggling it back is
        // always allowed and is what `canEmbark` reports, so it is not blocked by a missing hex.
        val enabled =
            if (unit.carrier < 0) {
                reasons.none { it.reason == ActionBlockReason.NOT_LOCAL_TURN }
            } else {
                reasons.isEmpty()
            }
        return ActionAvailability(
            UnitActionId.EMBARK,
            true,
            enabled,
            reasons,
            listOf(ActionEffect(ActionEffectKind.DISEMBARK, hexes)),
        )
    }

    private fun embarkOnto(context: UnitActionContext): ActionAvailability {
        val unit = context.unit
        val hex = unit.getHex()
        val data = unit.unitData()
        val onAirfield = hex?.terrain == TerrainType.AIRFIELD.value && data.embark > EmbarkType.NAVAL.value
        val onPort = hex?.terrain == TerrainType.PORT.value
        val type = EmbarkRules.getEmbarkType(context.map, unit)
        val reasons = mutableListOf<ActionBlock>()
        addTurnBlock(context, reasons)
        when {
            !onAirfield && !onPort -> reasons += ActionBlock(ActionBlockReason.NOT_AT_TRANSPORT_FACILITY)
            type == UnitClass.NONE.value -> reasons += ActionBlock(ActionBlockReason.NO_TRANSPORT_AVAILABLE)
        }
        if (unit.hasMoved) reasons += ActionBlock(ActionBlockReason.ALREADY_MOVED)
        val effect =
            when {
                type == UnitClass.AIR_TRANSPORT.value -> ActionEffectKind.EMBARK_AIR
                type == UnitClass.NAVAL_TRANSPORT.value -> ActionEffectKind.EMBARK_NAVAL
                data.embark > EmbarkType.NAVAL.value -> ActionEffectKind.EMBARK_AIR
                else -> ActionEffectKind.EMBARK_NAVAL
            }
        return ActionAvailability(
            UnitActionId.EMBARK,
            true,
            reasons.isEmpty(),
            reasons,
            listOf(ActionEffect(effect)),
        )
    }

    // ---- Resupply ----------------------------------------------------------------------------

    /** Applicable to any formation that carries ammo or fuel of its own, or whose organic
     *  transport does. A formation with neither never shows Supply. */
    private fun resupply(context: UnitActionContext): ActionAvailability {
        val unit = context.unit
        val transportData = unit.transport?.unitData()
        val usesSupply =
            SupplyRules.maxAmmo(unit) > 0 ||
                SupplyRules.maxFuel(unit) > 0 ||
                (transportData?.ammo ?: 0) > 0 ||
                (transportData?.fuel ?: 0) > 0
        if (!usesSupply) return ActionAvailability.notApplicable(UnitActionId.RESUPPLY)

        val reasons = mutableListOf<ActionBlock>()
        addTurnBlock(context, reasons)
        addSpentActionBlocks(unit, reasons)
        addSupplyTerrainBlocks(context, reasons)
        if (!SupplyRules.needsSupply(unit)) reasons += ActionBlock(ActionBlockReason.FULLY_SUPPLIED)

        val enabled = SupplyRules.canResupply(context.map, unit) && reasons.isEmpty()
        val value = SupplyRules.getResupplyValue(context.map, unit)
        val supplyContext = SupplyContextRules.getSupplyContext(context.map, unit)
        val effects = mutableListOf<ActionEffect>()
        if (value.ammo > 0) effects += ActionEffect(ActionEffectKind.SUPPLY_AMMO, value.ammo)
        if (value.fuel > 0) effects += ActionEffect(ActionEffectKind.SUPPLY_FUEL, value.fuel)
        val transportAmmo = value.transportAmmo
        val transportFuel = value.transportFuel
        if (transportAmmo > 0) effects += ActionEffect(ActionEffectKind.SUPPLY_TRANSPORT_AMMO, transportAmmo)
        if (transportFuel > 0) effects += ActionEffect(ActionEffectKind.SUPPLY_TRANSPORT_FUEL, transportFuel)
        effects += ActionEffect(ActionEffectKind.SUPPLY_EFFICIENCY, supplyContext.efficiencyPercent)
        effects += ActionEffect(ActionEffectKind.ENDS_UNIT_ACTION)
        return ActionAvailability(UnitActionId.RESUPPLY, true, enabled, reasons, effects)
    }

    // ---- Reinforce ---------------------------------------------------------------------------

    /** Applicable to any formation below normal strength -- including one that cannot afford it,
     *  which is exactly the case the player needs the cost spelled out for. */
    private fun reinforce(context: UnitActionContext): ActionAvailability {
        val unit = context.unit
        if (unit.strength >= FULL_STRENGTH) return ActionAvailability.notApplicable(UnitActionId.REINFORCE)

        val reasons = mutableListOf<ActionBlock>()
        addTurnBlock(context, reasons)
        addSpentActionBlocks(unit, reasons)
        addSupplyTerrainBlocks(context, reasons)
        val gain = SupplyRules.getReinforceValue(context.map, unit, false)
        if (SupplyRules.canReinforce(context.map, unit, false) && gain <= 0) {
            reasons += ActionBlock(ActionBlockReason.NO_STRENGTH_RESTORED_HERE)
        }
        val perPoint = CostCalculator.reinforceCostPerStrength(unit, false)
        addPrestigeBlock(context, perPoint, reasons)

        val enabled = SupplyRules.canReinforce(context.map, unit, false) && gain > 0 && reasons.isEmpty()
        val supplyContext = SupplyContextRules.getSupplyContext(context.map, unit)
        val affordable = affordablePoints(context, perPoint, gain)
        // The exact resulting experience, computed from the points the player can actually afford --
        // the roadmap requires the preview to state what will happen, so it must use the same input
        // and the same function the mutation will (`ReplacementExperience`).
        val dilutedExperience = ReplacementExperience.afterReplacement(unit.experience, unit.strength, affordable)
        val effects =
            listOfNotNull(
                ActionEffect(ActionEffectKind.STRENGTH_GAIN, affordable),
                ActionEffect(ActionEffectKind.PRESTIGE_COST, affordable * perPoint),
                ActionEffect(ActionEffectKind.EXPERIENCE_DILUTION, dilutedExperience, unit.experience)
                    .takeIf { dilutedExperience < unit.experience },
                ActionEffect(ActionEffectKind.SUPPLY_EFFICIENCY, supplyContext.efficiencyPercent),
                ActionEffect(ActionEffectKind.ENDS_UNIT_ACTION),
            )
        return ActionAvailability(UnitActionId.REINFORCE, true, enabled, reasons, effects)
    }

    // ---- Overstrength ------------------------------------------------------------------------

    /** Applicable to a formation at full strength whose class can hold an overstrength point at
     *  all; below full strength ordinary reinforcement is the relevant action instead. */
    private fun overstrength(context: UnitActionContext): ActionAvailability {
        val unit = context.unit
        if (unit.strength < FULL_STRENGTH) return ActionAvailability.notApplicable(UnitActionId.OVERSTRENGTH)

        val cap = SupplyRules.overstrengthCap(unit)
        val reasons = mutableListOf<ActionBlock>()
        addTurnBlock(context, reasons)
        if (unit.hasOverstrength) reasons += ActionBlock(ActionBlockReason.OVERSTRENGTH_SPENT)
        addSupplyTerrainBlocks(context, reasons)
        if (unit.experience < OVERSTRENGTH_MIN_EXPERIENCE) {
            reasons += ActionBlock(ActionBlockReason.NEEDS_EXPERIENCE, OVERSTRENGTH_MIN_EXPERIENCE - unit.experience)
        } else if (unit.strength >= cap) {
            reasons += ActionBlock(ActionBlockReason.OVERSTRENGTH_CAP_REACHED)
        }
        val perPoint = CostCalculator.reinforceCostPerStrength(unit, true)
        addPrestigeBlock(context, perPoint, reasons)

        val gain = SupplyRules.getReinforceValue(context.map, unit, true)
        if (SupplyRules.canReinforce(context.map, unit, true) && gain <= 0) {
            reasons += ActionBlock(ActionBlockReason.NO_STRENGTH_RESTORED_HERE)
        }
        val enabled = SupplyRules.canReinforce(context.map, unit, true) && gain > 0 && reasons.isEmpty()
        val affordable = affordablePoints(context, perPoint, gain)
        val effects =
            listOf(
                ActionEffect(ActionEffectKind.OVERSTRENGTH_TARGET, unit.strength + affordable, cap),
                ActionEffect(ActionEffectKind.PRESTIGE_COST, affordable * perPoint),
                ActionEffect(ActionEffectKind.ENDS_UNIT_ACTION),
            )
        return ActionAvailability(UnitActionId.OVERSTRENGTH, true, enabled, reasons, effects)
    }

    // ---- Undo --------------------------------------------------------------------------------

    /** Applicable while a move record exists for this unit, and for as long afterwards as the
     *  reason its record was dropped is still remembered -- so Undo explains its disappearance
     *  instead of just vanishing. */
    private fun undo(context: UnitActionContext): ActionAvailability {
        val canUndo = context.map.canUndoMove(context.unit)
        val invalidation = context.map.undoInvalidationFor(context.unit)
        if (!canUndo && invalidation == null) return ActionAvailability.notApplicable(UnitActionId.UNDO)
        val reasons = mutableListOf<ActionBlock>()
        addTurnBlock(context, reasons)
        if (!canUndo) reasons += ActionBlock(undoReason(invalidation))
        return ActionAvailability(
            UnitActionId.UNDO,
            true,
            reasons.isEmpty(),
            reasons,
            listOf(ActionEffect(ActionEffectKind.UNDO_RESTORE)),
        )
    }

    private fun undoReason(invalidation: UndoInvalidation?): ActionBlockReason =
        when (invalidation) {
            UndoInvalidation.NEW_INTELLIGENCE -> ActionBlockReason.UNDO_NEW_INTELLIGENCE
            UndoInvalidation.SURPRISED -> ActionBlockReason.UNDO_SURPRISED
            UndoInvalidation.INTERCEPTED -> ActionBlockReason.UNDO_INTERCEPTED
            UndoInvalidation.STOPPED_BY_HIDDEN_ENEMY -> ActionBlockReason.UNDO_STOPPED_BY_HIDDEN_ENEMY
            UndoInvalidation.COMBAT -> ActionBlockReason.UNDO_COMBAT
            UndoInvalidation.IRREVERSIBLE_ACTION -> ActionBlockReason.UNDO_IRREVERSIBLE_ACTION
            null -> ActionBlockReason.UNDO_NOTHING_TO_UNDO
        }

    // ---- Sleep / wake ------------------------------------------------------------------------

    /** Applicable while the unit could still be cycled to, or is already asleep and needs waking. */
    private fun sleep(context: UnitActionContext): ActionAvailability {
        if (!context.hasAnyAction && !context.asleep) return ActionAvailability.notApplicable(UnitActionId.SLEEP)
        val reasons = mutableListOf<ActionBlock>()
        addTurnBlock(context, reasons)
        if (!context.hasAnyAction && !context.asleep) reasons += ActionBlock(ActionBlockReason.NO_ACTION_LEFT)
        val effect = if (context.asleep) ActionEffectKind.WAKE_RESTORE else ActionEffectKind.SLEEP_SKIP
        return ActionAvailability(
            UnitActionId.SLEEP,
            true,
            reasons.isEmpty(),
            reasons,
            listOf(ActionEffect(effect)),
        )
    }

    // ---- Shared reason builders ---------------------------------------------------------------

    private fun addTurnBlock(
        context: UnitActionContext,
        reasons: MutableList<ActionBlock>,
    ) {
        val foreign = context.unit.player?.id != context.currentPlayer.id
        val notHuman = context.currentPlayer.type != PlayerType.HUMAN_LOCAL
        if (!context.localTurn || foreign || notHuman) reasons += ActionBlock(ActionBlockReason.NOT_LOCAL_TURN)
    }

    private fun addSpentActionBlocks(
        unit: GameUnit,
        reasons: MutableList<ActionBlock>,
    ) {
        if (unit.hasResupplied) {
            reasons += ActionBlock(ActionBlockReason.ALREADY_RESUPPLIED)
            return
        }
        if (unit.hasMoved) reasons += ActionBlock(ActionBlockReason.ALREADY_MOVED)
        if (unit.hasFired) reasons += ActionBlock(ActionBlockReason.ALREADY_FIRED)
    }

    /** The domain/terrain half of [SupplyRules.canResupply]'s eligibility test, named. */
    private fun addSupplyTerrainBlocks(
        context: UnitActionContext,
        reasons: MutableList<ActionBlock>,
    ) {
        val unit = context.unit
        when {
            UnitPredicates.isAir(unit) ->
                if (!MovementRules.hasAirfield(context.map, unit)) reasons += ActionBlock(ActionBlockReason.NO_AIRFIELD)

            UnitPredicates.isSea(unit) ->
                if (unit.getHex()?.terrain == TerrainType.PORT.value) {
                    reasons += ActionBlock(ActionBlockReason.NO_NAVAL_RESUPPLY_IN_PORT)
                }

            !UnitPredicates.isGround(unit) -> reasons += ActionBlock(ActionBlockReason.INVALID_SUPPLY_TERRAIN)
        }
    }

    private fun addPrestigeBlock(
        context: UnitActionContext,
        costPerPoint: Int,
        reasons: MutableList<ActionBlock>,
    ) {
        val missing = costPerPoint - context.currentPlayer.prestige
        if (missing > 0) reasons += ActionBlock(ActionBlockReason.NEEDS_PRESTIGE, missing)
    }

    /** How many of [wanted] strength points the player can actually pay for -- the same
     *  `prestige / unitCost` clamp `Player.reinforceUnit` applies before committing. */
    private fun affordablePoints(
        context: UnitActionContext,
        costPerPoint: Int,
        wanted: Int,
    ): Int {
        if (costPerPoint <= 0 || wanted <= 0) return maxOf(wanted, 0)
        return minOf(wanted, context.currentPlayer.prestige / costPerPoint)
    }
}
