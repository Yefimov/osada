package org.osada.rules

import org.osada.model.Cell
import org.osada.model.CombatResults
import org.osada.model.GameUnit
import org.osada.model.Hex

// --- Combat (CombatResolver) ---

internal fun GameRules.attackValue(
    attackPower: Int,
    defense: Int,
    attacker: GameUnit,
    defender: GameUnit,
    useRandom: Boolean,
): Int = CombatResolver.attackValue(attackPower, defense, attacker, defender, useRandom)

fun GameRules.calculateAttackResults(
    attacker: GameUnit,
    defender: GameUnit,
    useRandom: Boolean,
    units: List<GameUnit> = emptyList(),
): CombatResults = CombatResolver.calculateAttackResults(attacker, defender, useRandom, units)

fun GameRules.calculateCombatResults(
    attacker: GameUnit,
    defender: GameUnit,
    units: List<GameUnit>,
    full: Boolean,
    useRandom: Boolean,
): CombatResults = CombatResolver.calculateCombatResults(attacker, defender, units, full, useRandom)

fun GameRules.getSupportFireUnits(
    units: List<GameUnit>,
    attacker: GameUnit,
    defender: GameUnit,
): List<GameUnit> = CombatResolver.getSupportFireUnits(units, attacker, defender)

fun GameRules.getUnitAttackCells(
    map: Array<Array<Hex>>?,
    unit: GameUnit,
    rows: Int,
    cols: Int,
): Array<Cell> = CombatPositioning.getUnitAttackCells(map, unit, rows, cols)

fun GameRules.getUnitAttackRange(unit: GameUnit): Int = AttackEligibility.getUnitAttackRange(unit)

fun GameRules.isLossOverRetreatThreshold(
    current: Int,
    original: Int,
): Boolean = CombatResolver.isLossOverRetreatThreshold(current, original)

fun GameRules.shouldDefenderRetreat(
    attacker: GameUnit,
    defender: GameUnit,
    originalStrength: Int,
): Boolean = CombatResolver.shouldDefenderRetreat(attacker, defender, originalStrength)

fun GameRules.shouldDefenderSurrender(
    defender: GameUnit,
    blockedByOwnUnitsOnly: Boolean = false,
): Boolean = CombatResolver.shouldDefenderSurrender(defender, blockedByOwnUnitsOnly)

fun GameRules.isRetreatBlockedByOwnUnitsOnly(
    map: Array<Array<org.osada.model.Hex>>?,
    unit: GameUnit,
    rows: Int,
): Boolean = CombatPositioning.isRetreatBlockedByOwnUnitsOnly(map, unit, rows)
