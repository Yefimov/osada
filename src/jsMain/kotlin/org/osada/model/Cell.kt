package org.osada.model

import kotlin.js.JsExport
import kotlin.js.JsName

@JsExport
@JsName("Cell")
open class Cell(open var row: Int = 0, open var col: Int = 0) {
    fun getPos(): Cell = Cell(row, col)
}

@JsExport
@JsName("ExtendedCell")
class ExtendedCell(row: Int, col: Int) : Cell(row, col) {
    var cost: Int = 0
    var cout: Int = 0
    var cin: Int = 0
    var range: Int = 0
    var canPass: Boolean = false
    var canMove: Boolean = false
    var isVisible: Boolean = false
}

class PathCell(row: Int, col: Int) : Cell(row, col) {
    var cost: Int = 1
    var prev: PathCell? = null
    var dist: Double = Double.POSITIVE_INFINITY
}

@JsExport
@JsName("MovementResults")
class MovementResults {
    var isVisible: Boolean = false
    var surpriseCell: MutableList<Cell> = mutableListOf()
    var isVictorySide: Int = -1
    var passedCells: MutableList<Cell> = mutableListOf()
    var isCapture: Boolean = false
}

@JsExport
@JsName("CombatResults")
class CombatResults {
    var defExpGained: Int = 0
    var atkExpGained: Int = 0
    var defSuppress: Int = 0
    var atkSuppress: Int = 0
    var losses: Int = 0
    var kills: Int = 0
    var defcanfire: Boolean = true
    var isRugged: Boolean = false
    var isOverrun: Boolean = false
    var defLeaderGain: Boolean = false
    var atkLeaderGain: Boolean = false
}

@JsExport
@JsName("Supply")
class Supply(
    var ammo: Int = 0,
    var fuel: Int = 0,
    var transportAmmo: Int = 0,
    var transportFuel: Int = 0
)

class MouseInfo(var x: Int = 0, var y: Int = 0, var rclick: Boolean = false)

@JsExport
@JsName("ScreenPos")
class ScreenPos(var x: Double = 0.0, var y: Double = 0.0)
