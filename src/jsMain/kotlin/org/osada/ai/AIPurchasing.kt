package org.osada.ai

import org.osada.GameHolder
import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.Player
import org.osada.model.getCountryEquipmentByClass
import org.osada.model.isAiPurchasable
import org.osada.model.isPurchasable
import org.osada.rules.GameRules
import org.osada.rules.ScenarioPurchaseList
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
            val candidates = purchasableCandidates(player, classes[classIndex], country, remaining, year, month)
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
        player: Player,
        unitClass: UnitClass,
        country: Int,
        remaining: Int,
        year: Int,
        month: Int,
    ): List<Int> =
        Equipment.getCountryEquipmentByClass(unitClass, country).filter { eqid ->
            // The scenario's own Fronts/Factions list binds the AI exactly as it binds the player:
            // OpenSuite writes one section per player, and 83 corpus files customise the AI's alone
            // (`rules/ScenarioPurchaseList`).
            ScenarioPurchaseList.allows(player, eqid) && isPurchasable(eqid, remaining, year, month)
        }

    /**
     * **OG's two purchase prohibitions are read here since 2026-08-27**, and only one of them is
     * also a human rule.
     *
     * `Can't Buy` (`attr` bit 7) forbids the record to everybody, so `Player.buyUnit` refuses it
     * too. `No AI buy` (`attr` bit 8) is the AI's alone — 7,065 of the 56,970 merged records carry
     * it against 4,069 for `Can't Buy` — and it is how OG keeps scenario props and one-off
     * late-war equipment out of a computer shopping list without hiding any of it from the player.
     * Applying it to a human catalogue would delete a large part of some efiles on a bit that says
     * nothing about the player, so it must stay on this side of the line.
     */
    private fun isPurchasable(
        eqid: Int,
        remaining: Int,
        year: Int,
        month: Int,
    ): Boolean {
        if (!Equipment.isPurchasable(eqid) || !Equipment.isAiPurchasable(eqid)) return false
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
