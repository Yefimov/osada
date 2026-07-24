package org.osada.model

import org.osada.RoadType
import org.osada.TerrainType
import org.osada.rules.GameRules
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
    var owner: Int = -1
    var flag: Int = -1
    var isDeployment: Int = -1
    var victorySide: Int = -1
    var name: String = ""
    var isMoveSel: Boolean = false
    var isAttackSel: Boolean = false

    private val zoc: IntArray = IntArray(2)
    private val spotted: IntArray = IntArray(2)

    fun getPos(): Cell = Cell(rowVal, colVal)

    fun isZOC(side: Int): Boolean = side < zoc.size && zoc[side] > 0

    fun isSpotted(side: Int): Boolean = if (uiSettings.noFOW) true else side < spotted.size && spotted[side] > 0

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
            } else if (spotted[side] > 0) {
                spotted[side]--
            }
        }
    }

    fun copy(other: Hex) {
        terrain = other.terrain
        road = other.road
        rail = other.rail
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
