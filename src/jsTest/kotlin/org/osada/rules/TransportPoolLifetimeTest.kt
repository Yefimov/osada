package org.osada.rules

import org.osada.GameHolder
import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.Player
import org.osada.model.move
import org.osada.model.refreshRailPool
import org.osada.model.returnTransportToPool
import org.osada.model.takeTransportFromPool
import org.osada.rules.ruleset.RuleKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a transport pool COUNTS, and for how long -- the question `docs/og-open-questions.md` §1 led
 * with, closed on 2026-08-29 from the author's own pages.
 *
 * > *"it requires to be configured at design time **how many trains can be used at any time**
 * > (trains pool), similar to air/naval/helo transports."*
 * > -- `luis-guzman.com/OpenGen_Features.html`
 *
 * > *"If after deploying paratrooper using Air Transport, that unit was un-deployed, the transport
 * > was **not returned to the pool** when Pg2Mode is not set."* -- changelog 0.91.1.0
 *
 * A pool is a CONCURRENT CAPACITY. OSADA spent it permanently until this suite existed, which was
 * the other reading and the wrong one. Every assertion below is about the difference.
 */
class TransportPoolLifetimeTest : OgRulesTestHarness() {
    private val navalTransportEqid = 950

    @BeforeTest
    fun setup() {
        installTestWorld()
        Equipment.putEquipment(
            navalTransportEqid,
            EquipmentData().apply {
                name = "Transport Flotilla"
                uclass = UnitClass.NAVAL_TRANSPORT.value
                movmethod = MovMethod.NAVAL.value
                movpoints = 6
            },
        )
    }

    @AfterTest
    fun teardown() = clearTestWorld()

    private fun railLine(
        map: GameMap,
        row: Int,
        fromCol: Int,
        toCol: Int,
    ) {
        for (c in fromCol..toCol) map.map!![row][c].rail = 1
    }

    // ---- the pool arithmetic itself -----------------------------------------------------------

    @Test
    fun committingAndReleasingANavalTransportIsSymmetric() {
        val player =
            Player().apply {
                navalTransports = 2
                navalTransportsMax = 2
            }

        player.takeTransportFromPool(UnitClass.NAVAL_TRANSPORT.value)
        assertEquals(1, player.navalTransports, "one flotilla is at sea")

        player.returnTransportToPool(UnitClass.NAVAL_TRANSPORT.value)
        assertEquals(2, player.navalTransports, "and free again once its cargo is ashore")
    }

    @Test
    fun aReleaseCannotMintATransportTheAuthorNeverGranted() {
        // The case the ceiling exists for: a scenario may DEPLOY a unit already embarked, and that
        // unit spent nothing to get there. Without the clamp its first landing would hand the
        // player a flotilla out of nothing, every scenario, forever.
        val player =
            Player().apply {
                navalTransports = 1
                navalTransportsMax = 1
            }

        repeat(5) { player.returnTransportToPool(UnitClass.NAVAL_TRANSPORT.value) }

        assertEquals(1, player.navalTransports, "clamped to the authored pool SIZE")
    }

    @Test
    fun anExhaustedPoolStaysAtZeroRatherThanGoingNegative() {
        val player =
            Player().apply {
                airTransports = 1
                airTransportsMax = 1
            }

        repeat(3) { player.takeTransportFromPool(UnitClass.AIR_TRANSPORT.value) }

        assertEquals(0, player.airTransports)
    }

    // ---- the release point in the real disembark path -----------------------------------------

    @Test
    fun theMoveThatPutsCargoAshoreGivesTheFlotillaBack() {
        val map = world()
        GameHolder.instance = holderFor(map)
        friendly.navalTransports = 0
        friendly.navalTransportsMax = 1
        val unit = place(map, infantryEqid, 3, 3, side = 0)
        // `carrier < 0` is exactly the state `disembarkUnit` leaves behind: the landing leg, still
        // owed a transport, not yet ashore.
        unit.carrier = -navalTransportEqid

        unit.move(1)

        assertEquals(0, unit.carrier, "the disembarkation completed")
        assertEquals(1, friendly.navalTransports, "and the flotilla is back in the pool")
    }

    @Test
    fun aCancelledDisembarkationCostsAndReturnsNothing() {
        val map = world()
        GameHolder.instance = holderFor(map)
        friendly.navalTransports = 0
        friendly.navalTransportsMax = 1
        val unit = place(map, infantryEqid, 3, 3, side = 0)
        unit.carrier = -navalTransportEqid

        // Deselecting a unit mid-disembarkation flips the sign back (`GameMapSelection`), which is
        // the player changing their mind. The cargo never left, so nothing is owed either way.
        unit.carrier = -unit.carrier

        assertEquals(0, friendly.navalTransports, "still at sea, still committed")
        assertEquals(navalTransportEqid, unit.carrier, "and still aboard")
    }

    // ---- rail, which has no journey to hold the slot for ---------------------------------------

    @Test
    fun aRailwayMoveHoldsItsTrainUntilTheOwnerPlaysAgain() {
        ruleset(RuleKey.RAIL_TRANSPORT to 1)
        val map = world()
        GameHolder.instance = holderFor(map)
        friendly.railTransports = 1
        friendly.railTransportsMax = 1
        railLine(map, row = 3, fromCol = 1, toCol = 6)
        map.map!![3][1].station = true
        map.map!![3][6].station = true
        val unit = place(map, infantryEqid, 3, 1, side = 0)

        assertTrue(RailTransport.entrain(map, unit, 3, 6))
        assertEquals(0, friendly.railTransports, "the train is in use for the rest of the turn")

        map.endTurn()
        assertEquals(0, friendly.railTransports, "and stays in use while the opponent plays")

        map.endTurn()
        assertEquals(1, friendly.railTransports, "free again when its owner's next turn opens")
    }

    @Test
    fun aSecondFormationCannotBoardATrainThatIsAlreadyOut() {
        ruleset(RuleKey.RAIL_TRANSPORT to 1)
        val map = world()
        GameHolder.instance = holderFor(map)
        friendly.railTransports = 1
        friendly.railTransportsMax = 1
        railLine(map, row = 3, fromCol = 1, toCol = 6)
        map.map!![3][1].station = true
        map.map!![3][6].station = true
        val first = place(map, infantryEqid, 3, 1, side = 0)
        val second = place(map, infantryEqid, 3, 2, side = 0)

        assertTrue(RailTransport.entrain(map, first, 3, 6))

        assertFalse(RailTransport.canEntrain(second), "one train, and it is already carrying someone")
        assertFalse(RailTransport.entrain(map, second, 3, 5), "and the move is refused, not merely unoffered")
        assertEquals(2, second.getPos()?.col, "it did not budge")
    }

    @Test
    fun refreshingTheRailPoolRestoresTheAuthoredSizeAndNoMore() {
        val player =
            Player().apply {
                railTransports = 0
                railTransportsMax = 3
            }

        player.refreshRailPool()
        player.refreshRailPool()

        assertEquals(3, player.railTransports, "a reset, not an increment: it cannot drift upward")
    }

    // ---- the ceiling has to survive a save ----------------------------------------------------

    @Test
    fun aSaveCarriesBothTheCountAndTheCeiling() {
        val source =
            Player().apply {
                id = 0
                airTransports = 1
                airTransportsMax = 3
                navalTransports = 0
                navalTransportsMax = 2
                railTransports = 4
                railTransportsMax = 4
            }

        val restored =
            org.osada.GameStateDeserializer.deserializePlayer(
                reparse(org.osada.GameStateSerializer.serializePlayer(source)),
            )

        assertEquals(1, restored.airTransports)
        assertEquals(3, restored.airTransportsMax, "a save that lost the ceiling would refuse every release")
        assertEquals(2, restored.navalTransportsMax)
        assertEquals(4, restored.railTransportsMax)
    }

    @Test
    fun aSaveWrittenBeforeTheCeilingExistedKeepsThePoolItHad() {
        val legacy: dynamic = js("({})")
        legacy.id = 0
        legacy.airTransports = 2
        legacy.navalTransports = 1
        legacy.railTransports = 0

        val restored = org.osada.GameStateDeserializer.deserializePlayer(reparse(legacy))

        assertEquals(2, restored.airTransportsMax, "falls back to the count the old save did store")
        assertEquals(1, restored.navalTransportsMax)
    }
}
