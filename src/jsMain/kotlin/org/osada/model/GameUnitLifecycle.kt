package org.osada.model

import org.osada.UnitClass
import org.osada.rules.GameRules
import org.osada.rules.canEntrench
import org.osada.terrainEntrenchment
import org.osada.unitEntrenchRate

private const val EXPERIENCE_ENTRENCH_DIVISOR = 100
private const val ENTRENCH_TICK_SLOPE = 9
private const val ENTRENCH_TICK_BASE = 4
private const val MAX_ENTRENCHMENT_ABOVE_TERRAIN = 5

/** Entrenchment/turn-lifecycle actions for [GameUnit], split out to keep its function count in bounds. */
fun GameUnit.entrench(): Boolean {
    val hex = this.hex
    if (!GameRules.canEntrench(this) || hex == null) return false
    val terrainEnt = terrainEntrenchment[hex.terrain]
    val unitClass = unitData().uclass
    if (entrenchment >= terrainEnt) {
        var extra = entrenchment - terrainEnt
        var limit = ENTRENCH_TICK_SLOPE * extra + ENTRENCH_TICK_BASE
        entrenchTicks += experience / EXPERIENCE_ENTRENCH_DIVISOR + (terrainEnt + 1) * unitEntrenchRate[unitClass]
        while (entrenchTicks >= limit && entrenchment < terrainEnt + MAX_ENTRENCHMENT_ABOVE_TERRAIN) {
            entrenchTicks -= limit
            entrenchment++
            extra++
            limit = ENTRENCH_TICK_SLOPE * extra + ENTRENCH_TICK_BASE
        }
    } else {
        entrenchment = terrainEnt
        entrenchTicks = 0
    }
    return true
}

fun GameUnit.refillAmmoFuel() {
    Equipment.getEquipment(eqid)?.let {
        ammo = it.ammo
        fuel = it.fuel
    }
    transport?.let { tr ->
        Equipment.getEquipment(tr.eqid)?.let {
            tr.ammo = it.ammo
            tr.fuel = it.fuel
        }
    }
}

fun GameUnit.unitEndTurn(spotSide: Int) {
    entrench()
    moveLeft = Equipment.equipment[eqid]?.movpoints ?: 0
    hasMoved = false
    hasFired = false
    hasOverstrength = false
    hasResupplied = false
    isSurprised = false
    hits = 0
    if (unitData().uclass != UnitClass.FORTIFICATION.value) {
        val hex = this.hex
        if (hex == null || !hex.isSpotted(spotSide)) {
            tempSpotted = false
        }
    }
}

fun GameUnit.cleanup() {
    // nothing to cleanup explicitly in Kotlin
}
