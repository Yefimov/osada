package org.osada.model

// Equipment.attr bit flags (faithful port of openpanzer.js's own numeric masks -- no named
// JS constants exist upstream to borrow, values preserved exactly).
private const val ATTR_MASK_IGNORES_ENTRENCHMENT = 4
private const val ATTR_MASK_BRIDGE = 8
private const val ATTR_MASK_CANNOT_ATTACK_SOFT = 16
private const val ATTR_MASK_CANNOT_ATTACK_HARD = 32
private const val ATTR_MASK_CANNOT_ATTACK_AIR = 64
private const val ATTR_MASK_SPECIAL_ANTI_AIR = 32768
private const val ATTR_MASK_PURCHASABLE = 262144

/** Attribute-bitmask combat eligibility checks, split out of [Equipment] to keep its function count in bounds. */
fun Equipment.isBridge(eqid: Int): Boolean = (equipmentMap[eqid]?.attr?.and(ATTR_MASK_BRIDGE) ?: 0) != 0

fun Equipment.ignoresEntrenchment(eqid: Int): Boolean =
    (equipmentMap[eqid]?.attr?.and(ATTR_MASK_IGNORES_ENTRENCHMENT) ?: 0) != 0

fun Equipment.isPurchasable(eqid: Int): Boolean = (equipmentMap[eqid]?.attr?.and(ATTR_MASK_PURCHASABLE) ?: 0) != 0

/**
 * A bare Ground Transport is never bought as a unit of its own. A transport is acquired by
 * ATTACHING it to a unit at purchase time (`eqUserSel.eqtransport`), which this does not affect --
 * only the "buy a Horse as your combat unit" case, which has no defensible reading: it cannot
 * attack, cannot capture, and exists solely to carry something.
 *
 * **This replaced an attr-bit gate on 2026-07-26 (user request), and the bit it used is suspect.**
 * The previous rule permitted a transport whose `attr` had bit 262144 -- recorded in DEFERRED.md
 * §1.5/§1.7 as "purchasable" -- with a per-country fallback for countries that never set the bit.
 * That fallback does NOT fire for every country: 29 of 289 `eqp-united` countries do set the bit
 * on a transport, and country 20 (USSR) flags only 4 of its 28, refusing the other 24. The bit's
 * identification is still wrong, though: only 1,060 of 46,978 `eqp-united` records carry it (2.3%),
 * **including zero Tank and zero Anti-tank records** (class 2 = 0/3,024, class 4 = 0/3,186), and no
 * equipment table ships with no buyable tank. Do not restore an attr-based gate here until that
 * bit has been re-identified.
 */
fun Equipment.isPurchasableGroundTransport(eqid: Int): Boolean =
    equipmentMap[eqid]?.uclass != org.osada.UnitClass.GROUND_TRANSPORT.value

fun Equipment.canInitiateAttackOnUnitType(
    attackerEqid: Int,
    defenderEqid: Int,
): Boolean {
    val attacker = equipmentMap[attackerEqid]
    val defender = equipmentMap[defenderEqid]
    if (attacker == null || defender == null) return false
    return canAttackSubmarineTarget(attacker, defender) && canAttackTargetType(attacker, defender.target)
}

private fun canAttackSubmarineTarget(
    attacker: EquipmentData,
    defender: EquipmentData,
): Boolean {
    if (defender.uclass != org.osada.UnitClass.SUBMARINE.value) return true
    return attacker.uclass == org.osada.UnitClass.DESTROYER.value ||
        attacker.uclass == org.osada.UnitClass.TACTICAL_BOMBER.value
}

private fun canAttackTargetType(
    attacker: EquipmentData,
    target: Int,
): Boolean =
    when (target) {
        org.osada.UnitType.SOFT.value -> attacker.attr.and(ATTR_MASK_CANNOT_ATTACK_SOFT) == 0
        org.osada.UnitType.HARD.value -> attacker.attr.and(ATTR_MASK_CANNOT_ATTACK_HARD) == 0
        org.osada.UnitType.AIR.value -> canAttackAirTarget(attacker)
        else -> true
    }

private fun canAttackAirTarget(attacker: EquipmentData): Boolean {
    if (attacker.attr.and(ATTR_MASK_CANNOT_ATTACK_AIR) != 0) return false
    val attackerClass = attacker.uclass
    val isDedicatedAntiAir =
        attackerClass == org.osada.UnitClass.AIR_DEFENCE.value ||
            attackerClass == org.osada.UnitClass.FIGHTER.value ||
            attackerClass == org.osada.UnitClass.LEVEL_BOMBER.value ||
            attackerClass == org.osada.UnitClass.TACTICAL_BOMBER.value ||
            attackerClass == org.osada.UnitClass.BATTLESHIP.value ||
            attackerClass == org.osada.UnitClass.BATTLE_CRUISER.value ||
            attackerClass == org.osada.UnitClass.LIGHT_CRUISER.value ||
            attackerClass == org.osada.UnitClass.AIR_TRANSPORT.value
    return isDedicatedAntiAir || attacker.attr.and(ATTR_MASK_SPECIAL_ANTI_AIR) != 0
}
