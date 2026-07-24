package org.osada.scenario

import org.osada.CURRENCY_MULTIPLIER
import org.osada.PROTOTYPE_MIN_COST
import org.osada.SCENARIO_START_PRESTIGE
import org.osada.UnitClass
import org.osada.model.Equipment
import org.osada.model.getCountryEquipmentByYearRange
import kotlin.random.Random

/** Prestige/prototype/experience economy queries for [Scenario], split out to keep its function count in bounds. */
fun Scenario.getPrototypeUnitsAvailable(country: Int): List<Int> {
    val year = date.getFullYear() + 1
    val list = Equipment.getCountryEquipmentByYearRange(year, year, country).toMutableList()
    val iter = list.iterator()
    while (iter.hasNext()) {
        val eqid = iter.next()
        val eq = Equipment.equipment[eqid] ?: continue
        val uclass = eq.uclass
        val outOfTankRange = uclass < UnitClass.TANK.value || uclass > UnitClass.ANTI_TANK.value
        val outOfArtilleryRange = uclass < UnitClass.ARTILLERY.value || uclass > UnitClass.TACTICAL_BOMBER.value
        val outOfEligibleClasses = outOfTankRange && outOfArtilleryRange
        val tooCheap = eq.cost * CURRENCY_MULTIPLIER < PROTOTYPE_MIN_COST
        if (outOfEligibleClasses || tooCheap) {
            iter.remove()
        }
    }
    return list
}

fun Scenario.getRandomPrototype(country: Int): Int {
    val available = getPrototypeUnitsAvailable(country)
    if (available.isEmpty()) return -1
    return available[(Random.nextDouble() * available.size).toInt()]
}

fun Scenario.getBalancedPrestige(side: Int): Int {
    var prestige = SCENARIO_START_PRESTIGE + unitsCostPerSide[1 - side] - unitsCostPerSide[side]
    if (prestige < SCENARIO_START_PRESTIGE) prestige = SCENARIO_START_PRESTIGE
    return prestige
}

fun Scenario.getSideUnitsAvgExp(side: Int): Int {
    val data = expPerSide[side]
    val count = data.count as Int
    val exp = data.exp as Int
    return if (count > 0) kotlin.math.round(exp.toDouble() / count).toInt() else 0
}
