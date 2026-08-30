package org.osada.rules

import org.osada.UnitClass
import org.osada.model.EfileConfig
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.addUnit
import org.osada.model.resupply
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey

/**
 * OG's **carrier hangars** — aircraft carried INSIDE a ship rather than parked on top of it.
 *
 * `og-fidelity-plan.md` §Y.3 listed this as *"a UI-and-model project"* and the last of its four
 * unbuilt mechanics. It was never blocked on evidence: `EFILE_NOKORP/equip.cfg` documents the
 * switch, and `hangarCap` has been imported since §Q.
 *
 * > *"ground_carrier — 0 disabled (default), set >0 to define different options summing up values
 * > (bitwise logic). **any value no zero allow to enter into empty carrier's hangar**. 1 enables to
 * > enter/launch. 2 enables these options: Combat Support, AirDefense and FireSupport. 4 disables
 * > being launched (taking off) units."*
 *
 * **916 shipped records carry a hangar**, with capacities of 1 to 6.
 *
 * ### Containment, and what it changes about a carried aircraft
 *
 * OSADA has always parked a carrier-based aircraft in [Hex.airunit] — stacked over the ship, on the
 * map, visible, spottable and occupying the hex's one air slot. A hangar is different: the aircraft
 * is **off the map entirely** while aboard. It cannot be seen, cannot be shot at, does not block
 * the air slot, and moves with the ship. That is the whole point of a hangar and the reason
 * `hangarCap` exists rather than "carriers may stack aircraft".
 *
 * The two coexist deliberately. `rules/CarrierDeploy` puts an aircraft ON a carrier at deployment
 * and is untouched by this; boarding the hangar is a separate, in-battle action.
 *
 * ### What is built, and what is named rather than approximated
 *
 * Built: capacity from `hangarCap`, boarding and launching gated on `ground_carrier`'s own bits,
 * **no relaunch on the turn it landed**, the hangar going down with its ship, and the whole thing
 * behind [RuleKey.CARRIER_HANGARS] plus the efile key.
 *
 * **Not built, and stated so rather than guessed:**
 *  - `ground_carrier` bit 2, *"enables Combat Support, AirDefense and FireSupport"* — a contained
 *    aircraft firing out of the hangar. OSADA's support-fire path resolves from map positions, and
 *    an off-map unit has none; wiring it would mean inventing where the shot comes from.
 *  - **campaign handoff.** `DEFERRED.md` records that OG unlinks planes from carriers between
 *    scenarios; OSADA's core roster carries units forward individually, so a contained aircraft
 *    simply arrives as its own formation. That matches OG's outcome without modelling the transfer.
 */
object CarrierHangars {
    /** *"any value no zero allow to enter into empty carrier's hangar"*. */
    private const val ENTER_LAUNCH = 1

    /** *"4 disables being launched (taking off) units"*. */
    private const val NO_LAUNCH = 4

    private fun mode(): Int =
        if (!ActiveRuleset.flag(RuleKey.CARRIER_HANGARS, false)) 0 else EfileConfig.intKey("ground_carrier", 0)

    /** Whether hangars operate at all — the player's key AND the efile's. */
    fun enabled(): Boolean = mode() != 0

    /** How many aircraft [carrier] can hold, or 0 when it is not a carrier at all. */
    fun capacity(carrier: GameUnit): Int =
        if (!enabled() || carrier.unitData(true).uclass != UnitClass.CARRIER.value) {
            0
        } else {
            carrier.unitData(true).hangarCap
        }

    /** Whether [carrier] has room for one more. */
    fun hasRoom(carrier: GameUnit): Boolean = carrier.hangar.size < capacity(carrier)

    /**
     * Whether [aircraft] may enter [carrier]'s hangar.
     *
     * *"1 enables to enter/launch"* — with `ground_carrier` set to something that omits bit 1, a
     * hangar may hold aircraft placed there at deployment but may not be entered during the battle.
     */
    fun canBoard(
        aircraft: GameUnit,
        carrier: GameUnit,
    ): Boolean =
        enabled() &&
            mode() and ENTER_LAUNCH != 0 &&
            UnitPredicates.isAir(aircraft) &&
            !aircraft.destroyed &&
            !carrier.destroyed &&
            aircraft.player?.side == carrier.player?.side &&
            hasRoom(carrier)

    /**
     * Put [aircraft] inside [carrier], taking it off the map.
     *
     * Its spotting and ZOC are withdrawn first: a contained aircraft contributes neither, which is
     * the difference between a hangar and a parking space.
     */
    fun board(
        map: GameMap,
        aircraft: GameUnit,
        carrier: GameUnit,
    ): Boolean {
        if (!canBoard(aircraft, carrier)) return false
        GameRules.setZOCRange(map, aircraft, false)
        GameRules.setSpotRange(map, aircraft, false)
        aircraft.getHex()?.delUnit(aircraft)
        map.units.remove(aircraft)
        aircraft.landedTurn = map.turn
        carrier.hangar.add(aircraft)
        return true
    }

    /**
     * Whether [aircraft] may leave [carrier] this turn.
     *
     * Two clauses, both OG's: `ground_carrier` bit 2 (`4`) *"disables being launched (taking off)
     * units"* outright, and an aircraft may not take off again on the turn it landed — the rule
     * `DEFERRED.md` records for OG's hangars and the one that stops a carrier being used as a
     * free teleport within a single turn.
     */
    fun canLaunch(
        map: GameMap,
        aircraft: GameUnit,
        carrier: GameUnit,
    ): Boolean =
        enabled() &&
            mode() and NO_LAUNCH == 0 &&
            mode() and ENTER_LAUNCH != 0 &&
            !carrier.destroyed &&
            aircraft in carrier.hangar &&
            aircraft.landedTurn != map.turn

    /** Put [aircraft] back on the map over [carrier]'s own hex, returning whether it flew off. */
    fun launch(
        map: GameMap,
        aircraft: GameUnit,
        carrier: GameUnit,
    ): Boolean {
        val hex = carrier.getHex()
        if (!canLaunch(map, aircraft, carrier) || hex == null || hex.airunit != null) return false
        carrier.hangar.remove(aircraft)
        hex.setUnit(aircraft)
        map.addUnit(aircraft)
        GameRules.setZOCRange(map, aircraft, true)
        GameRules.setSpotRange(map, aircraft, true)
        return true
    }

    /**
     * A carrier has been destroyed: everything in its hangar goes down with it.
     *
     * Called from the one sweep every death passes through, so an aircraft cannot outlive its ship
     * by having been contained when it sank.
     */
    fun sinkWith(carrier: GameUnit) {
        carrier.hangar.forEach { it.destroyed = true }
        carrier.hangar.clear()
    }

    /** End-of-turn resupply for contained aircraft — a hangar is a working airfield. */
    fun resupplyContained(
        map: GameMap,
        carrier: GameUnit,
    ) {
        if (!enabled() || carrier.destroyed) return
        carrier.hangar.forEach { aircraft ->
            aircraft.resupply(GameRules.getResupplyValue(map, aircraft, true))
        }
    }
}
