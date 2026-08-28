package org.osada.rules

import org.osada.RoadType
import org.osada.model.ATTR_EX_MASK_NO_NEED_STATION
import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey

/**
 * OG's **railway transport**, and with it the `No Need Station` equipment special.
 *
 * > *"The player must have some rail transport available"*, a station is required at **both** ends
 * > by default, and the unit *"must not already have acted"* before embarking. `No Need Station`
 * > lifts the station requirement at **both** ends — embarkation and disembarkation alike.
 *
 * ### Why this could be built without confirming player record `+21`
 *
 * `docs/og-fidelity-plan.md` §Y.2 blocks the rail POOL on a controlled OpenSuite diff: `+21` is a
 * strong candidate and §Q.2 is the standing reason not to build on a strong candidate. That blocks
 * the **importer**, not the rule. OSADA already carries two per-player transport pools of exactly
 * this shape — `airtrans` and `navaltrans`, read from the scenario XML — so rail is a third one
 * (`Player.railTransports`, attribute `railtrans`), and **this object never reads a `.xscn` byte**.
 *
 * The consequence is that the mechanic is **inert on all 502 shipped scenarios**, because none of
 * them authors `railtrans`. When somebody confirms `+21`, `add_rail_pools.py` fills the attribute
 * and every scenario gains the mechanic with no change here. If `+21` turns out to be something
 * else, nothing built on it has to be unwound — which is the whole point of §Q.2's rule.
 *
 * ### The model: a strategic move, not a container
 *
 * OSADA's air and naval transports work by CONTAINMENT — the unit takes the transport's stats in
 * [GameUnit.carrier] and moves as it. Rail deliberately does not: OG has a rail-transport class
 * (`RT`) that OSADA folds into Ground Transport, so there is no record to become, and picking one
 * per efile would be an invention. Instead a rail move **relocates the formation between two
 * boarding points along connected track**, which is what the pool counts — *trains usable at one
 * time* — and costs it the movement it would have spent getting there.
 *
 * ### What is deliberately not built, and it is all one open question
 *
 * `docs/og-fidelity-plan.md` §AA.7 records these; each is unknown rather than skipped:
 *
 *  - **weights.** OG matches rail, unit and transport weights. The rail's own capacity value is not
 *    published and OSADA has no field for it, so no weight is checked.
 *  - **cut track and hostile territory.** [reachableFrom] refuses to path THROUGH a hex an enemy
 *    occupies, which is the minimum any reading requires. Whether an enemy ZOC beside the line, or
 *    ownership of the ground it runs over, also cuts it is not documented.
 *  - **combat while entrained.** There is no entrained state to be attacked in: the move is atomic.
 *  - **acting after detraining.** The move spends the formation's MOVEMENT and leaves its shot
 *    alone, which is what any other full move does in OSADA. OG says only that the unit must not
 *    have acted BEFORE.
 */
object RailTransport {
    /** One boarding, one train. Consumed permanently, exactly as `airtrans`/`navaltrans` are. */
    private const val POOL_COST = 1

    /** Whether [unit] may board or leave the rail anywhere on it, with no station. */
    fun needsNoStation(unit: GameUnit): Boolean = unit.unitData(true).attrEx and ATTR_EX_MASK_NO_NEED_STATION != 0

    /** Whether [hex] is a place [unit] may get on or off: track, plus a station unless the
     *  formation carries `No Need Station`. */
    fun isBoardingPoint(
        hex: Hex?,
        unit: GameUnit,
    ): Boolean =
        hex != null &&
            hex.rail > RoadType.NONE.value &&
            (hex.station || needsNoStation(unit))

    /**
     * Whether [unit] could use the railway at all right now.
     *
     * Ground formations only — OG's railway carries neither aircraft nor ships — and the formation
     * must be idle, hold a pool point, and be standing somewhere it may board.
     */
    fun canEntrain(unit: GameUnit): Boolean {
        if (!ActiveRuleset.flag(RuleKey.RAIL_TRANSPORT, false)) return false
        val usable = UnitPredicates.isGround(unit) && !unit.destroyed && !unit.isMounted
        val idle = !unit.hasMoved && !unit.hasFired
        val hasPool = (unit.player?.railTransports ?: 0) >= POOL_COST
        return usable && idle && hasPool && isBoardingPoint(unit.getHex(), unit)
    }

    /**
     * Every hex [unit] could be railed to: a boarding point it can reach along connected track,
     * currently free, and not the one it is standing on.
     */
    fun destinations(
        map: GameMap,
        unit: GameUnit,
    ): List<Cell> {
        val from = if (canEntrain(unit)) unit.getPos() else null
        return if (from == null) {
            emptyList()
        } else {
            reachableFrom(map, unit, from).filter { cell ->
                val hex = map.map?.getOrNull(cell.row)?.getOrNull(cell.col)
                hex != null && hex.unit == null && isBoardingPoint(hex, unit)
            }
        }
    }

    /**
     * Breadth-first walk of connected track from [from], excluding the start.
     *
     * A hex an ENEMY occupies is not entered and not crossed — a train does not run through the
     * other side's position under any reading. A friendly unit on the line blocks nothing: the
     * train passes it.
     */
    private fun reachableFrom(
        map: GameMap,
        unit: GameUnit,
        from: Cell,
    ): List<Cell> {
        val side = unit.player?.side
        val seen = mutableSetOf(from.row * map.cols + from.col)
        val queue = ArrayDeque(listOf(from))
        val found = mutableListOf<Cell>()
        while (queue.isNotEmpty()) {
            val cell = queue.removeFirst()
            HexGeometry.getAdjacent(cell.row, cell.col).forEach { next ->
                val key = next.row * map.cols + next.col
                val hex = map.map?.getOrNull(next.row)?.getOrNull(next.col)
                val onTrack = hex != null && hex.rail > RoadType.NONE.value
                val enemyHeld = hex?.unit != null && hex.unit?.player?.side != side
                if (key !in seen && onTrack && !enemyHeld) {
                    seen += key
                    found += next
                    queue.addLast(next)
                }
            }
        }
        return found
    }

    /**
     * Rails [unit] to ([row], [col]), spending one point of its player's pool.
     *
     * Returns false and changes nothing unless the destination is one [destinations] offered, so a
     * stale overlay cannot move a formation somewhere the rule refuses.
     */
    fun entrain(
        map: GameMap,
        unit: GameUnit,
        row: Int,
        col: Int,
    ): Boolean {
        val offered = destinations(map, unit).any { it.row == row && it.col == col }
        val target = if (offered) map.map?.getOrNull(row)?.getOrNull(col) else null
        if (target == null) return false
        GameRules.setZOCRange(map, unit, false)
        GameRules.setSpotRange(map, unit, false)
        unit.getHex()?.delUnit(unit)
        target.setUnit(unit)
        GameRules.setZOCRange(map, unit, true)
        GameRules.setSpotRange(map, unit, true)
        unit.player?.railTransports = (unit.player?.railTransports ?: 0) - POOL_COST
        // The journey IS the formation's move for the turn. Its shot is left alone, which is what
        // any other full move does -- see this object's header for why that is the open half.
        unit.moveLeft = 0
        unit.hasMoved = true
        return true
    }
}
