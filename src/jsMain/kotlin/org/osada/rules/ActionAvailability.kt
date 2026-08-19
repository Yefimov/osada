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
 * [LAY_MINES] and [CLEAR_MINES] were added 2026-08-18 with the minefield mechanic
 * (`docs/og-fidelity-plan.md` C.1). They sit after the supply group and before Undo because that is
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
    OVERSTRENGTH("overstrength"),
    LAY_MINES("lay_mines"),
    CLEAR_MINES("clear_mines"),
    UNDO("undo"),
    SLEEP("sleep"),
}

/** Why an applicable action is unavailable right now. Ordered most-immediate-first by the caller. */
enum class ActionBlockReason {
    /** The unit has already used its move this turn. */
    ALREADY_MOVED,

    /** The unit has already fired this turn. */
    ALREADY_FIRED,

    /** The unit already resupplied or reinforced this turn. */
    ALREADY_RESUPPLIED,

    /** Transportable, but no organic ground transport is assigned to it. */
    NO_ORGANIC_TRANSPORT,

    /** No free air/naval transport point left in the player's pool. */
    NO_TRANSPORT_AVAILABLE,

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

    /** The move became final because a hidden enemy stopped it. */
    UNDO_STOPPED_BY_HIDDEN_ENEMY,

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

    /** Skips the unit in ready-unit navigation, still counted by the end-turn warning. */
    SLEEP_SKIP,

    /** Returns the unit to ready-unit navigation. */
    WAKE_RESTORE,

    /** Lays a minefield on this hex; `amount` ammunition points spent. */
    LAY_MINEFIELD,

    /** Attempts to clear the minefield here; `amount` is the percentage chance of success. */
    CLEAR_MINEFIELD,

    /** A failed clearing attempt suppresses the formation. */
    CLEAR_MINEFIELD_RISK,
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
