package org.osada.model

import org.osada.LeaderType
import org.osada.UnitClass
import org.osada.rules.GameRules
import org.osada.rules.unitUsesFuel

/** Movement/combat actions for [GameUnit], split out to keep its function count in bounds. */
fun GameUnit.hit(damage: Int) {
    strength -= damage
    hits++
    if (entrenchment > 0) entrenchment--
    if (strength <= 0) destroyed = true
}

fun GameUnit.fire(usedOverstrength: Boolean) {
    tempSpotted = true
    ammo--
    if (usedOverstrength) {
        hasFired = true
        hasOverstrength = true
    }
}

fun GameUnit.move(cost: Int) {
    entrenchment = 0
    // JS: `254 <= a ? a/254 + a%254 >> 0 : a`. The `>> 0` is an integer
    // truncation (no-op), NOT a shift-by-one — `shr 1` here halved the cost.
    val realCost = if (cost >= 254) (cost / 254 + cost % 254) else cost
    if (isMounted && transport != null) {
        hasFired = true
        if (GameRules.unitUsesFuel(transport!!)) {
            transport!!.fuel -= realCost
        }
        moveLeft = 0
    } else {
        if (GameRules.unitUsesFuel(this) && carrier == 0) {
            fuel -= realCost
        }
        moveLeft -= realCost
    }
    // Recon class gets phased movement innately; a Reconnaissance Movement leader (§1.6) extends
    // the same allowance to any other formation, without touching the class's own free grant.
    val phasedMovement =
        unitData().uclass == UnitClass.RECON.value || Leaders.unitHasLeader(this, LeaderType.RECON_MOVEMENT)
    if (!phasedMovement || moveLeft <= 0) {
        hasMoved = true
        hasOverstrength = true
    }
    if (carrier < 0) carrier = 0
}

fun GameUnit.resupply(supply: Supply) {
    ammo += supply.ammo
    fuel += supply.fuel
    transport?.let {
        it.ammo += supply.transportAmmo
        it.fuel += supply.transportFuel
    }
    hasMoved = true
    hasFired = true
    hasResupplied = true
}

fun GameUnit.reinforce(
    amount: Int,
    overStrength: Boolean,
) {
    strength += amount
    hasMoved = true
    hasFired = true
    hasResupplied = true
    if (overStrength) hasOverstrength = true
}
