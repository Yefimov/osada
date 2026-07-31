package org.osada.ai

import org.osada.GameHolder
import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.Player
import org.osada.model.getCountryEquipmentByClass
import org.osada.rules.GameRules
import org.osada.rules.calculateUnitCosts
import kotlin.random.Random

/**
 * [AI]'s prestige-driven unit purchase selection. Split out purely to keep [AI] within the
 * project's function-count/class-size limits -- not expected to be called from elsewhere.
 */
internal object AIPurchasing {
    /** Randomly buys units cycling through [classes] until [budget] is exhausted. */
    fun selectUnits(
        player: Player,
        budget: Int,
        classes: List<UnitClass>,
    ): PurchaseResult {
        val result = mutableListOf<Int>()
        val country = player.country + 1
        val year =
            GameHolder.instance
                ?.scenario
                ?.date
                ?.getFullYear() ?: 9999
        val month =
            (
                GameHolder.instance
                    ?.scenario
                    ?.date
                    ?.getMonth() ?: 0
                ) + 1
        var remaining = budget
        var classIndex = 0
        var exhausted = false

        while (remaining > 0) {
            if (classIndex > classes.lastIndex) {
                if (exhausted) break
                classIndex = 0
            }
            val candidates = purchasableCandidates(classes[classIndex], country, remaining, year, month)
            if (candidates.isNotEmpty()) {
                val choice = candidates[Random.nextInt(0, candidates.size)]
                remaining -= GameRules.calculateUnitCosts(choice, -1)
                result.add(choice)
                exhausted = false
            } else {
                exhausted = true
            }
            classIndex++
        }
        return PurchaseResult(remaining, result)
    }

    private fun purchasableCandidates(
        unitClass: UnitClass,
        country: Int,
        remaining: Int,
        year: Int,
        month: Int,
    ): List<Int> =
        Equipment.getCountryEquipmentByClass(unitClass, country).filter { eqid ->
            isPurchasable(eqid, remaining, year, month)
        }

    private fun isPurchasable(
        eqid: Int,
        remaining: Int,
        year: Int,
        month: Int,
    ): Boolean {
        val cost = GameRules.calculateUnitCosts(eqid, -1)
        // Equipment.equipment is dynamic (JS interop); cast once so the year/month
        // comparison below is a proper Int comparison rather than a dynamic operand
        // (Int < dynamic is an overload-ambiguity compile error, dynamic > Int isn't —
        // the concrete type has to be on the same side consistently to avoid that trap).
        val data = Equipment.equipment[eqid].unsafeCast<EquipmentData?>()
        val tooExpensiveOrCheap = cost > remaining || cost > MAX_UNIT_COST || cost < MIN_UNIT_COST
        val isDeepNaval = data?.movmethod == MovMethod.DEEP_NAVAL.value
        val notYetAvailable =
            data != null && (year < data.yearavailable || (year == data.yearavailable && month < data.monthavailable))
        return !tooExpensiveOrCheap && !isDeepNaval && !notYetAvailable
    }
}
