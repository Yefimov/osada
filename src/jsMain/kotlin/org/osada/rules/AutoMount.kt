package org.osada.rules

import org.osada.model.Cell
import org.osada.model.ExtendedCell
import org.osada.model.GameMap
import org.osada.model.GameUnit

/**
 * Open General's **ride-to-get-there**: a formation with its own transport that is sent somewhere
 * its legs cannot reach climbs aboard and drives, instead of the order being refused.
 *
 * OG shows this as a truck cursor over the hexes only the transport can reach; OSADA marks those
 * hexes as well ([org.osada.model.Hex.needsTransport]), because a cursor states the rule only once
 * the player is already pointing at the hex, and the reachable set is what they are reading when
 * they plan.
 *
 * ### Why this needs no ruleset key
 *
 * **It changes nothing about what is possible.** Mounting is already free
 * (`ActionEffectKind.LIMBER_TOGGLE_FREE`) and already legal for any formation that has not moved
 * (`UnitActionAvailability.mount`), so "mount, then drive" is a sequence the player can perform by
 * hand today, in exactly two clicks. This performs the same two steps from one click. No hex
 * becomes reachable that was not reachable before, no cost changes, and nothing is added to the
 * ruleset surface — which is the test `docs/design/ruleset-profiles.md` §2 sets for a rule, and
 * this is not one.
 *
 * What it does change is that the formation **ends its move mounted**, exactly as it would have
 * done by hand — and that is what makes OG's `Dismount after movement` ability (§N.3) matter for
 * the first time: a record carrying it can step down at the far end, and one that does not is
 * riding in a truck when the enemy turn starts.
 *
 * ### Determinism, and why the decision is not read off the overlay
 *
 * [requiresTransport] recomputes both ranges from the unit and the map rather than consulting the
 * selection overlay, so it answers the same on a peer that never drew one. `MoveExecutor` runs it
 * inside the move itself, which is the single path both the local player, the AI and a replayed
 * multiplayer `MoveUnit` command all go through — so no new command type exists and no peer can
 * mount while the other does not.
 */
internal object AutoMount {
    /**
     * Whether [unit] could climb into its own organic transport right now: it is a ground formation
     * that owns one, is not already aboard, and has not moved.
     *
     * Deliberately the same three conditions `UnitActionAvailability.mount` enables its chip on,
     * minus the ones about the player's turn — this is asked from inside a move that is already
     * being executed.
     */
    fun canRideOwnTransport(unit: GameUnit): Boolean =
        UnitPredicates.isGround(unit) &&
            unit.transport != null &&
            !unit.isMounted &&
            !unit.hasMoved

    /**
     * The hexes [unit] can reach ONLY by mounting first — its transport's range minus its own.
     *
     * Not a superset: a truck refuses terrain infantry crosses on foot, so the two ranges overlap
     * rather than nest. A hex both can reach is not in here, and is walked to on foot, because
     * arriving on foot is strictly better than arriving in a truck.
     */
    fun transportOnlyCells(
        map: GameMap,
        unit: GameUnit,
    ): List<ExtendedCell> {
        if (!canRideOwnTransport(unit)) return emptyList()
        val onFoot =
            MoveRangeCalculation
                .getMoveRange(map, unit)
                .filter { it.canMove }
                .map { it.row to it.col }
                .toSet()
        return mountedRange(map, unit).filter { it.canMove && (it.row to it.col) !in onFoot }
    }

    /** Whether reaching [target] means mounting first: out of reach on foot, in reach aboard. */
    fun requiresTransport(
        map: GameMap,
        unit: GameUnit,
        target: Cell,
    ): Boolean = transportOnlyCells(map, unit).any { it.row == target.row && it.col == target.col }

    /**
     * [unit]'s move range as if it were already aboard its transport.
     *
     * **The mounted flag is toggled for the duration of the call and restored in `finally`.** That
     * is deliberate and is the safer of the two options: every input the range depends on — movement
     * points, movement method, fuel, the terrain column, the train check — is read through
     * `unit.unitData()` and `unit.getMovesLeft()`, both of which already switch to the transport
     * when this flag is set. Threading a second "pretend" profile through
     * [MoveRangeCalculation] instead would mean two code paths that must agree about a dozen
     * modifiers, and §L.10's lesson is that two paths which must agree eventually do not.
     *
     * The call is a pure read: [MoveRangeCalculation.getMoveRange] builds a list and touches no hex,
     * no reference count and no unit but this flag.
     */
    private fun mountedRange(
        map: GameMap,
        unit: GameUnit,
    ): List<ExtendedCell> {
        val wasMounted = unit.isMounted
        return try {
            unit.isMounted = true
            MoveRangeCalculation.getMoveRange(map, unit).toList()
        } finally {
            unit.isMounted = wasMounted
        }
    }
}
