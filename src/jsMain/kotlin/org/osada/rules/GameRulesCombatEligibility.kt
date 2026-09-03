package org.osada.rules

import org.osada.model.Cell
import org.osada.model.GameUnit
import org.osada.model.Hex

fun GameRules.getRetreatPosition(
    map: Array<Array<Hex>>?,
    unit: GameUnit,
    rows: Int,
): Cell? = CombatPositioning.getRetreatPosition(map, unit, rows)

fun GameRules.isRuggedDefense(
    attacker: GameUnit,
    defender: GameUnit,
): Boolean = CombatResolver.isRuggedDefense(attacker, defender)

fun GameRules.isEntrenchmentIntact(
    attacker: GameUnit,
    defender: GameUnit,
    terrain: Int,
): Boolean = CombatResolver.isEntrenchmentIntact(attacker, defender, terrain)

fun GameRules.canInitiateAttack(
    attacker: GameUnit,
    defender: GameUnit,
): Boolean = AttackEligibility.canInitiateAttack(attacker, defender)

fun GameRules.canFire(
    attacker: GameUnit,
    defender: GameUnit,
): Boolean = AttackEligibility.canFire(attacker, defender)

fun GameRules.isInAttackRange(
    attacker: GameUnit,
    defender: GameUnit,
): Boolean = AttackEligibility.isInAttackRange(attacker, defender)

fun GameRules.airGroundedByWeather(unit: GameUnit): Boolean = AttackEligibility.airGroundedByWeather(unit)
