package org.osada.rules

/*
 * Typed vocabulary for "can this unit do this right now, and why not?"
 * (`docs/design/action-affordances-and-objectives.md` §2).
 *
 * Nothing here is English. The rules layer answers with stable enum values plus the exact numbers
 * the command itself would use; the UI localizes them. The existing predicates
 * ([UnitPredicates.canMount], [SupplyRules.canResupply], [SupplyRules.canReinforce],
 * [EmbarkRules.canEmbark] and friends) remain the final authority -- [UnitActionAvailability]
 * explains their answer, it does not re-decide it.
 */

/**
 * The unit-context commands, in the stable order the action strip renders them.
 *
 * The six engineering commands were added 2026-08-25 with OG's Build and Repair optional rule
 * (manual 9.3, `rules/Engineering`). They sit beside the two mine actions for the same reason those
 * do -- an engineer's work belongs together in the reading order -- and follow the same rule: NOT
 * APPLICABLE, i.e. absent from the strip entirely, unless `build_and_repair` is on and the
 * formation carries the ability, so no existing campaign gains a chip. [DEMOLISH] is one chip for
 * OG's two demolition sub-rules (9.3.1 bridge, 9.3.7 terrain) because a hex can never offer both.
 *
 * [LAY_MINES] and [CLEAR_MINES] were added 2026-08-18 with the minefield mechanic.
 * They sit after the supply group and before Undo because that is
 * where an engineer's work belongs in the reading order, and both are NOT APPLICABLE — absent from
 * the strip entirely — unless the `minefields` ruleset key is on and the formation actually carries
 * OG's ability, so no existing campaign gains a chip.
 */
enum class UnitActionId(
    val id: String,
) {
    MOUNT("mount"),
    EMBARK("embark"),
    RESUPPLY("resupply"),
    REINFORCE("reinforce"),

    /** OG's cheap, veterancy-costing replacement (`rules/GreenReplacements`, 2026-08-30). A
     *  SIBLING of [REINFORCE] rather than a replacement for it: OG offers both. */
    GREEN_REINFORCE("green_reinforce"),

    /** OG's `rem_leader` — dismiss the formation's commander (`rules/LeaderDismissal`, 2026-08-30). */
    DISMISS_LEADER("dismiss_leader"),
    OVERSTRENGTH("overstrength"),
    LAY_MINES("lay_mines"),

    /** OG 9.2's barrage. Unlike every other action here it needs a TARGET, so choosing it opens a
     *  targeting mode rather than doing something at once (`rules/Barrage`, added 2026-08-26). */
    BARRAGE("barrage"),

    /** OG's railway transport. Like [BARRAGE] it needs a TARGET, so choosing it opens a
     *  destination mode rather than doing something at once (`rules/RailTransport`, 2026-08-28). */
    RAIL_MOVE("rail_move"),
    CLEAR_MINES("clear_mines"),
    BUILD_BRIDGE("build_bridge"),
    BUILD_FORTIFICATION("build_fortification"),
    BUILD_AIRFIELD("build_airfield"),
    BUILD_PORT("build_port"),

    /** OG 9.3.6's railroad station — the fifth facility, added 2026-08-27 once the per-hex station
     *  flag was located and the 915 authored ones imported (`rules/EngineeringWork.STATION`). */
    BUILD_STATION("build_station"),
    REPAIR("repair"),
    DEMOLISH("demolish"),

    /** Cutting the railway line under the formation — OSADA's own demolition, which is why it has a
     *  chip of its own instead of sharing [DEMOLISH]: a rail hex can also be a city, a port or a
     *  bridge, so the two demolitions really can be offered on the same hex and one chip could not
     *  say which it would do (`rules/EngineeringWork.BLOW_RAIL`). */
    BLOW_RAIL("blow_rail"),
    UNDO("undo"),

    /** Lift a formation placed during the opening deployment back into the reserve tray
     *  (`UnitActionAvailability.withdraw`). Absent from the strip -- not merely disabled -- once the window closes,
     *  so it takes no room for the rest of the battle (2026-09-25 user request). */
    WITHDRAW("withdraw"),

    /** Puts the formation inside a friendly carrier's or airbase's hangar on or beside its hex
     *  (`rules/HangarStowage`, 2026-10-07). */
    LAND_IN_HANGAR("hangar_land"),

    /** Opens a container's hangar: who is inside, and the take-off order (`ui/HangarDialog`). */
    HANGAR("hangar"),

    /** A land formation goes inside a friendly fortification on or beside its hex (2026-10-07). */
    ENTER_COVER("cover_enter"),

    /** Opens a fortification's garrison: who is inside, and the order to come out (`ui/HangarDialog`). */
    COVER("cover"),
    SLEEP("sleep"),

    /** OG's cautious move: route only through spotted hexes (`rules/CautiousMove`, 2026-10-08). */
    CAUTIOUS_MOVE("cautious"),
}

/** Why an applicable action is unavailable right now. Ordered most-immediate-first by the caller. */
enum class ActionBlockReason {
    /** The unit has already used its move this turn. */
    ALREADY_MOVED,

    /** The unit has already fired this turn. */
    ALREADY_FIRED,

    /** Barrage: every hex in range is already spotted, so there is nothing to shell blind. Not an
     *  error — a gun that can see everything in front of it simply attacks instead (OG 9.2). */
    NO_BARRAGE_TARGET,

    /** The unit already resupplied or reinforced this turn. */
    ALREADY_RESUPPLIED,

    /** Transportable, but no organic ground transport is assigned to it. */
    NO_ORGANIC_TRANSPORT,

    /** No free air/naval transport point left in the player's pool. */
    NO_TRANSPORT_AVAILABLE,

    /** Railway: aboard a train, but not at a station (and without `No Need Station`). */
    NOT_AT_STATION,

    /** Not standing on the airfield/port the embarkation rule requires. */
    NOT_AT_TRANSPORT_FACILITY,

    /** Carried, but every adjacent hex is occupied or impassable. */
    NO_DISEMBARK_HEX,

    /** Ammo, fuel (and any transport's) are already full. */
    FULLY_SUPPLIED,

    /** Aircraft is neither on nor beside an own airfield, nor on an own carrier. */
    NO_AIRFIELD,

    /** Terrain/domain does not support a manual supply action for this unit at all. */
    INVALID_SUPPLY_TERRAIN,

    /** Already at normal strength, so ordinary reinforcement has nothing to restore. */
    AT_FULL_STRENGTH,

    /** Eligible, but local efficiency rounds the restorable strength down to nothing. */
    NO_STRENGTH_RESTORED_HERE,

    /** Not enough prestige; `amount` is how much more is needed. */
    NEEDS_PRESTIGE,

    /** Not enough experience for overstrength; `amount` is how much more is needed. */
    NEEDS_EXPERIENCE,

    /** Overstrength was already used by this unit (or spent by firing/moving). */
    OVERSTRENGTH_SPENT,

    /** Already at the overstrength ceiling its experience allows. */
    OVERSTRENGTH_CAP_REACHED,

    /** The move became final because it revealed something new. */
    UNDO_NEW_INTELLIGENCE,

    /** The move became final because the unit was caught by surprise. */
    UNDO_SURPRISED,

    /** The move became final because anti-air fire intercepted it. */
    UNDO_INTERCEPTED,

    /** The move became final because combat followed it. */
    UNDO_COMBAT,

    /** The move became final because another irreversible command followed it. */
    UNDO_IRREVERSIBLE_ACTION,

    /** There is no recorded move for this unit to undo. */
    UNDO_NOTHING_TO_UNDO,

    /** The unit has nothing left to do this turn. */
    NO_ACTION_LEFT,

    /** OG requires a mine-laying unit to have taken no other action this turn. */
    MINES_NEED_UNSPENT_TURN,

    /** Not enough ammunition left to lay a minefield; `amount` is how much more is needed. */
    NOT_ENOUGH_AMMO_FOR_MINES,

    /** This hex already carries this side's minefield. */
    MINEFIELD_ALREADY_HERE,

    /** There is no minefield on this hex to clear. */
    NO_MINEFIELD_HERE,

    /** OG requires an engineering unit to have taken no other action this turn. */
    ENGINEERING_NEEDS_UNSPENT_TURN,

    /** The same rule as relaxed by the efile's `build_start_ex`: the unit may have moved, but must
     *  not have fired or resupplied. */
    ENGINEERING_NEEDS_UNFIRED,

    /** Another job is already under way on this hex; `amount` is the turns left on it. */
    ENGINEERING_IN_PROGRESS,

    /** Every friendly hangar within reach is full. */
    HANGAR_FULL,

    /** Every friendly fortification within reach is full. */
    COVER_FULL,

    /** Build bridge: the hex is not an unbridged river or stream crossing. */
    BRIDGE_NEEDS_CROSSING,

    /** Build fortification: not dry buildable ground, or already fortified. */
    FORTIFICATION_NEEDS_GROUND,

    /** Build airfield: OG's "must be in a clear hex". */
    AIRFIELD_NEEDS_CLEAR,

    /** Build port: not dry ground on the coast, or already a port. */
    PORT_NEEDS_COAST,

    /** Build station: OG's "must be in a rail hex", without a station already. */
    STATION_NEEDS_RAIL,

    /** The scenario author switched this kind of engineering off (OG's Build / Blow / Repair option). */
    SCENARIO_FORBIDS_ENGINEERING,

    /** Not the local human player's turn, so no command may be issued. */
    NOT_LOCAL_TURN,
}

/** One blocking reason plus the exact quantity it refers to, when it has one. */
data class ActionBlock(
    val reason: ActionBlockReason,
    val amount: Int = 0,
)

/** What the command would actually do. The numbers are the command's own, never re-derived. */
enum class ActionEffectKind {
    /** The transport's movement and vulnerability will apply. */
    MOUNT_TRANSPORT_STATS,

    /** The unit's own combat and movement statistics will apply. */
    DISMOUNT_OWN_STATS,

    /** Limbering is free but does not return a spent move. */
    LIMBER_TOGGLE_FREE,

    /** Boards air transport. */
    EMBARK_AIR,

    /** Boards naval transport. */
    EMBARK_NAVAL,

    /**
     * The formation's organic transport cannot fly and will be left on the airfield — OG's *"to be
     * able to carry its organic transport into the air transport, the unit's organic transport must
     * be also Airmobile/Airborne"*.
     *
     * Shown BEFORE the player commits, because the rule takes away something they bought and
     * OSADA has nowhere to park it: a mechanic with a cost must have a visible cause
     * (`docs/design/action-affordances-and-objectives.md` §1.1), and 5,264 of the 5,937 shipped ground transports
     * cannot fly.
     */
    EMBARK_DROPS_TRANSPORT,

    /** Leaves the carrier onto an adjacent hex; `amount` is how many hexes qualify. */
    DISEMBARK,

    /** `amount` ammo restored. */
    SUPPLY_AMMO,

    /** `amount` fuel restored. */
    SUPPLY_FUEL,

    /** `amount` ammo restored to the organic transport. */
    SUPPLY_TRANSPORT_AMMO,

    /** `amount` fuel restored to the organic transport. */
    SUPPLY_TRANSPORT_FUEL,

    /** `amount` percent local supply efficiency. */
    SUPPLY_EFFICIENCY,

    /** `amount` strength points restored. */
    STRENGTH_GAIN,

    /** Experience falls from `detail` to `amount` because the replacements are untrained
     *  (`ReplacementExperience`). Emitted only when the rule is on and there is experience to lose,
     *  so a formation with none never shows a line about it. */
    EXPERIENCE_DILUTION,

    /** `amount` prestige spent. */
    PRESTIGE_COST,

    /** Raises strength to `amount`, out of a ceiling of `detail`. */
    OVERSTRENGTH_TARGET,

    /** Uses up the unit's action for this turn. */
    ENDS_UNIT_ACTION,

    /** Restores the pre-move position, fuel and movement state. */
    UNDO_RESTORE,

    /** Returns the formation to the reserve tray, to be placed again; no prestige changes hands. */
    WITHDRAW_TO_TRAY,

    /** An auxiliary goes back into the reserve tray as an auxiliary: still not core, and still left
     *  behind when the battle ends. */
    WITHDRAW_AUXILIARY_TO_TRAY,

    /** Skips the unit in ready-unit navigation, still counted by the end-turn warning. */
    SLEEP_SKIP,

    /** The move range and route will use spotted hexes only (`rules/CautiousMove`). */
    CAUTIOUS_ROUTE,

    /** Back to the ordinary move range. */
    NORMAL_ROUTE,

    /** Returns the unit to ready-unit navigation. */
    WAKE_RESTORE,

    /** Lays a minefield on this hex; `amount` ammunition points spent. */
    LAY_MINEFIELD,

    /** Opens the Barrage targeting mode; `amount` is how many hexes are currently shellable, so the
     *  chip can say "no unseen hex in range" before the player enters a mode with nothing in it. */
    OPEN_BARRAGE_TARGETING,

    /** Boards a train, which then moves along the track; `amount` is how many trains are free. */
    BOARD_TRAIN,

    /** Leaves the train here and returns it to the pool; the formation can still move. */
    LEAVE_TRAIN,

    /** Leaves the train here after it moved this turn, which ends the formation's move. */
    LEAVE_TRAIN_ENDS_MOVE,

    /** Attempts to clear the minefield here; `amount` is the percentage chance of success. */
    CLEAR_MINEFIELD,

    /** A failed clearing attempt suppresses the formation. */
    CLEAR_MINEFIELD_RISK,

    /** `amount` prestige, charged once, in full, when the work begins -- never per turn. */
    PRESTIGE_COST_ONCE,

    /** Starts construction here; `amount` is how many of this side's turns it takes, the turn it
     *  begins on included (the countdown ticks at the end of every one of them). */
    BUILD_TURNS,

    /** Engineering may begin after moving (`build_start_ex`) while the unit has not fired or
     *  resupplied. */
    ENGINEERING_START_AFTER_MOVE,

    /** Engineering must begin before the unit moves, fires or resupplies. */
    ENGINEERING_START_UNSPENT,

    /** Destroys what is on this hex outright, with no waiting. */
    DEMOLISH_NOW,

    /** Goes inside the hangar: out of sight and reach, refuelled and rearmed every round, and able
     *  to take off from the next turn. */
    HANGAR_STOW,

    /** Goes inside the fortification: out of sight and reach, resupplied every round, and able to
     *  come out from the next turn. */
    COVER_STOW,

    /** `amount` formations aboard out of `detail` places. */
    HANGAR_OCCUPANCY,
}

/** One effect line plus its quantities. */
data class ActionEffect(
    val kind: ActionEffectKind,
    val amount: Int = 0,
    val detail: Int = 0,
)

/**
 * Whether an action belongs on this unit's strip at all ([applicable]), whether it can be used
 * right now ([enabled]), why not ([reasons]) and what it would do ([effects]).
 *
 * A non-applicable action is omitted entirely -- a fortification never shows Mount. An applicable
 * but disabled action stays visible so the rule that blocks it can be read.
 */
data class ActionAvailability(
    val action: UnitActionId,
    val applicable: Boolean,
    val enabled: Boolean,
    val reasons: List<ActionBlock> = emptyList(),
    val effects: List<ActionEffect> = emptyList(),
) {
    companion object {
        fun notApplicable(action: UnitActionId): ActionAvailability =
            ActionAvailability(action, applicable = false, enabled = false)
    }
}
