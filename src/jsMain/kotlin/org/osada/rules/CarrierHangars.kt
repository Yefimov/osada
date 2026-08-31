package org.osada.rules

import org.osada.TerrainType
import org.osada.model.Cell
import org.osada.model.EfileConfig
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.addUnit
import org.osada.model.isWorking
import org.osada.model.resupply
import org.osada.model.unitEndTurn
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey

/**
 * OG's **containers** — `ground_carrier` and `hangarCap`: a formation carried INSIDE another unit
 * rather than parked on top of it.
 *
 * > *"ground_carrier — 0 = disabled(default), set >0 to define different options summing up values
 * > (bitwise logic). **any value no zero allow to enter into empty carrier's hangar**.
 * > 1 = enables to enter/launch.
 * > 2 = enables these options: Combat Support, AirDefense and FireSupport.
 * > 4 = disables being launched (taking off) units.
 * > 8 = allow **land units** to enter naval-class carriers out of port."*
 * > — `OPENTXT_SAMPLE/equip.cfg`, `EFILE_NOKORP/equip.cfg`
 *
 * ### What a container is, and the correction of 2026-08-31
 *
 * This file shipped on 2026-08-30 reading `ground_carrier` as **the aircraft carrier's hangar** and
 * nothing else. That was wrong, and the key's own text says so: bit 8 is about *land units* and
 * *naval-class carriers*, which only means something if the thing being carried is normally a land
 * unit. `air_carrier` is the separate key for aircraft (`eqp-basekorp` and `eqp-lupo` set it, and
 * no shipped `equip.cfg` comments it).
 *
 * **The data agrees, and one class settles it.** `hangarCap` is non-zero on 916 shipped
 * `eqp-united` records spread over **13 unit classes**: 234 Battleship, 222 Carrier, 230 Cruiser
 * and Battle Cruiser, 90 Destroyer, 14 Submarine — and then 57 **Tank** (`M2 Bradley` and its
 * marks: an IFV carrying its dismounts), **54 Fortification**, eight Infantry, four Anti-Tank, one
 * Artillery, one Recon, one Level Bomber. Fifty-four fortifications with a hangar capacity are
 * bunkers with a garrison and cannot be read any other way. A rule that only let aircraft into
 * class-17 carriers threw away **694 of the 916**.
 *
 * So the shapes OG means are all one mechanic:
 *
 * ```text
 * bunker / fortified building / landing ship / IFV / carrier      (hangarCap > 0)
 * └── the formation riding inside it                              (GameUnit.hangar)
 * ```
 *
 * ### Bit 2 — the garrison that fights from inside
 *
 * *"enables these options: Combat Support, AirDefense and FireSupport"*. A contained formation
 * keeps doing the three things a unit does **for its neighbours** without leaving the container:
 * artillery below decks gives fire support, a FlaK in a bunker answers an air attack, a staff
 * element lends its experience bars. It does not launch, it is not separately visible, and it
 * cannot be targeted on its own.
 *
 * **Its range is measured from the container's hex** ([supportPosition]). That is not "shooting
 * through the ship": having no hex of its own is exactly what makes the container's hex its
 * effective position, and it is the only position in the situation.
 *
 * **Aircraft are excluded, deliberately.** Owner's ruling, 2026-08-31: a plane that has not taken
 * off does not shoot, and nothing in this key grants a hangared fighter an interception. OG keeps
 * *Air-Interception* and *Air Defense* as separate procedures (the author's own Combat page), and
 * the word in this key's sentence is the second one. If OG turns out to scramble carrier fighters
 * automatically, that is a mechanic of its own and not this bit — the manual's hangar section says
 * an aircraft must be launched first, and only then is it back on the map.
 *
 * ### What is built, and what is named rather than approximated
 *
 * Built: capacity from `hangarCap` on any class, boarding and launching gated on `ground_carrier`'s
 * own bits, bit 8's port rule for land units boarding a ship at sea, **no re-launch on the turn it
 * boarded**, bit 2's support from inside, end-of-round resupply and turn reset for the passengers,
 * the container's loss taking them with it, and the whole thing behind [RuleKey.CARRIER_HANGARS]
 * plus the efile key.
 *
 * **Not built, and stated so rather than guessed:**
 *  - **`ff_mustmatch`** — *"force units to land in carrier to match F/F"*. It reaches into Fronts
 *    and Factions, which `rules/ScenarioPurchaseList` records as resolved by OpenSuite into the
 *    per-scenario `.buy4` list rather than carried as masks, so there is nothing here to match on.
 *  - **campaign handoff.** `DEFERRED.md` records that OG unlinks passengers from containers between
 *    scenarios; OSADA's core roster carries units forward individually, so a contained formation
 *    simply arrives as its own. That matches OG's outcome without modelling the transfer.
 */
@Suppress("TooManyFunctions") // one mechanic; splitting bit 2 off would separate `supportPosition`
// from `board`, which is exactly the drift this file's KDoc warns about.
object CarrierHangars {
    /** *"1 enables to enter/launch"*. */
    private const val ENTER_LAUNCH = 1

    /** *"2 enables these options: Combat Support, AirDefense and FireSupport"*. */
    private const val SUPPORT_FROM_INSIDE = 2

    /** *"4 disables being launched (taking off) units"*. */
    private const val NO_LAUNCH = 4

    /** *"8 allow land units to enter naval-class carriers out of port"*. */
    private const val LAND_AT_SEA = 8

    private fun mode(): Int =
        if (!ActiveRuleset.flag(RuleKey.CARRIER_HANGARS, false)) 0 else EfileConfig.intKey("ground_carrier", 0)

    /** Whether containers operate at all — the player's key AND the efile's. */
    fun enabled(): Boolean = mode() != 0

    /**
     * How many formations [container] can hold, or 0 when it carries nobody.
     *
     * **No class test.** `hangarCap` is the whole gate, because the record's own capacity is what
     * OG stores and 13 classes carry it; requiring class 17 here is what hid bunkers, IFVs and
     * landing ships until 2026-08-31.
     */
    fun capacity(container: GameUnit): Int = if (!enabled()) 0 else container.unitData(true).hangarCap

    /** Whether [container] has room for one more. */
    fun hasRoom(container: GameUnit): Boolean = container.hangar.size < capacity(container)

    /**
     * Whether [passenger] may enter [container].
     *
     * *"1 enables to enter/launch"* — with `ground_carrier` set to something that omits bit 1 (as
     * `eqp-ag`'s bare `2` does), a container may hold formations placed there at deployment but may
     * not be entered during the battle. That is how a permanently sealed garrison is authored.
     */
    fun canBoard(
        passenger: GameUnit,
        container: GameUnit,
    ): Boolean =
        enabled() &&
            mode() and ENTER_LAUNCH != 0 &&
            passenger !== container &&
            !passenger.destroyed &&
            !container.destroyed &&
            passenger.player?.side == container.player?.side &&
            hasRoom(container) &&
            boardingPermitted(passenger, container)

    /**
     * Bit 8 — *"allow land units to enter naval-class carriers out of port"*.
     *
     * Read as the permission it is worded as: without the bit a land formation may still board a
     * ship, but only where a ship can be loaded, which is a port. Aircraft are unaffected — the
     * sentence names land units — and a land container (a bunker, an IFV) is unaffected too,
     * because it is not a naval-class carrier.
     */
    private fun boardingPermitted(
        passenger: GameUnit,
        container: GameUnit,
    ): Boolean =
        UnitPredicates.isAir(passenger) ||
            !UnitPredicates.isSea(container) ||
            mode() and LAND_AT_SEA != 0 ||
            container.getHex()?.isWorking(TerrainType.PORT.value) == true

    /**
     * Put [passenger] inside [container], taking it off the map.
     *
     * Its spotting and ZOC are withdrawn first: a contained formation contributes neither, which is
     * the difference between a container and a hex it happens to share.
     */
    fun board(
        map: GameMap,
        passenger: GameUnit,
        container: GameUnit,
    ): Boolean {
        if (!canBoard(passenger, container)) return false
        GameRules.setZOCRange(map, passenger, false)
        GameRules.setSpotRange(map, passenger, false)
        passenger.getHex()?.delUnit(passenger)
        map.units.remove(passenger)
        passenger.landedTurn = map.turn
        passenger.containedIn = container
        container.hangar.add(passenger)
        return true
    }

    /**
     * Whether [passenger] may leave [container] this turn.
     *
     * Two clauses, both OG's: `ground_carrier` bit 4 *"disables being launched (taking off)
     * units"* outright, and a passenger may not leave on the turn it boarded — the rule
     * `DEFERRED.md` records for OG's containers and the one that stops a container being used as a
     * free teleport within a single turn.
     */
    fun canLaunch(
        map: GameMap,
        passenger: GameUnit,
        container: GameUnit,
    ): Boolean =
        enabled() &&
            mode() and NO_LAUNCH == 0 &&
            mode() and ENTER_LAUNCH != 0 &&
            !container.destroyed &&
            passenger in container.hangar &&
            passenger.landedTurn != map.turn

    /**
     * Put [passenger] back on the map, returning whether it left.
     *
     * **[destination] defaults to the container's own hex, which is right for an aircraft and
     * usually wrong for anything else.** An aeroplane taking off from a carrier occupies the hex's
     * air slot, which the ship does not use; a battalion leaving a bunker or a landing ship cannot
     * stand where the bunker stands, because a hex holds one ground formation. So a ground
     * passenger needs somewhere to go and the caller names it.
     *
     * **Where a ground passenger may step out to is an INFERENCE**, and a named one: nothing
     * published says. The rule enforced here is the weakest one that cannot be wrong — the
     * destination is the container's own hex or one adjacent to it, and the slot it would take is
     * free. Anything narrower (terrain, ZOC, movement cost) would be invented.
     */
    fun launch(
        map: GameMap,
        passenger: GameUnit,
        container: GameUnit,
        destination: Hex? = null,
    ): Boolean {
        val hex = destination ?: container.getHex()
        val permitted =
            hex != null &&
                canLaunch(map, passenger, container) &&
                withinReachOf(container, hex) &&
                !slotTaken(hex, passenger)
        if (!permitted || hex == null) return false
        container.hangar.remove(passenger)
        passenger.containedIn = null
        hex.setUnit(passenger)
        map.addUnit(passenger)
        GameRules.setZOCRange(map, passenger, true)
        GameRules.setSpotRange(map, passenger, true)
        return true
    }

    /** Whether the slot [passenger] would occupy on [hex] is already filled. */
    private fun slotTaken(
        hex: Hex,
        passenger: GameUnit,
    ): Boolean = if (UnitPredicates.isAir(passenger)) hex.airunit != null else hex.unit != null

    /** The container's own hex, or one beside it — see [launch]'s named inference. */
    private fun withinReachOf(
        container: GameUnit,
        hex: Hex,
    ): Boolean {
        val from = container.getPos() ?: return false
        val to = hex.getPos()
        return HexGeometry.distance(from.row, from.col, to.row, to.col) <= 1
    }

    // ---- Bit 2: fighting from inside -----------------------------------------------------------

    /** Whether the efile enables `ground_carrier` bit 2 at all. */
    fun supportFromInsideEnabled(): Boolean = enabled() && mode() and SUPPORT_FROM_INSIDE != 0

    /**
     * Whether [unit] is a contained formation that bit 2 lets act for its neighbours.
     *
     * Aircraft are excluded by the owner's ruling — see this file's header. A passenger whose
     * container has been lost is excluded too, which cannot normally happen ([sinkWith] empties the
     * hangar) but must not depend on that for its answer.
     */
    fun supportsFromInside(unit: GameUnit): Boolean {
        val container = unit.containedIn ?: return false
        return supportFromInsideEnabled() &&
            !UnitPredicates.isAir(unit) &&
            !unit.destroyed &&
            !container.destroyed
    }

    /**
     * Where [unit]'s shot or support comes from: its own hex, or its container's when it is riding
     * inside one under bit 2.
     *
     * The single function every range test goes through, so the support-fire rule and the
     * combat-support rule can never disagree about where a passenger stands.
     */
    fun supportPosition(unit: GameUnit): Cell? =
        if (supportsFromInside(unit)) unit.containedIn?.getPos() else unit.getPos()

    /**
     * The passengers of every container in [units] that bit 2 lets act.
     *
     * Contained formations are not in `GameMap.units`, so a caller that scans the map for
     * candidates cannot see them; this is the one place that widens such a scan, and it returns an
     * empty list whenever the bit is off — which is every shipped efile but `eqp-ag` (2),
     * `eqp-lxf` (11), `eqp-son` (2) and the three `EFILE_CC*` that set 3.
     */
    fun supportingPassengers(units: List<GameUnit>): List<GameUnit> =
        if (!supportFromInsideEnabled()) {
            emptyList()
        } else {
            units.flatMap { container -> container.hangar.filter { supportsFromInside(it) } }
        }

    // ---- Lifecycle -----------------------------------------------------------------------------

    /**
     * A container has been destroyed: everything inside it goes down with it.
     *
     * Called from the one sweep every death passes through, so a passenger cannot outlive the thing
     * carrying it by having been inside when it was lost.
     */
    fun sinkWith(container: GameUnit) {
        container.hangar.forEach {
            it.destroyed = true
            it.containedIn = null
        }
        container.hangar.clear()
    }

    /**
     * End-of-round housekeeping for contained formations — a container is a working depot.
     *
     * They are resupplied, and their per-turn flags are reset. **The reset is not optional once
     * bit 2 exists**: a passenger that gives support fire sets `hasFired`/`hasSupportedThisTurn`,
     * and nothing else would ever clear them, so a bunker garrison would answer once in the whole
     * battle and then fall silent for reasons the player could not see.
     */
    fun endRoundForContained(
        map: GameMap,
        container: GameUnit,
        spotSide: Int,
    ) {
        if (!enabled() || container.destroyed) return
        container.hangar.forEach { passenger ->
            passenger.resupply(GameRules.getResupplyValue(map, passenger, true))
            passenger.unitEndTurn(spotSide)
        }
    }
}
