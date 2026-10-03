package org.osada.multiplayer.coop

import org.osada.model.GameMap
import org.osada.model.getPlayer
import org.osada.multiplayer.command.CommandValidation
import org.osada.multiplayer.command.GameCommand
import org.osada.multiplayer.command.ProposePurchase
import org.osada.multiplayer.command.PurchaseUnit
import org.osada.multiplayer.command.RespondPurchase
import org.osada.rules.OgRulesTestHarness
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoopEconomyTest : OgRulesTestHarness() {
    private lateinit var map: GameMap
    private lateinit var state: CoopState
    private var price: Int? = 200
    private var revision = 0L
    private var purchases = 0

    @BeforeTest fun setup() {
        installTestWorld()
        map = world(prestige = 300)
        state =
            CoopState(
                listOf(
                    CoopCommander("c1", 0, "host", "Ada", CoopPortrait.generated(1), emptyList()),
                    CoopCommander("c2", 1, "guest", "Bo", CoopPortrait.generated(2), emptyList()),
                ),
                friendly.id,
            )
    }

    @AfterTest fun teardown() = clearTestWorld()

    private fun order(
        sender: String,
        command: GameCommand,
    ): CommandValidation =
        CoopCommandPipeline.execute(
            CoopCommandContext(
                map,
                state,
                command,
                sender,
                sender == "host",
                ++revision,
                validate = { CommandValidation.Accepted },
                apply = {
                    if (it is PurchaseUnit) {
                        map.getPlayer(friendly.id).prestige -= price!!
                        purchases++
                    }
                },
                quotePurchase = { price },
            ),
        )

    private fun propose(): String {
        assertEquals(CommandValidation.Accepted, order("guest", ProposePurchase(infantryEqid, null, friendly.id)))
        return state.economy.proposals
            .last()
            .id
    }

    @Test fun authenticatedProposalDoesNotSpendAndAnotherCommanderApprovesExactlyOnce() {
        val id = propose()
        assertEquals(300, map.getPlayer(friendly.id).prestige)
        assertEquals(
            "c2",
            state.economy.proposals
                .single()
                .commanderId,
        )
        assertTrue(order("guest", RespondPurchase(id, true, friendly.id)) is CommandValidation.Rejected)
        assertEquals(CommandValidation.Accepted, order("host", RespondPurchase(id, true, friendly.id)))
        assertEquals(100, map.getPlayer(friendly.id).prestige)
        assertEquals(1, purchases)
        val entry = state.economy.spending.single()
        assertEquals("c2", entry.commanderId)
        assertEquals("c1", entry.approvedBy)
        assertEquals(-200, entry.change)
        assertTrue(order("host", RespondPurchase(id, true, friendly.id)) is CommandValidation.Rejected)
        assertEquals(1, purchases)
    }

    @Test fun approvalRechecksPriceAvailabilityAndBalanceWithoutConsumingTheProposal() {
        val id = propose()
        price = 201
        assertTrue(order("host", RespondPurchase(id, true, friendly.id)) is CommandValidation.Rejected)
        price = null
        assertTrue(order("host", RespondPurchase(id, true, friendly.id)) is CommandValidation.Rejected)
        price = 200
        map.getPlayer(friendly.id).prestige = 199
        assertTrue(order("host", RespondPurchase(id, true, friendly.id)) is CommandValidation.Rejected)
        assertEquals(
            id,
            state.economy.proposals
                .single()
                .id,
        )
        assertEquals(0, purchases)
        assertTrue(state.economy.spending.isEmpty())
    }

    @Test fun competingApprovalsCannotOverspendAndWithdrawalNeverBuys() {
        val first = propose()
        val second = propose()
        assertEquals(CommandValidation.Accepted, order("host", RespondPurchase(first, true, friendly.id)))
        assertTrue(order("host", RespondPurchase(second, true, friendly.id)) is CommandValidation.Rejected)
        assertEquals(CommandValidation.Accepted, order("guest", RespondPurchase(second, false, friendly.id)))
        assertTrue(state.economy.proposals.isEmpty())
        assertEquals(100, map.getPlayer(friendly.id).prestige)
        assertEquals(1, purchases)
    }

    @Test fun pendingProposalAndBoundedAuditSurviveSnapshot() {
        val id = propose()
        repeat(105) { state.economy.record(CoopSpending(it.toLong(), "a.xml", "c2", null, "MoveUnit", 1, it)) }
        val restored = CoopStateJson.decode(CoopStateJson.encode(state))
        assertEquals(
            id,
            restored.economy.proposals
                .single()
                .id,
        )
        assertEquals(100, restored.economy.spending.size)
        assertEquals(
            5L,
            restored.economy.spending
                .first()
                .revision,
        )
        assertNull(
            restored.economy.spending
                .first()
                .approvedBy,
        )
        assertEquals(CoopStateJson.encode(state), CoopStateJson.encode(restored))
    }
}
