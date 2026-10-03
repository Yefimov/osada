@file:Suppress("ReturnCount")

package org.osada.multiplayer.coop

import org.osada.model.getPlayer
import org.osada.multiplayer.command.CommandValidation
import org.osada.multiplayer.command.CoopEconomyOrder
import org.osada.multiplayer.command.ProposePurchase
import org.osada.multiplayer.command.PurchaseUnit
import org.osada.multiplayer.command.RespondPurchase

internal object CoopEconomyPipeline {
    fun execute(
        ctx: CoopCommandContext,
        order: CoopEconomyOrder,
        who: CoopCommander?,
    ): CoopRefusal? {
        if (who == null) return CoopRefusal.UNKNOWN_COMMANDER
        return when (order) {
            is ProposePurchase -> propose(ctx, order, who)
            is RespondPurchase -> respond(ctx, order, who)
        }
    }

    private fun propose(
        ctx: CoopCommandContext,
        order: ProposePurchase,
        who: CoopCommander,
    ): CoopRefusal? {
        val cost =
            ctx.quotePurchase(PurchaseUnit(order.equipmentId, order.transportEquipmentId, ctx.coop.humanPlayerId))
                ?: return CoopRefusal.PURCHASE_NOT_AVAILABLE
        val proposal =
            CoopPurchaseProposal(
                "${ctx.coop.missionIndex}:${ctx.committingRevision}",
                who.id,
                order.equipmentId,
                order.transportEquipmentId,
                cost,
            )
        return if (ctx.coop.economy.propose(proposal)) null else CoopRefusal.PROPOSAL_LIMIT
    }

    private fun respond(
        ctx: CoopCommandContext,
        order: RespondPurchase,
        who: CoopCommander,
    ): CoopRefusal? {
        val proposal =
            ctx.coop.economy.proposals
                .firstOrNull { it.id == order.proposalId }
                ?: return CoopRefusal.PROPOSAL_NOT_FOUND
        if (!order.accept) {
            ctx.coop.economy.proposals
                .remove(proposal)
            return null
        }
        if (proposal.commanderId == who.id) return CoopRefusal.NOT_THE_ADDRESSEE
        val purchase = PurchaseUnit(proposal.equipmentId, proposal.transportEquipmentId, ctx.coop.humanPlayerId)
        val cost = ctx.quotePurchase(purchase) ?: return CoopRefusal.PURCHASE_NOT_AVAILABLE
        if (cost != proposal.quotedCost) return CoopRefusal.PRICE_CHANGED
        val player = ctx.map.getPlayer(ctx.coop.humanPlayerId)
        if (cost > player.prestige ||
            ctx.validate(purchase) is CommandValidation.Rejected
        ) {
            return CoopRefusal.PURCHASE_NOT_AVAILABLE
        }
        val before = player.prestige
        val applied = runCatching { ctx.apply(purchase) }
        if (applied.isFailure) return CoopRefusal.PURCHASE_NOT_AVAILABLE
        ctx.coop.economy.record(
            CoopSpending(
                ctx.committingRevision,
                ctx.coop.missionFile,
                proposal.commanderId,
                who.id,
                "PurchaseUnit",
                player.prestige - before,
                player.prestige,
            ),
        )
        ctx.coop.economy.proposals
            .remove(proposal)
        ctx.coop.clearDone()
        ctx.coop.ordersThisMission++
        return null
    }
}
