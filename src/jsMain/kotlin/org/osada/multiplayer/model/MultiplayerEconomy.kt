@file:Suppress("UnusedParameter")

package org.osada.multiplayer.model

enum class PrestigeReason {
    PURCHASE,
    UPGRADE,
    REINFORCEMENT,
    RESUPPLY,
    TURN_INCOME,
    SCENARIO_REWARD,
    CAMPAIGN_TRANSITION,
    OTHER,
}

data class SpendResult(
    val accepted: Boolean,
    val balance: Int,
    val rejectionCode: String? = null,
)

interface PrestigeAccount {
    val accountId: String

    fun balance(): Int

    fun canSpend(amount: Int): Boolean

    fun spend(
        amount: Int,
        reason: PrestigeReason,
    ): SpendResult

    fun credit(
        amount: Int,
        reason: PrestigeReason,
    )
}

class MutablePrestigeAccount(
    override val accountId: String,
    openingBalance: Int,
) : PrestigeAccount {
    private var currentBalance = openingBalance

    init {
        require(openingBalance >= 0)
    }

    override fun balance(): Int = currentBalance

    override fun canSpend(amount: Int): Boolean = amount >= 0 && currentBalance >= amount

    override fun spend(
        amount: Int,
        reason: PrestigeReason,
    ): SpendResult {
        if (!canSpend(amount)) return SpendResult(false, currentBalance, "INSUFFICIENT_PRESTIGE")
        currentBalance -= amount
        return SpendResult(true, currentBalance)
    }

    override fun credit(
        amount: Int,
        reason: PrestigeReason,
    ) {
        require(amount >= 0)
        currentBalance += amount
    }
}

class PrestigeService(
    private val playerAccounts: MutableMap<Int, PrestigeAccount>,
    private val campaignSlotAccounts: MutableMap<String, PrestigeAccount> = mutableMapOf(),
) {
    fun accountForPlayer(playerId: Int): PrestigeAccount =
        requireNotNull(playerAccounts[playerId]) { "No prestige account for player $playerId" }

    fun accountForCampaignSlot(slotId: String): PrestigeAccount =
        requireNotNull(campaignSlotAccounts[slotId]) { "No prestige account for campaign slot $slotId" }

    fun spend(
        playerId: Int,
        amount: Int,
        reason: PrestigeReason,
    ): SpendResult = accountForPlayer(playerId).spend(amount, reason)

    fun credit(
        playerId: Int,
        amount: Int,
        reason: PrestigeReason,
    ) {
        accountForPlayer(playerId).credit(amount, reason)
    }
}
