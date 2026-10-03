package org.osada.multiplayer.coop

import kotlin.js.json

internal object CoopEconomyJson {
    fun encode(economy: CoopEconomy): dynamic =
        json(
            "spending" to
                economy.spending
                    .map { entry ->
                        json(
                            "revision" to entry.revision.toDouble(),
                            "missionFile" to entry.missionFile,
                            "commanderId" to entry.commanderId,
                            "approvedBy" to entry.approvedBy,
                            "action" to entry.action,
                            "change" to entry.change,
                            "balance" to entry.balance,
                        )
                    }.toTypedArray(),
            "proposals" to
                economy.proposals
                    .map { proposal ->
                        json(
                            "id" to proposal.id,
                            "commanderId" to proposal.commanderId,
                            "equipmentId" to proposal.equipmentId,
                            "transportEquipmentId" to proposal.transportEquipmentId,
                            "quotedCost" to proposal.quotedCost,
                        )
                    }.toTypedArray(),
        )

    fun decode(value: dynamic): CoopEconomy {
        val result = CoopEconomy()
        if (value != null && value != undefined) {
            check((value.spending.length as Number).toInt() <= CoopEconomy.MAX_SPENDING)
            check((value.proposals.length as Number).toInt() <= CoopEconomy.MAX_PROPOSALS)
            for (index in 0 until (value.spending.length as Number).toInt()) {
                val item = value.spending[index]
                result.record(
                    CoopSpending(
                        (item.revision as Number).toLong(),
                        item.missionFile as String,
                        item.commanderId as String,
                        item.approvedBy as? String,
                        item.action as String,
                        (item.change as Number).toInt(),
                        (item.balance as Number).toInt(),
                    ),
                )
            }
            for (index in 0 until (value.proposals.length as Number).toInt()) {
                val item = value.proposals[index]
                check(
                    result.propose(
                        CoopPurchaseProposal(
                            item.id as String,
                            item.commanderId as String,
                            (item.equipmentId as Number).toInt(),
                            (item.transportEquipmentId as? Number)?.toInt(),
                            (item.quotedCost as Number).toInt(),
                        ),
                    ),
                )
            }
        }
        return result
    }
}
