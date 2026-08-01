package org.osada.model

import org.osada.UnitClass
import org.osada.entrenchRateFor
import org.osada.rules.Attachments
import org.osada.rules.GameRules
import org.osada.rules.canEntrench

private const val EXPERIENCE_ENTRENCH_DIVISOR = 100
private const val ENTRENCH_TICK_SLOPE = 9
private const val ENTRENCH_TICK_BASE = 4
private const val MAX_ENTRENCHMENT_ABOVE_TERRAIN = 5

/** Converts mutable resource pools exactly once when the mode is toggled or a new unit appears. */
internal fun GameUnit.synchronizeStalinRegime(enabled: Boolean) {
    if (stalinRegimeBoosted == enabled) return
    val multiplier = GameUnit.STALIN_REGIME_MULTIPLIER
    if (enabled) {
        moveLeft *= multiplier
        ammo *= multiplier
        fuel *= multiplier
        transport?.let {
            it.ammo *= multiplier
            it.fuel *= multiplier
        }
    } else {
        moveLeft /= multiplier
        ammo /= multiplier
        fuel /= multiplier
        transport?.let {
            it.ammo /= multiplier
            it.fuel /= multiplier
        }
    }
    stalinRegimeBoosted = enabled
}

/** Entrenchment/turn-lifecycle actions for [GameUnit], split out to keep its function count in bounds. */
fun GameUnit.entrench(): Boolean {
    val hex = this.hex
    if (!GameRules.canEntrench(this) || hex == null) return false
    // Fast Entrench (Attachments.SLOT_FAST_ENTRENCH, DEFERRED.md §1.4 Tier 2) raises the terrain's
    // own entrenchment ceiling for this unit -- both the immediate snap-to-baseline below and the
    // slow tick-up toward baseline+5 pair naturally with the existing per-efile terrain value
    // rather than needing a new mechanic.
    val terrainEnt = TerrainEx.baseEntrenchment(hex.terrain) + Attachments.bonus(this, Attachments.SLOT_FAST_ENTRENCH)
    val unitClass = unitData().uclass
    if (entrenchment >= terrainEnt) {
        var extra = entrenchment - terrainEnt
        var limit = ENTRENCH_TICK_SLOPE * extra + ENTRENCH_TICK_BASE
        entrenchTicks += experience / EXPERIENCE_ENTRENCH_DIVISOR + (terrainEnt + 1) * entrenchRateFor(unitClass)
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
        val data =
            if (stalinRegimeBoosted) {
                it.withStatMultiplier(GameUnit.STALIN_REGIME_MULTIPLIER)
            } else {
                it
            }
        ammo = data.ammo
        fuel = data.fuel
    }
    transport?.let { tr ->
        Equipment.getEquipment(tr.eqid)?.let {
            val data =
                if (stalinRegimeBoosted) {
                    it.withStatMultiplier(GameUnit.STALIN_REGIME_MULTIPLIER)
                } else {
                    it
                }
            tr.ammo = data.ammo
            tr.fuel = data.fuel
        }
    }
}

fun GameUnit.unitEndTurn(spotSide: Int) {
    entrench()
    moveLeft = unitData(useReal = true).movpoints
    hasMoved = false
    hasFired = false
    hasOverstrength = false
    hasResupplied = false
    isSurprised = false
    hasInterceptedThisTurn = false
    hits = 0
    if (unitData().uclass != UnitClass.FORTIFICATION.value) {
        val hex = this.hex
        if (hex == null || !hex.isSpotted(spotSide)) {
            tempSpotted = false
        }
    }
}

/** Retained as the leaf lifecycle hook used by [Hex.cleanup]; units currently own no external resources. */
@Suppress("UnusedReceiverParameter")
fun GameUnit.cleanup() {
    // nothing to cleanup explicitly in Kotlin
}
