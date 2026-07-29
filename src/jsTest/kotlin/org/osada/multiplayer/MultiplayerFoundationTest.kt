package org.osada.multiplayer

import org.osada.multiplayer.command.GameCommandJson
import org.osada.multiplayer.command.HexCoordinate
import org.osada.multiplayer.command.MoveUnit
import org.osada.multiplayer.command.toPayloadJson
import org.osada.multiplayer.model.ContentManifest
import org.osada.multiplayer.model.ContentManifestService
import org.osada.multiplayer.model.MutablePrestigeAccount
import org.osada.multiplayer.model.PrestigeReason
import org.osada.multiplayer.sync.SeededGameRandom
import org.osada.multiplayer.sync.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MultiplayerFoundationTest {
    @Test
    fun sha256MatchesKnownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.digest("abc"),
        )
    }

    @Test
    fun seededRandomIsReproducible() {
        val first = SeededGameRandom(42)
        val second = SeededGameRandom(42)
        assertEquals(List(20) { first.nextInt(1000) }, List(20) { second.nextInt(1000) })
        assertEquals(20L, first.cursor())
    }

    @Test
    fun prestigeAccountNeverOverspends() {
        val account = MutablePrestigeAccount("side-0", 100)
        assertTrue(account.spend(60, PrestigeReason.PURCHASE).accepted)
        assertFalse(account.spend(50, PrestigeReason.UPGRADE).accepted)
        assertEquals(40, account.balance())
        account.credit(10, PrestigeReason.TURN_INCOME)
        assertEquals(50, account.balance())
    }

    @Test
    fun commandJsonRoundTrips() {
        val command =
            MoveUnit(
                unitId = 12,
                from = HexCoordinate(3, 4),
                to = HexCoordinate(4, 4),
                path = listOf(HexCoordinate(3, 4), HexCoordinate(4, 4)),
                actorPlayerId = 0,
            )
        assertEquals(command, GameCommandJson.decode(command.toPayloadJson()))
    }

    @Test
    fun manifestComparisonNamesEveryMismatch() {
        val local = ContentManifest(1, "a", "rules", "scenario", "", "equipment")
        val required = local.copy(gameBuild = "b", rulesHash = "other")
        val result = ContentManifestService().compare(local, required)
        assertFalse(result.compatible)
        assertEquals(setOf("gameBuild", "rulesHash"), result.mismatchedFields)
    }
}
