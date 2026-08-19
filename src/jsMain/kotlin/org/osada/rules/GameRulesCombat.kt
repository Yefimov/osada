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

/**
 * [committed] marks the call that actually APPLIES the exchange, as opposed to previewing it.
 *
 * Only a committed call may draw from the shared random stream, and defaulting it to `false` is the
 * safe direction: a preview that forgets to say so is merely deterministic, while a commit that
 * forgets would leave two multiplayer peers at different stream positions
 * (`rules/GameRandomSource`). Exactly three call sites pass `true` -- `CombatApplication`,
 * `AAInterception` and `OverwatchFire`.
 */
fun GameRules.calculateAttackResults(
    attacker: GameUnit,
    defender: GameUnit,
    useRandom: Boolean,
    units: List<GameUnit> = emptyList(),
    committed: Boolean = false,
): CombatResults = CombatResolver.calculateAttackResults(attacker, defender, useRandom, units, committed = committed)

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
    map: Array<Array<Hex>>?,
    unit: GameUnit,
    rows: Int,
): Boolean = CombatPositioning.isRetreatBlockedByOwnUnitsOnly(map, unit, rows)
