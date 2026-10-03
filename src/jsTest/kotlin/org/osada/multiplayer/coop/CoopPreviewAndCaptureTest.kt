package org.osada.multiplayer.coop

import org.osada.GameHolder
import org.osada.GameStateSerializer
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.model.Equipment
import org.osada.model.GameMap
import org.osada.model.executeAttack
import org.osada.model.restoreCoreUnitList
import org.osada.model.selectUnit
import org.osada.rules.AttackEligibility
import org.osada.rules.CombatPositioning
import org.osada.rules.CombatResolver
import org.osada.rules.GameRandomSource
import org.osada.rules.HexGeometry
import org.osada.rules.OgRulesTestHarness
import kotlin.js.JSON
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoopPreviewAndCaptureTest : OgRulesTestHarness() {
    private lateinit var map: GameMap
    private lateinit var coop: CoopState

    @BeforeTest
    fun setup() {
        installTestWorld()
        map = world()
        GameHolder.instance = holderFor(map)
        coop =
            CoopState(
                listOf(CoopCommander("c1", 0, "host", "Ada", CoopPortrait.generated(3), listOf("coop.marksman"))),
                humanPlayerId = 0,
            )
        CoopRuntime.install(coop)
        GameRandomSource.resetForTest()
    }

    @AfterTest
    fun teardown() {
        CoopRuntime.clear()
        clearTestWorld()
    }

    @Test
    fun campaignReserveRestoresItsDirectionAsWellAsItsIdentity() {
        val original =
            place(map, infantryEqid, 2, 2, 0).apply {
                isDeployed = false
                facing = 0
            }
        val saved = JSON.parse<dynamic>(JSON.stringify(GameStateSerializer.serializeCoreUnit(original)))
        map.map!![2][2].delUnit(original)
        map.units.remove(original)
        map.restoreCoreUnitList(friendly, listOf(saved))
        val restored = friendly.getCoreUnitList().single()
        assertEquals(original.id, restored.id)
        assertEquals(original.facing, restored.facing)
        assertFalse(restored.isDeployed)
    }

    @Test
    fun selectionOffersTheFirstOrdersExtendedRangeWithoutClaimingOtherUnits() {
        val gun = place(map, gunEqid, 3, 3, 0)
        val other = place(map, gunEqid, 1, 1, 0)
        val baseRange = AttackEligibility.getUnitAttackRange(gun)
        val targetCell =
            HexGeometry
                .getRing(3, 3, baseRange + 1, map.rows, map.cols, false)
                .first { HexGeometry.distance(3, 3, it.row, it.col) == baseRange + 1 }
        val enemy = place(map, infantryEqid, targetCell.row, targetCell.col, 1)
        map.map!![targetCell.row][targetCell.col].setSpotted(friendly.side, true)
        assertFalse(
            CombatPositioning.getUnitAttackCells(map.map, gun, map.rows, map.cols).any {
                it.row == targetCell.row && it.col == targetCell.col
            },
        )
        var forecastKills = 0
        CoopRuntime.preview("host", gun) {
            assertEquals(baseRange + 1, AttackEligibility.getUnitAttackRange(gun))
            assertEquals(
                baseRange,
                AttackEligibility.getUnitAttackRange(other),
                "support units get no prospective bonus",
            )
            map.selectUnit(gun)
            assertTrue(map.map!![targetCell.row][targetCell.col].isAttackSel)
            forecastKills = CombatResolver.calculateAttackResults(gun, enemy, true).kills
        }
        assertTrue(coop.assignments.isEmpty(), "selection alone never assigns")
        CoopRuntime.acting("c1", gun.id) {
            assertEquals(forecastKills, CombatResolver.calculateAttackResults(gun, enemy, true).kills)
        }
        assertNull(CoopRuntime.commanderIdFor(gun), "the preview lens is gone")
    }

    @Test
    fun nestedPreviewAndFailureRestoreTheOuterActingContext() {
        val first = place(map, gunEqid, 3, 3, 0)
        val second = place(map, gunEqid, 1, 1, 0)
        CoopRuntime.acting("c1", first.id) {
            runCatching { CoopRuntime.preview("host", second) { error("failed preview") } }
            assertEquals("c1", CoopRuntime.commanderIdFor(first))
            assertNull(CoopRuntime.commanderIdFor(second))
        }
        assertNull(CoopRuntime.commanderIdFor(first))
    }

    @Test
    fun assignedAndUnassignedFriendlySurrendersCountAsLossesWithoutEndurance() {
        listOf("c1", null).forEach { commander ->
            // A tank's fire is not rugged infantry-v-infantry combat. Find a surviving strength
            // beyond the retreat threshold, then deny every escape hex with ocean.
            map = world()
            GameHolder.instance = holderFor(map)
            val adjacent = HexGeometry.getAdjacent(4, 4).first()
            Equipment.getEquipment(riflemanEqid)?.apply {
                uclass = UnitClass.TANK.value
                softatk = 20
                gunrange = 1
            }
            val enemy = place(map, riflemanEqid, adjacent.row, adjacent.col, 1)
            val defender = place(map, infantryEqid, 4, 4, 0)
            if (commander != null) coop.claim(defender.id, commander) else coop.assignments.clear()
            val strength =
                (10 downTo 2).firstOrNull { s ->
                    defender.strength = s
                    val kills = CombatResolver.calculateAttackResults(enemy, defender, true).kills
                    kills < s && CombatResolver.isLossOverRetreatThreshold(s - kills, s)
                }
            defender.strength = assertNotNull(strength, "an attacker strength must leave a retreating survivor")
            HexGeometry.getAdjacent(4, 4).forEach {
                if (it.row != adjacent.row ||
                    it.col != adjacent.col
                ) {
                    map.map!![it.row][it.col].terrain = TerrainType.OCEAN.value
                }
            }
            val before = coop.ledger.counters(commander).copy()
            val teamBefore = coop.ledger.teamOwnLosses
            val outcome = map.executeAttack(enemy, defender)
            assertNotNull(outcome.surrender, "a surrounded friendly survivor must surrender")
            val counters = coop.ledger.counters(commander)
            assertEquals(before.unitsLost + 1, counters.unitsLost)
            assertEquals(teamBefore + 1, coop.ledger.teamOwnLosses)
            assertEquals(before.strengthLost + strength, counters.strengthLost)
            assertEquals(before.strengthCaptured + strength - outcome.result.kills, counters.strengthCaptured)
            assertEquals(before.endurance, counters.endurance)
            val restored = CoopStateJson.decode(CoopStateJson.encode(coop))
            assertEquals(counters.strengthCaptured, restored.ledger.counters(commander).strengthCaptured)
            assertEquals(counters.unitsLost, restored.ledger.counters(commander).unitsLost)
        }
    }
}
