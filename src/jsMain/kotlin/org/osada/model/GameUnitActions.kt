package org.osada.model

import org.osada.LeaderType
import org.osada.UnitClass
import org.osada.rules.AirOperations
import org.osada.rules.GameRules
import org.osada.rules.ReplacementExperience
import org.osada.rules.WeatherCombatRules
import org.osada.rules.unitUsesFuel

/*
 * Movement/combat actions for [GameUnit], split out to keep its function count in bounds.
 */

/**
 * Applies [damage] and one point of suppression ([GameUnit.hits]).
 *
 * [lasting] is the attacker's OG `Shock Tactics` trait, resolved by the caller because the victim
 * cannot see who hit it. The point is recorded in [GameUnit.lastingHits] as well, which makes it
 * survive the victim's next `unitEndTurn` -- see that field for why lasting suppression is expressed
 * as a surviving point rather than as a second statistic.
 */
fun GameUnit.hit(
    damage: Int,
    lasting: Boolean = false,
) {
    strength -= damage
    hits++
    if (lasting) lastingHits++
    if (entrenchment > 0) entrenchment--
    if (strength <= 0) destroyed = true
}

/**
 * Spends one shot: ammunition, concealment, and (for a real attack rather than support fire or an
 * overrun) the formation's attack for the turn.
 *
 * Two OG leader traits land here and nowhere else:
 *
 *  - **`Fire Discipline`** halves the ammunition cost, carried across two shots by
 *    [GameUnit.halfShotPending] because ammo is an integer everywhere else in the engine.
 *  - **`Devastating Fire`** withholds `hasFired` on the first attack of the turn, which is what
 *    "may fire twice in a turn" means to every gate that reads it ([AttackEligibility.canFire],
 *    the attack-cell overlay, the ready-unit navigator). The second attack spends it normally.
 *
 * Both were advertised to the player and inert until 2026-08-18 (`docs/og-fidelity-plan.md` A.4).
 */
fun GameUnit.fire(usedOverstrength: Boolean) {
    tempSpotted = true
    spendAmmoForShot()
    shotsThisTurn++
    if (usedOverstrength) {
        hasOverstrength = true
        if (!owesAnotherShot()) hasFired = true
    }
}

/** True while `Devastating Fire` still owes this formation an attack this turn. Read AFTER
 *  [GameUnit.shotsThisTurn] has been incremented, so 1 means "that was the first shot". */
private fun GameUnit.owesAnotherShot(): Boolean =
    shotsThisTurn <= 1 && Leaders.unitHasLeader(this, LeaderType.DEVASTATING_FIRE)

/** One ammunition point, or half of one under `Fire Discipline` -- the unpaid half is carried in
 *  [GameUnit.halfShotPending] and spent by the next attack. */
private fun GameUnit.spendAmmoForShot() {
    if (!Leaders.unitHasLeader(this, LeaderType.FIRE_DISCIPLINE)) {
        ammo--
        return
    }
    if (halfShotPending) {
        halfShotPending = false
    } else {
        ammo--
        halfShotPending = true
    }
}

fun GameUnit.move(cost: Int) {
    entrenchment = 0
    // JS: `254 <= a ? a/254 + a%254 >> 0 : a`. The `>> 0` is an integer
    // truncation (no-op), NOT a shift-by-one — `shr 1` here halved the cost.
    val realCost = if (cost >= 254) (cost / 254 + cost % 254) else cost
    // OG 6.23's snow rule: movement points and FUEL stop being the same number in snow. They are
    // separated here rather than by scaling the cost, because `moveLeft` must keep counting movement
    // points -- doubling that instead would halve the unit's reach twice over, once here and again
    // through the fuel clamp in `MovementRules.getUnitMoveRange`. Behind `snow_fuel`; 1 by default.
    // OG 6.23's other fuel rule: a SORTIE costs at least a third of an aircraft's full movement,
    // however short the hop. Charged in movement points before the snow multiplier so the two rules
    // compound once rather than each scaling the other. Behind `air_fuel`; identity by default.
    val chargedPoints = AirOperations.chargedMovePoints(this, realCost)
    val fuelCost = chargedPoints * WeatherCombatRules.fuelPerMovePoint()
    if (isMounted && transport != null) {
        hasFired = true
        if (GameRules.unitUsesFuel(transport!!)) {
            transport!!.fuel -= fuelCost
        }
        moveLeft = 0
    } else {
        if (GameRules.unitUsesFuel(this) && carrier == 0) {
            fuel -= fuelCost
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

/**
 * Adds [amount] strength points and spends the formation's action for the turn.
 *
 * Ordinary replacements dilute experience -- the new intake arrives untrained, so the formation's
 * experience becomes the strength-weighted average ([ReplacementExperience]). Overstrength does not:
 * see that object for why the premium veteran action is exempt. Experience is read BEFORE strength
 * changes, because the weighting is over the strength the veterans actually represent.
 *
 * Hero experience is untouched here and lives on `HeroCampaign`'s own roster, which is what keeps a
 * commander's record separate from the formation's (roadmap P2 item 9's standing constraint).
 */
fun GameUnit.reinforce(
    amount: Int,
    overStrength: Boolean,
) {
    if (!overStrength) {
        experience = ReplacementExperience.afterReplacement(experience, strength, amount)
    }
    strength += amount
    hasMoved = true
    hasFired = true
    hasResupplied = true
    if (overStrength) hasOverstrength = true
}
