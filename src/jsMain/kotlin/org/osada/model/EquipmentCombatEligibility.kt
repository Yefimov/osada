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

/** Ground Transport is the one class where OG's own data meaningfully uses the purchasable bit --
 *  most of the class exists only to be *given* to a unit as an organic transport, not bought as a
 *  combat unit (see DEFERRED.md §1.7: 5,041 Ground Transport records, ~1% flagged purchasable).
 *  A country whose data never sets the bit on any of its own Ground Transport records is treated as
 *  not using the flag at all, so the gate falls back to permitting everything for that country --
 *  otherwise a country with no populated bit would lose the whole class to buy. */
fun Equipment.isPurchasableGroundTransport(eqid: Int): Boolean {
    val eq = equipmentMap[eqid]
    if (eq == null || eq.uclass != org.osada.UnitClass.GROUND_TRANSPORT.value) return true
    val countryTransports =
        equipmentMap.values.filter {
            it.uclass == org.osada.UnitClass.GROUND_TRANSPORT.value && it.country == eq.country
        }
    val bitUsedByCountry = countryTransports.any { it.attr.and(ATTR_MASK_PURCHASABLE) != 0 }
    return !bitUsedByCountry || eq.attr.and(ATTR_MASK_PURCHASABLE) != 0
}

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
