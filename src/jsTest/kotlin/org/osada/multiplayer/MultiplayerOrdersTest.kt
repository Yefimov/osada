package org.osada.multiplayer

import org.osada.model.GameUnit
import org.osada.multiplayer.command.CommandValidation
import org.osada.multiplayer.command.DisbandUnit
import org.osada.multiplayer.command.DisembarkUnit
import org.osada.multiplayer.command.DismissLeader
import org.osada.multiplayer.command.EmbarkUnit
import org.osada.multiplayer.command.GameCommandJson
import org.osada.multiplayer.command.GreenReinforceUnit
import org.osada.multiplayer.command.HexCoordinate
import org.osada.multiplayer.command.OsadaGameCommandApplier
import org.osada.multiplayer.command.OsadaGameCommandValidator
import org.osada.multiplayer.command.PurchaseAttachment
import org.osada.multiplayer.command.RailMove
import org.osada.multiplayer.command.UpgradeUnit
import org.osada.multiplayer.command.toPayloadJson
import org.osada.rules.OgRulesTestHarness
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Every player order in a room travels to the host as a command (2026-09-27). These pin the two
 * halves that make that work: each new command survives the wire, and the host can find a unit that
 * is still in the reserve tray -- a lookup that only searched the map refused every deployment,
 * reserve upgrade and reserve sale as "Unknown unit".
 */
class MultiplayerOrdersTest : OgRulesTestHarness() {
    @BeforeTest
    fun setup() {
        installTestWorld()
    }

    @AfterTest
    fun teardown() {
        clearTestWorld()
    }

    @Test
    fun theNewOrdersSurviveTheWire() {
        listOf(
            EmbarkUnit(7, 0),
            DisembarkUnit(7, 0),
            RailMove(7, HexCoordinate(3, 4), 0),
            PurchaseAttachment(7, listOf(2, 5), 0),
            GreenReinforceUnit(7, 0),
            DismissLeader(7, 1),
        ).forEach { command -> assertEquals(command, GameCommandJson.decode(command.toPayloadJson())) }
    }

    private fun reserveUnit(): GameUnit =
        GameUnit(infantryEqid)
            .apply {
                id = 9001
                owner = friendly.id
                player = friendly
                isDeployed = false
            }.also { friendly.setCoreUnitList(friendly.getCoreUnitList() + it) }

    @Test
    fun theHostFindsAReserveUnitForAnUpgrade() {
        val map = world(prestige = 10_000)
        val game = holderFor(map)
        val unit = reserveUnit()

        val verdict =
            OsadaGameCommandValidator { game }.validate(UpgradeUnit(unit.id, sapperEqid, null, friendly.id), null)

        assertEquals(CommandValidation.Accepted, verdict)
    }

    @Test
    fun sellingAReserveUnitRemovesItFromTheTray() {
        val map = world(prestige = 0)
        val game = holderFor(map)
        val unit = reserveUnit()

        OsadaGameCommandApplier(gameProvider = { game }).apply(DisbandUnit(unit.id, friendly.id))

        assertFalse(friendly.getCoreUnitList().any { it.id == unit.id }, "the formation left the tray")
    }
}
