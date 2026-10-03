package org.osada.multiplayer.coop

data class CoopSpending(
    val revision: Long,
    val missionFile: String,
    val commanderId: String,
    val approvedBy: String?,
    val action: String,
    val change: Int,
    val balance: Int,
)

data class CoopPurchaseProposal(
    val id: String,
    val commanderId: String,
    val equipmentId: Int,
    val transportEquipmentId: Int?,
    val quotedCost: Int,
)

/** A bounded audit of the one army treasury, plus optional proposals that reserve no money. */
class CoopEconomy {
    val spending: MutableList<CoopSpending> = mutableListOf()
    val proposals: MutableList<CoopPurchaseProposal> = mutableListOf()
    val isEmpty: Boolean get() = spending.isEmpty() && proposals.isEmpty()

    fun record(entry: CoopSpending) {
        if (spending.any { it.revision == entry.revision && it.missionFile == entry.missionFile }) return
        spending += entry
        if (spending.size > MAX_SPENDING) spending.removeAt(0)
    }

    fun propose(proposal: CoopPurchaseProposal): Boolean {
        if (proposals.size >= MAX_PROPOSALS || proposals.any { it.id == proposal.id }) return false
        proposals += proposal
        return true
    }

    companion object {
        const val MAX_SPENDING = 100
        const val MAX_PROPOSALS = 8
    }
}
