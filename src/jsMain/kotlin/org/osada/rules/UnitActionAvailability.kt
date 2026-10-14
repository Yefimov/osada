package org.osada.rules

import org.osada.PlayerType
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.model.Equipment
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.UndoInvalidation
import org.osada.model.canUndoMove
import org.osada.model.isInDeployZone
import org.osada.model.isInitialDeploymentWindow
import org.osada.model.isOutOfZoneDeployTarget
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
     *  callers filter on [ActionAvailability.applicable].
     *
     *  Undo sits right after Reinforce rather than at the tail, next to Lay/Clear Mines and Sleep:
     *  it is the one rescue action in the strip (a misclick undone before it costs a whole turn),
     *  and at the tail it could scroll out of reach on a full strip -- exactly the report that moved
     *  it here. */
    fun all(context: UnitActionContext): List<ActionAvailability> =
        listOf(
            // First, because while it is applicable at all it is the one thing a just-placed
            // formation is most likely being clicked for.
            withdraw(context),
            // Second, and not at the end where it was built: the strip shows six chips a page, so
            // last place put it on the "More" page for most formations and nobody found it. It is
            // only applicable before the unit moves -- exactly when the route is being chosen -- so
            // it never holds a slot for the rest of the turn (owner's request, 2026-10-09).
            CautiousMove.availability(context),
            mount(context),
            embark(context),
            resupply(context),
            reinforce(context),
            // Beside its sibling: the strip folds the two into one Reinforce menu (2026-10-09). It
            // was missing from this list altogether, so the green action never reached the strip.
            greenReinforce(context),
            undo(context),
            overstrength(context),
            layMines(context),
            barrage(context),
            railMove(context),
            clearMines(context),
            engineering(context, UnitActionId.BUILD_BRIDGE),
            engineering(context, UnitActionId.BUILD_FORTIFICATION),
            engineering(context, UnitActionId.BUILD_AIRFIELD),
            engineering(context, UnitActionId.BUILD_PORT),
            engineering(context, UnitActionId.BUILD_STATION),
            engineering(context, UnitActionId.REPAIR),
            engineering(context, UnitActionId.DEMOLISH),
            engineering(context, UnitActionId.BLOW_RAIL),
            HangarActions.land(context),
            HangarActions.hangar(context),
            HangarActions.enterCover(context),
            HangarActions.cover(context),
            HangarActions.aboard(context),
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
            UnitActionId.LAY_MINES -> layMines(context)
            UnitActionId.BARRAGE -> barrage(context)
            UnitActionId.RAIL_MOVE -> railMove(context)
            UnitActionId.CLEAR_MINES -> clearMines(context)
            UnitActionId.BUILD_BRIDGE,
            UnitActionId.BUILD_FORTIFICATION,
            UnitActionId.BUILD_AIRFIELD,
            UnitActionId.BUILD_PORT,
            UnitActionId.BUILD_STATION,
            UnitActionId.REPAIR,
            UnitActionId.DEMOLISH,
            UnitActionId.BLOW_RAIL,
            -> engineering(context, action)

            UnitActionId.UNDO -> undo(context)
            UnitActionId.SLEEP -> sleep(context)
            else -> forSecondaryAction(action, context)
        }

    // ---- Mount / dismount --------------------------------------------------------------------

    /** Applicable to any ground formation that could ever ride its own transport, so an infantry
     *  formation bought without one still shows a disabled Mount with the real reason rather than
     *  silently missing the action. A fortification can never mount and never shows it. */
    private fun mount(context: UnitActionContext): ActionAvailability {
        val unit = context.unit
        val transportable =
            !RailTransport.isEntrained(unit) &&
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
        // OG 8.3: *"Units can only mount or dismount BEFORE moving"* -- for everyone, including a
        // record carrying `Dismount after movement`. That ability is not a permission the player
        // exercises; it fires by itself once the ride ends (`MoveExecutor.dismountAfterMove`), so
        // there is nothing to unblock here. A permission reading briefly stood on 2026-08-26 and
        // was corrected against the manual the same day -- see the plan's §Q.
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

    /** Show embarkation only if this mission has a pool this formation can use. A busy pool
     *  remains applicable; an already carried formation always keeps its disembark action. */
    private fun embark(context: UnitActionContext): ActionAvailability {
        val unit = context.unit
        val carried = unit.carrier != 0
        // A train is left with the Rail chip, not Disembark: the train record says nothing about
        // `embark`, and naval disembarkation onto an adjacent hex is not how a train unloads.
        val aboardTrain = RailTransport.isEntrained(unit)
        if (aboardTrain || (!carried && !EmbarkRules.hasTransportPool(unit))) {
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
        val onAirfield = hex?.terrain == TerrainType.AIRFIELD.value && EmbarkRules.permitsAir(data)
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
                EmbarkRules.permitsAir(data) -> ActionEffectKind.EMBARK_AIR
                else -> ActionEffectKind.EMBARK_NAVAL
            }
        val effects = mutableListOf(ActionEffect(effect))
        if (effect == ActionEffectKind.EMBARK_AIR && losesTransportToAirlift(unit)) {
            effects += ActionEffect(ActionEffectKind.EMBARK_DROPS_TRANSPORT)
        }
        return ActionAvailability(
            UnitActionId.EMBARK,
            true,
            reasons.isEmpty(),
            reasons,
            effects,
        )
    }

    /** Whether an airlift would leave this formation's organic transport behind — see
     *  [UnitCapabilities.transportSurvivesAirlift] for OG's rule and
     *  `UnitMountOperations.leaveGroundedTransportBehind` for the act. */
    private fun losesTransportToAirlift(unit: GameUnit): Boolean {
        val data = unit.transport?.let { Equipment.getEquipment(it.eqid) }
        return data != null && !UnitCapabilities.transportSurvivesAirlift(data)
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

    /** The tail of [forAction]'s dispatch, split off to keep it inside detekt's complexity budget
     *  as OG's green replacements and leader dismissal joined the list. */
    private fun forSecondaryAction(
        action: UnitActionId,
        context: UnitActionContext,
    ): ActionAvailability =
        when (action) {
            UnitActionId.GREEN_REINFORCE -> greenReinforce(context)
            UnitActionId.DISMISS_LEADER -> dismissLeader(context)
            UnitActionId.WITHDRAW -> withdraw(context)
            UnitActionId.LAND_IN_HANGAR -> HangarActions.land(context)
            UnitActionId.HANGAR -> HangarActions.hangar(context)
            UnitActionId.ENTER_COVER -> HangarActions.enterCover(context)
            UnitActionId.COVER -> HangarActions.cover(context)
            UnitActionId.ABOARD -> HangarActions.aboard(context)
            UnitActionId.CAUTIOUS_MOVE -> CautiousMove.availability(context)
            else -> ActionAvailability.notApplicable(action)
        }

    // ---- Dismiss the commander (`rem_leader`) -----------------------------------------------------

    /** Applicable only to a formation that HAS a commander, under an efile that allows dismissal. */
    private fun dismissLeader(context: UnitActionContext): ActionAvailability {
        val unit = context.unit
        if (!LeaderDismissal.canDismiss(unit)) {
            return ActionAvailability.notApplicable(UnitActionId.DISMISS_LEADER)
        }
        val reasons = mutableListOf<ActionBlock>()
        addTurnBlock(context, reasons)
        // No ENDS_UNIT_ACTION: dismissal is free, so the formation keeps its turn.
        return ActionAvailability(UnitActionId.DISMISS_LEADER, true, reasons.isEmpty(), reasons, mutableListOf())
    }

    // ---- Green replacements ---------------------------------------------------------------------

    /**
     * OG's cheap replacement, offered beside the ordinary one when the efile enables it.
     *
     * Applicable on exactly the same terms as [reinforce] — the two differ in price and in what
     * they do to experience, not in when they may be used — so it reuses `SupplyRules` throughout
     * rather than inventing a second eligibility rule that could drift from the first.
     */
    private fun greenReinforce(context: UnitActionContext): ActionAvailability {
        val unit = context.unit
        val applicable = GreenReplacements.enabled() && unit.strength < unit.basicStrength
        if (!applicable) return ActionAvailability.notApplicable(UnitActionId.GREEN_REINFORCE)

        val reasons = mutableListOf<ActionBlock>()
        addTurnBlock(context, reasons)
        addSpentActionBlocks(unit, reasons)
        addSupplyTerrainBlocks(context, reasons)
        val gain = SupplyRules.getReinforceValue(context.map, unit, false)
        if (SupplyRules.canReinforce(context.map, unit, false) && gain <= 0) {
            reasons += ActionBlock(ActionBlockReason.NO_STRENGTH_RESTORED_HERE)
        }
        val perPoint = GreenReplacements.costPerStrength(unit)
        addPrestigeBlock(context, perPoint, reasons)

        val enabled = SupplyRules.canReinforce(context.map, unit, false) && gain > 0 && reasons.isEmpty()
        val affordable = affordablePoints(context, perPoint, gain)
        val diluted = GreenReplacements.experienceAfter(unit, affordable)
        val effects =
            listOfNotNull(
                ActionEffect(ActionEffectKind.STRENGTH_GAIN, affordable),
                ActionEffect(ActionEffectKind.PRESTIGE_COST, affordable * perPoint),
                // Green replacements dilute by construction -- that is what they are FOR -- so the
                // preview always states the resulting experience where there is any to lose.
                ActionEffect(ActionEffectKind.EXPERIENCE_DILUTION, diluted, unit.experience)
                    .takeIf { diluted < unit.experience },
                ActionEffect(ActionEffectKind.ENDS_UNIT_ACTION),
            ).toMutableList()
        return ActionAvailability(UnitActionId.GREEN_REINFORCE, true, enabled, reasons, effects)
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
        val supplyContext = SupplyContextRules.getSupplyContext(context.map, unit, reinforcement = true)
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

    // ---- Withdraw to the reserve tray ----------------------------------------------------------

    /**
     * Take a formation placed during the opening deployment back into the reserve tray.
     *
     * Applicable only while [isInitialDeploymentWindow] is open -- turn 1, before any of the
     * player's formations has moved or fired -- so the chip disappears for good the moment the
     * battle actually starts. Three narrowings keep it from doing damage:
     *  - only a CORE formation on the player's own roster, or an AUXILIARY this battle lent the
     *    player: the tray IS the roster's undeployed half, and an auxiliary joins it on the way in
     *    (`GameMap.undeployUnit`) without becoming core. Anything else would vanish rather than
     *    wait there;
     *  - only one standing where it could be put back ([canRedeployHere]): an author-placed
     *    formation outside the deploy zone is placed content, and withdrawing it would change the
     *    battle's opening position with no way to restore it (the same rule
     *    `CoreUnitListOperations.liftsIntoTray` applies);
     *  - only a formation that has done nothing itself, which the window already implies.
     */
    private fun withdraw(context: UnitActionContext): ActionAvailability {
        val unit = context.unit
        val map = context.map
        val player = context.currentPlayer
        val auxiliary = unit.isTemporaryBorrowed && !unit.isCore
        val onRoster = unit.isCore && player.getCoreUnitList().any { it === unit }
        val applicable =
            player.type == PlayerType.HUMAN_LOCAL &&
                unit.owner == player.id &&
                (onRoster || auxiliary) &&
                unit.isDeployed &&
                map.isInitialDeploymentWindow(player) &&
                canRedeployHere(map, unit)
        if (!applicable) return ActionAvailability.notApplicable(UnitActionId.WITHDRAW)
        val reasons = mutableListOf<ActionBlock>()
        addTurnBlock(context, reasons)
        val effect = if (auxiliary) ActionEffectKind.WITHDRAW_AUXILIARY_TO_TRAY else ActionEffectKind.WITHDRAW_TO_TRAY
        return ActionAvailability(
            UnitActionId.WITHDRAW,
            true,
            reasons.isEmpty(),
            reasons,
            listOf(ActionEffect(effect)),
        )
    }

    private fun canRedeployHere(
        map: GameMap,
        unit: GameUnit,
    ): Boolean {
        val pos = unit.getPos()
        val side = unit.player?.side
        return pos != null &&
            side != null &&
            (map.isInDeployZone(side, pos.row, pos.col) || map.isOutOfZoneDeployTarget(unit, pos.row, pos.col))
    }

    private fun undoReason(invalidation: UndoInvalidation?): ActionBlockReason =
        when (invalidation) {
            UndoInvalidation.NEW_INTELLIGENCE -> ActionBlockReason.UNDO_NEW_INTELLIGENCE
            UndoInvalidation.SURPRISED -> ActionBlockReason.UNDO_SURPRISED
            UndoInvalidation.INTERCEPTED -> ActionBlockReason.UNDO_INTERCEPTED
            UndoInvalidation.COMBAT -> ActionBlockReason.UNDO_COMBAT
            UndoInvalidation.IRREVERSIBLE_ACTION -> ActionBlockReason.UNDO_IRREVERSIBLE_ACTION
            null -> ActionBlockReason.UNDO_NOTHING_TO_UNDO
        }

    // ---- Minefields --------------------------------------------------------------------------

    /**
     * OG 9.9: a unit with `Drop mines` lays a field on the hex it stands on, *"having taken no
     * previous action that turn"*, for two ammunition points.
     *
     * Not applicable at all — no chip on the strip — unless the `minefields` key is on and this
     * equipment carries the ability. A chip that could never be used would be exactly the "switch in
     * the editor that changes nothing" `ruleset-profiles.md` §2 exists to prevent, in the action bar
     * instead of the rules window.
     */

    private fun layMines(context: UnitActionContext): ActionAvailability {
        val unit = context.unit
        if (!MineAbilities.canDropMines(unit)) return ActionAvailability.notApplicable(UnitActionId.LAY_MINES)
        val side = unit.player?.side ?: -1
        val hex = unit.getHex()
        val reasons = mutableListOf<ActionBlock>()
        addTurnBlock(context, reasons)
        if (unit.hasMoved || unit.hasFired || unit.hasResupplied) {
            reasons += ActionBlock(ActionBlockReason.MINES_NEED_UNSPENT_TURN)
        }
        val missingAmmo = Minefields.LAY_MINES_AMMO_COST - unit.getAmmo()
        if (missingAmmo > 0) reasons += ActionBlock(ActionBlockReason.NOT_ENOUGH_AMMO_FOR_MINES, missingAmmo)
        if (hex != null && Minefields.isDetectedBy(hex, side) && !Minefields.threatens(hex, side)) {
            reasons += ActionBlock(ActionBlockReason.MINEFIELD_ALREADY_HERE)
        }
        val effects =
            listOf(
                ActionEffect(ActionEffectKind.LAY_MINEFIELD, Minefields.LAY_MINES_AMMO_COST),
                ActionEffect(ActionEffectKind.ENDS_UNIT_ACTION),
            )
        return ActionAvailability(UnitActionId.LAY_MINES, true, reasons.isEmpty(), reasons, effects)
    }

    /**
     * OG 9.2: a formation whose Bomber Size marks it `'='` may shell a hex it cannot see.
     *
     * Applicable whenever the equipment can barrage AT ALL under the current rules, so a gun that
     * has already fired shows a disabled chip with the reason rather than a chip that vanishes --
     * the contract `docs/design/action-affordances-and-objectives.md` §2 sets for every action here.
     */
    private fun barrage(context: UnitActionContext): ActionAvailability {
        val unit = context.unit
        if (!Barrage.canBarrage(unit)) return ActionAvailability.notApplicable(UnitActionId.BARRAGE)
        val reasons = mutableListOf<ActionBlock>()
        addTurnBlock(context, reasons)
        if (unit.hasFired) reasons += ActionBlock(ActionBlockReason.ALREADY_FIRED)
        val missingAmmo = Barrage.AMMO_COST - unit.getAmmo()
        if (missingAmmo > 0) reasons += ActionBlock(ActionBlockReason.NOT_ENOUGH_AMMO_FOR_MINES, missingAmmo)
        val targets = Barrage.targets(context.map, unit).size
        if (targets == 0 && reasons.isEmpty()) reasons += ActionBlock(ActionBlockReason.NO_BARRAGE_TARGET)
        return ActionAvailability(UnitActionId.BARRAGE, true, reasons.isEmpty(), reasons, barrageChipEffects(targets))
    }

    /**
     * OG's railway, as a toggle: board a train where the formation stands, or leave the one it is
     * on (`rules/RailTransport`). The train itself moves through the ordinary move range.
     *
     * **NOT APPLICABLE unless the scenario grants this player a rail pool** -- 120 of the 502
     * deployed scenarios do -- so the chip is absent in the rest
     * rather than present and permanently disabled, the rule the engineering chips follow. A pool
     * that is merely used up right now shows the chip disabled, with the reason.
     *
     * **Equipment OG forbids the railway to is not applicable either**: a fortification that cannot
     * be entrained is not a formation waiting for a train (`EquipmentData.railTransportable`).
     */
    private fun railMove(context: UnitActionContext): ActionAvailability =
        if (RailTransport.isEntrained(context.unit)) leaveTrain(context) else boardTrain(context)

    private fun boardTrain(context: UnitActionContext): ActionAvailability {
        val unit = context.unit
        val offered =
            (unit.player?.railTransportsMax ?: 0) > 0 &&
                unit.carrier == 0 &&
                UnitPredicates.isGround(unit) &&
                RailTransport.permitsRail(unit)
        if (!offered || !RailTransport.isBoardingPoint(unit.getHex(), unit)) {
            return ActionAvailability.notApplicable(UnitActionId.RAIL_MOVE)
        }
        val reasons = mutableListOf<ActionBlock>()
        addTurnBlock(context, reasons)
        if (unit.hasMoved) reasons += ActionBlock(ActionBlockReason.ALREADY_MOVED)
        if (unit.hasFired) reasons += ActionBlock(ActionBlockReason.ALREADY_FIRED)
        val pool = unit.player?.railTransports ?: 0
        if (pool <= 0 || TrainRecords.trainFor(unit) == null) {
            reasons += ActionBlock(ActionBlockReason.NO_TRANSPORT_AVAILABLE)
        }
        val effects = listOf(ActionEffect(ActionEffectKind.BOARD_TRAIN, pool))
        return ActionAvailability(UnitActionId.RAIL_MOVE, true, reasons.isEmpty(), reasons, effects)
    }

    /** The same chip on a formation already aboard: it may step off at a station, or anywhere on
     *  the track with `No Need Station`. */
    private fun leaveTrain(context: UnitActionContext): ActionAvailability {
        val unit = context.unit
        val reasons = mutableListOf<ActionBlock>()
        addTurnBlock(context, reasons)
        if (!RailTransport.canDetrain(unit)) reasons += ActionBlock(ActionBlockReason.NOT_AT_STATION)
        val effect = if (unit.hasMoved) ActionEffectKind.LEAVE_TRAIN_ENDS_MOVE else ActionEffectKind.LEAVE_TRAIN
        val effects = listOf(ActionEffect(effect))
        return ActionAvailability(UnitActionId.RAIL_MOVE, true, reasons.isEmpty(), reasons, effects)
    }

    /**
     * OG 9.9: a unit able to clear mines must be STANDING in the field, and *"the attempt can fail,
     * and a failed attempt suppresses the unit."*
     *
     * The unit may have moved onto the field this turn — OG imposes the no-previous-action rule on
     * laying, not on clearing, and a sapper that walked to the minefield has done exactly what it
     * was sent to do. It may not have fired.
     */
    private fun clearMines(context: UnitActionContext): ActionAvailability {
        val unit = context.unit
        if (!MineAbilities.canClearMines(unit)) return ActionAvailability.notApplicable(UnitActionId.CLEAR_MINES)
        val hex = unit.getHex()
        val reasons = mutableListOf<ActionBlock>()
        addTurnBlock(context, reasons)
        if (unit.hasFired || unit.hasResupplied) reasons += ActionBlock(ActionBlockReason.ALREADY_FIRED)
        if (hex == null || hex.mines == 0) reasons += ActionBlock(ActionBlockReason.NO_MINEFIELD_HERE)
        val effects =
            listOf(
                ActionEffect(ActionEffectKind.CLEAR_MINEFIELD, MineAbilities.clearSuccessPercent()),
                ActionEffect(ActionEffectKind.CLEAR_MINEFIELD_RISK),
                ActionEffect(ActionEffectKind.ENDS_UNIT_ACTION),
            )
        return ActionAvailability(UnitActionId.CLEAR_MINES, true, reasons.isEmpty(), reasons, effects)
    }

    // ---- Build and repair (OG 9.3) -----------------------------------------------------------

    /** Which engineering job each of the named chips orders. [UnitActionId.DEMOLISH] is
     *  deliberately absent: it stands for whichever of OG's two demolitions the hex allows, resolved
     *  per hex in [engineeringWorkFor], because a river hex offers a bridge to blow and a city
     *  offers its terrain and no hex ever offers both.
     *
     *  Cutting rail is NOT one of those two and is named here instead. Track runs through cities and
     *  over bridges, so that chip and this one really can be offered together, and a single chip
     *  would have to guess which the player meant. */
    private val ENGINEERING_ACTIONS: Map<UnitActionId, EngineeringWork> =
        mapOf(
            UnitActionId.BUILD_BRIDGE to EngineeringWork.BRIDGE,
            UnitActionId.BUILD_FORTIFICATION to EngineeringWork.FORTIFICATION,
            UnitActionId.BUILD_AIRFIELD to EngineeringWork.AIRFIELD,
            UnitActionId.BUILD_PORT to EngineeringWork.PORT,
            UnitActionId.BUILD_STATION to EngineeringWork.STATION,
            UnitActionId.REPAIR to EngineeringWork.REPAIR,
            UnitActionId.BLOW_RAIL to EngineeringWork.BLOW_RAIL,
        )

    private fun engineeringWorkFor(
        action: UnitActionId,
        available: List<EngineeringWork>,
    ): EngineeringWork? =
        if (action == UnitActionId.DEMOLISH) {
            // BLOW_RAIL is excluded because it has its own chip: without this, the Demolish chip on
            // a railway city would race it and blow whichever the enum happened to list first.
            available.firstOrNull { it.demolition && it != EngineeringWork.BLOW_RAIL }
        } else {
            ENGINEERING_ACTIONS[action]?.takeIf { it in available }
        }

    /**
     * OG 9.3: a sapper builds, a demolition unit blows, and both must have *"taken no previous
     * action that turn"*.
     *
     * Applicability is decided by [Engineering.availableWork], which answers the whole question at
     * once -- rule on, ability present, hex suitable, nothing already being built here. So a chip
     * appears only where the order could genuinely be given, and never on a hex where the terrain
     * makes it meaningless: no Build Port inland, no Build Bridge away from a crossing.
     *
     * The one condition that is a REASON rather than an absence is prestige. A player who cannot
     * afford a bridge today can afford it next turn, so the chip stays visible with
     * [ActionBlockReason.NEEDS_PRESTIGE] and the exact shortfall, the same way Reinforce does.
     *
     * **The five BUILD chips are the exception (owner's request 2026-10-07):** a sapper is always
     * shown them, greyed with the reason where this hex will not take the work
     * ([unsuitableConstruction]), so the player learns where an airfield or a port CAN go instead
     * of finding the button missing. Demolition, repair and cutting rail still appear only where
     * there is something to blow or repair.
     */
    private fun engineering(
        context: UnitActionContext,
        action: UnitActionId,
    ): ActionAvailability {
        val unit = context.unit
        val work =
            engineeringWorkFor(action, Engineering.availableWork(unit, ignoreScenarioBan = true))
                ?: return unsuitableConstruction(context, action) ?: ActionAvailability.notApplicable(action)
        val reasons = mutableListOf<ActionBlock>()
        addTurnBlock(context, reasons)
        // Shown greyed rather than hidden, so the player learns the scenario forbids it.
        if (!Engineering.authorisedByScenario(work)) {
            reasons += ActionBlock(ActionBlockReason.SCENARIO_FORBIDS_ENGINEERING)
        }
        // The efile's own `build_start_ex` may relax OG's "hasn't done any action" to "still has
        // its shot" -- one owner for that reading, so the chip and the order cannot disagree.
        val afterMove = Engineering.startsAfterMove()
        if (!Engineering.mayStartWork(unit)) {
            reasons +=
                ActionBlock(
                    if (afterMove) {
                        ActionBlockReason.ENGINEERING_NEEDS_UNFIRED
                    } else {
                        ActionBlockReason.ENGINEERING_NEEDS_UNSPENT_TURN
                    },
                )
        }
        val missingPrestige = work.costFor() - (unit.player?.prestige ?: 0)
        if (missingPrestige > 0) reasons += ActionBlock(ActionBlockReason.NEEDS_PRESTIGE, missingPrestige)
        val effects = ConstructionChips.effects(unit, work, afterMove)
        return ActionAvailability(action, true, reasons.isEmpty(), reasons, effects)
    }

    /** A greyed BUILD chip where the sapper's hex will not take the job ([ConstructionChips.unsuitable]). */
    private fun unsuitableConstruction(
        context: UnitActionContext,
        action: UnitActionId,
    ): ActionAvailability? {
        val reasons = mutableListOf<ActionBlock>().also { addTurnBlock(context, it) }
        return ConstructionChips.unsuitable(action, ENGINEERING_ACTIONS[action], context.unit, reasons)
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

    internal fun addTurnBlock(
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

            // A warship is eligible wherever it floats, port included -- the inverted PORT test
            // both surfaces used to assert was A.2's defect, not a rule.
            UnitPredicates.isSea(unit) -> Unit

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
