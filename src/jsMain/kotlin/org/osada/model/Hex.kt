package org.osada.model

import org.osada.RoadType
import org.osada.TerrainType
import org.osada.rules.GameRules
import org.osada.rules.SpottingModel
import org.osada.rules.isAir
import org.osada.uiSettings

@JsExport
@JsName("Hex")
class Hex(
    private val rowVal: Int,
    private val colVal: Int,
) {
    var unit: GameUnit? = null
    var airunit: GameUnit? = null
    var terrain: Int = TerrainType.CLEAR.value
    var road: Int = RoadType.NONE.value

    // 8-direction mask like [road], but for rail (train movement only) -- see
    // MovementRules.getMoveRange's isTrain gate. Parsed from the scenario XML's own `rail`
    // attribute (tools/og-import/xscn.py already split it out of the OG binary's road/rail
    // uint16; scn_to_xml.py/add_rails.py now emit it). Absent (0) on scenarios never re-patched
    // with rail data -- MovementRules falls back to today's behaviour for those.
    var rail: Int = RoadType.NONE.value

    /**
     * Sides that have a LAND MINEFIELD laid on this hex, as a bitmask (`1 shl side`), and the sides
     * that have detected it (`docs/og-fidelity-plan.md` C.1, OG manual 9.9).
     *
     * A minefield in Open General is a **characteristic of the hex, not a unit** -- authored by the
     * scenario designer or laid during play -- which is why it lives here beside `road` and `rail`
     * rather than as an occupant. OG's own binary agrees: it is `byte6` of the hex record, bit 1 for
     * Axis and bit 2 for Allied (`tools/og-import/SCENARIO_FORMAT_NOTES.md`), so a hex really can
     * hold one field per side.
     *
     * [minesDetected] is the half that keeps the mechanic honest. `DEFERRED.md` §1.1 forbids
     * movement damage with no visible cause, so a field a side has detected is DRAWN and made to
     * cost the rest of the move without damage, and only an undetected one may ambush. A side always
     * counts as detecting its own field.
     *
     * Both are 0 on every hex unless the `minefields` ruleset key is on, and every rule that reads
     * them checks that key first ([org.osada.rules.Minefields]).
     */
    var mines: Int = 0

    var minesDetected: Int = 0

    /**
     * The two Open General spotting layers that sit BESIDE the reference counts below, as per-side
     * bitmasks (`1 shl side`): what a side has seen so far this turn (`spotting_memory`, OG's
     * "a hex once spotted stays spotted for the active turn"), and what its own cities, ports and
     * airfields watch with no unit present (`installation_spotting`).
     *
     * They are separate fields rather than additions to [spotted] for one reason, and it is the
     * reason [clearSpotted] exists: [spotted] is a REFERENCE COUNT that is only ever correct while
     * each remove cancels an add of the same range. Memory has no matching remove, and an
     * installation has no unit to cancel it when the hex changes hands, so writing either into the
     * counter would strand it above zero and lift the fog permanently.
     *
     * [spotMemory] is cleared for a side when that side's turn ends; [installationSpotted] is
     * recomputed wholesale each turn. Both are 0 on every hex unless the matching ruleset key is on
     * (`org.osada.rules.SpottingModel`), which is what keeps [isSpotted] unchanged for everyone
     * else.
     */
    var spotMemory: Int = 0

    var installationSpotted: Int = 0

    var owner: Int = -1
    var flag: Int = -1
    var isDeployment: Int = -1
    var victorySide: Int = -1
    var name: String = ""
    var isMoveSel: Boolean = false
    var isAttackSel: Boolean = false

    /** Set alongside [isMoveSel] when a SPOTTED enemy AA unit covers this hex and the currently
     *  selected unit is an aircraft (DEFERRED.md §1.1). Never derived from hidden AA -- see
     *  `AAInterception.visibleThreatHexes`. Cleared in `delMoveSel`. */
    var isAaThreat: Boolean = false

    private val zoc: IntArray = IntArray(2)
    private val spotted: IntArray = IntArray(2)

    fun getPos(): Cell = Cell(rowVal, colVal)

    fun isZOC(side: Int): Boolean = side < zoc.size && zoc[side] > 0

    fun isSpotted(side: Int): Boolean =
        when {
            uiSettings.noFOW -> true
            side < spotted.size && spotted[side] > 0 -> true
            // The two OG layers, in that order deliberately: live vision is the common case and the
            // cheapest test, and with both keys off the fields below are 0 on every hex.
            else -> SpottingModel.revealedByLayers(this, side)
        }

    fun setZOC(
        side: Int,
        add: Boolean,
    ) {
        if (side < zoc.size) {
            if (add) {
                zoc[side]++
            } else if (zoc[side] > 0) {
                zoc[side]--
            }
        }
    }

    fun setSpotted(
        side: Int,
        add: Boolean,
    ) {
        if (side < spotted.size) {
            if (add) {
                spotted[side]++
                // The one choke point every source of live vision passes through, which is why OG's
                // turn-scoped memory is recorded here rather than in `MovementRules.setSpotRange`.
                // A no-op unless `spotting_memory` is on.
                SpottingModel.remember(this, side)
            } else if (spotted[side] > 0) {
                spotted[side]--
            }
        }
    }

    /**
     * Zeroes both sides' spotting counters, for a full recompute (see
     * `GameMap.recomputeSpotting`).
     *
     * These are reference counts, added and removed one unit at a time, so they only stay correct
     * while every remove uses the same range its add did. Anything that changes a unit's spot range
     * out from under them — Stalin Regime being toggled, or a build that changes how the range is
     * derived — leaves counters that never fall back to zero, and the fog stays permanently lifted
     * over those hexes. Recomputing from the units is the only way back.
     */
    fun clearSpotted() = spotted.fill(0)

    fun copy(other: Hex) {
        terrain = other.terrain
        road = other.road
        rail = other.rail
        mines = other.mines
        minesDetected = other.minesDetected
        spotMemory = other.spotMemory
        installationSpotted = other.installationSpotted
        owner = other.owner
        flag = other.flag
        isDeployment = other.isDeployment
        victorySide = other.victorySide
        name = other.name
        setUnit(other.unit)
        setUnit(other.airunit)
    }

    fun getUnit(airMode: Boolean = false): GameUnit? =
        if (unit != null && airunit != null) {
            if (airMode) airunit else unit
        } else {
            unit ?: airunit
        }

    fun setUnit(unit: GameUnit?) {
        if (unit != null) {
            unit.setHex(this)
            if (GameRules.isAir(unit)) {
                airunit = unit
            } else {
                this.unit = unit
            }
        }
    }

    fun delUnit(unit: GameUnit?) {
        if (unit == null) return
        unit.setHex(null)
        if (this.unit?.id == unit.id) this.unit = null
        if (this.airunit?.id == unit.id) this.airunit = null
    }

    fun cleanup() {
        unit?.cleanup()
        unit = null
        airunit?.cleanup()
        airunit = null
    }
}
