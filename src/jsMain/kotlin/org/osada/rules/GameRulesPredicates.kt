package org.osada.rules

import org.osada.model.GameUnit

// --- Unit predicates (UnitPredicates) ---

fun GameRules.canCapture(unit: GameUnit): Boolean = UnitPredicates.canCapture(unit)

fun GameRules.canEntrench(unit: GameUnit): Boolean = UnitPredicates.canEntrench(unit)

fun GameRules.isEnemy(
    a: GameUnit?,
    b: GameUnit?,
): Boolean = UnitPredicates.isEnemy(a, b)

fun GameRules.canMount(unit: GameUnit): Boolean = UnitPredicates.canMount(unit)

fun GameRules.canUnmount(unit: GameUnit): Boolean = UnitPredicates.canUnmount(unit)

fun GameRules.isTransportable(eqid: Int): Boolean = UnitPredicates.isTransportable(eqid)

fun GameRules.isAir(unit: GameUnit?): Boolean = UnitPredicates.isAir(unit)

fun GameRules.isSea(unit: GameUnit?): Boolean = UnitPredicates.isSea(unit)

fun GameRules.isGround(unit: GameUnit?): Boolean = UnitPredicates.isGround(unit)

fun GameRules.isTrain(unit: GameUnit?): Boolean = UnitPredicates.isTrain(unit)

fun GameRules.isCloseCombatTerrain(terrain: Int): Boolean = UnitPredicates.isCloseCombatTerrain(terrain)
