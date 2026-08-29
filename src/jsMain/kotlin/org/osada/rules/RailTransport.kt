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
 * ### Fidelity status — read this before calling the railway "done"
 *
 * **The pool SIZE is exact; the mechanic around it is not.** `railtrans` is imported byte-for-byte
 * from the scenario binary and 212 shipped player records now carry it. Everything else on this
 * page is a compression of a mechanic OG builds differently, and the gap is stated in full two
 * sections down. Air and naval transport, by contrast, now reproduce OG's documented behaviour
 * closely — a pool point is committed on embarkation and returned when the cargo comes ashore.
 *
 * ### Where the pool comes from
 *
 * `Player.railTransports`, scenario attribute `railtrans`, imported from player record `+21`.
 * That offset was a "strong candidate we must not build on" when this object was written; it was
 * confirmed on 2026-08-28 against OpenSuite's own REPORT logs, which print
 * `Avail non organic transports: Air [1] Naval [2] Rail [0] Helo [0]` per player
 * (`docs/og-open-questions.md` §Y.1). The rule still reads only the attribute.
 *
 * ### The model: a strategic move where OG has a container — a compression, and a named one
 *
 * **OG's railway is containment.** The author's Features page asks the scenario for *"a train
 * transport able to move on rails, available in the Train Transport Pool"*, the changelog speaks of
 * *"units embarked in RTP"* that *"will look for the closer free station to disembark"* (0.92.0.0)
 * and of *"unit transported on trains ... movement on minefields"*, and trains can carry hangars.
 * A unit boards a real train, the train drives, and it can be caught on the way.
 *
 * OSADA does not do that, and cannot cheaply: OG's `RT` class is folded into Ground Transport here,
 * so there is no train record to become and picking one per efile would be an invention. A rail
 * move instead **relocates the formation between two boarding points along connected track** and
 * costs it the movement it would have spent getting there.
 *
 * The consequences are real and are listed below and in `docs/og-open-questions.md` §1: there is no
 * entrained state, so nothing can be attacked or mined in transit, and the pool slot has no journey
 * to be held for — `model/TransportPools` explains why it is held for the turn instead.
 *
 * ### What is deliberately not built, and it is all one open question
 *
 * `docs/og-fidelity-plan.md` §AA.7 records these; each is unknown rather than skipped:
 *
 *  - **weights.** OG matches rail, unit and transport weights — *"units and transports must match
 *    the weight in order to embark"*. The per-record values ARE in the binary (`equip.xeqp` @43
 *    `NtpW`, @44 `RtpW`, @45 `HtpW`, beside the @40/@42 pair OSADA does import), and
 *    `xeqp_to_csv.py` already dumps them; `csv_to_eqp.py` reads none of the three. So this is a
 *    deployable gap rather than an unknown one, unlike the three below it. What the numbers MEAN
 *    (a capacity, a class, a bitmask) is not published, which is why it was not deployed with the
 *    rail permission on 2026-08-29.
 *  - **cut track and hostile territory.** [reachableFrom] refuses to path THROUGH a hex an enemy
 *    occupies, which is the minimum any reading requires. Whether an enemy ZOC beside the line, or
 *    ownership of the ground it runs over, also cuts it is not documented.
 *  - **combat and minefields while entrained.** There is no entrained state to be attacked in: the
 *    move is atomic. OG has one — *"unit transported on trains was not handling properly movement
 *    on minefields"* is a fixed bug, so a train in OG can be mined — and OSADA cannot reproduce it
 *    without the containment model above.
 *  - **acting after detraining.** The move spends the formation's MOVEMENT and leaves its shot
 *    alone, which is what any other full move does in OSADA. OG says only that the unit must not
 *    have acted BEFORE.
 */
object RailTransport {
    /** One boarding, one train, for the rest of the turn — `Player.refreshRailPool` frees it when
     *  the owner plays again, because OG's pool counts trains usable *"at any time"* rather than
     *  journeys allowed (`model/TransportPools`). */
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
        val usable = UnitPredicates.isGround(unit) && !unit.destroyed && !unit.isMounted && permitsRail(unit)
        val idle = !unit.hasMoved && !unit.hasFired
        val hasPool = (unit.player?.railTransports ?: 0) >= POOL_COST
        return usable && idle && hasPool && isBoardingPoint(unit.getHex(), unit)
    }

    /**
     * OG's per-record rail permission — *"Units be configured to use Train Transport"*.
     *
     * Wired 2026-08-29. Before that any ground formation could board, which handed the railway to
     * the 5-6% of infantry and the **73% of anti-aircraft** and **69% of fortifications** that OG
     * refuses it to ([EquipmentData.railTransportable] has the distribution). A record with no data
     * is permitted, not refused.
     *
     * Read through [GameUnit.unitData] with `useReal = true`, the same way `needsNoStation` reads
     * `attrEx`: the permission belongs to the formation itself, never to whatever is carrying it.
     */
    private fun permitsRail(unit: GameUnit): Boolean = unit.unitData(true).canUseRailTransport()

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
        unit.player?.railTransports = ((unit.player?.railTransports ?: 0) - POOL_COST).coerceAtLeast(0)
        // The journey IS the formation's move for the turn. Its shot is left alone, which is what
        // any other full move does -- see this object's header for why that is the open half.
        unit.moveLeft = 0
        unit.hasMoved = true
        return true
    }
}
