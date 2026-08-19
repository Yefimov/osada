package org.osada.rules

import org.osada.Game
import org.osada.GameHolder
import org.osada.MovMethod
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.TerrainEx
import org.osada.model.addPlayer
import org.osada.model.addUnit
import org.osada.model.allocMap
import org.osada.model.getUnits
import org.osada.model.resetEquipment
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RULESET_SCHEMA_VERSION
import org.osada.rules.ruleset.RuleKey
import org.osada.rules.ruleset.RulesetDefaults
import org.osada.rules.ruleset.RulesetResolver
import org.osada.rules.ruleset.RulesetSource
import org.osada.scenario.Scenario
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The shared gameplay random stream and the invariant that makes it safe
 * (`rules/GameRandomSource`, `docs/og-fidelity-plan.md` §H.2).
 *
 * These are the tests that stand between OSADA and a multiplayer desync, so each is written as the
 * failure it prevents rather than as the behaviour it observes:
 *
 *  * two peers seeded alike must draw alike, or they end a battle holding different units;
 *  * a save must resume its stream rather than restart it, or reloading re-rolls the future;
 *  * **a preview must never draw** — that is the invariant most easily broken by a later change,
 *    because a preview looks exactly like a resolution from inside the combat pipeline.
 */
class GameRandomSourceTest {
    private val tankEqid = 970

    @BeforeTest
    fun setup() {
        GameHolder.instance = Game().apply { scenario = Scenario(null) }
        TerrainEx.resetForTest()
        ActiveRuleset.resetForTest()
        GameRandomSource.resetForTest()
        Equipment.resetEquipment()
        Equipment.putEquipment(
            tankEqid,
            EquipmentData().apply {
                name = "T-34"
                uclass = UnitClass.TANK.value
                target = UnitType.HARD.value
                movmethod = MovMethod.TRACKED.value
                movpoints = 6
                ammo = 8
                fuel = 40
                initiative = 8
                hardatk = 10
                softatk = 10
                grounddef = 10
            },
        )
    }

    @AfterTest
    fun tearDown() {
        TerrainEx.resetForTest()
        ActiveRuleset.resetForTest()
        GameRandomSource.resetForTest()
        GameHolder.instance = null
    }

    // ---- the stream itself ---------------------------------------------------------------------

    @Test
    fun twoStreamsWithTheSameSeedProduceTheSameDraws() {
        GameRandomSource.start(12345L)
        val first = (0 until 20).map { GameRandomSource.nextInt(100) }
        GameRandomSource.start(12345L)
        val second = (0 until 20).map { GameRandomSource.nextInt(100) }
        assertEquals(first, second, "two peers seeded alike must resolve every battle alike")
    }

    @Test
    fun differentSeedsDiverge() {
        GameRandomSource.start(1L)
        val first = (0 until 20).map { GameRandomSource.nextInt(1000) }
        GameRandomSource.start(2L)
        val second = (0 until 20).map { GameRandomSource.nextInt(1000) }
        assertNotEquals(first, second, "a stream that ignored its seed would be no stream at all")
    }

    @Test
    fun theCursorCountsEveryDraw() {
        GameRandomSource.start(7L)
        assertEquals(0L, GameRandomSource.cursor())
        GameRandomSource.nextInt(6)
        GameRandomSource.nextDouble()
        GameRandomSource.nextInRange(1, 6)
        assertEquals(3L, GameRandomSource.cursor())
    }

    @Test
    fun restoringResumesTheStreamRatherThanRestartingIt() {
        GameRandomSource.start(999L)
        repeat(5) { GameRandomSource.nextInt(100) }
        val seed = GameRandomSource.seed()
        val cursor = GameRandomSource.cursor()
        val continuation = (0 until 10).map { GameRandomSource.nextInt(100) }

        GameRandomSource.restore(seed, cursor)
        assertEquals(cursor, GameRandomSource.cursor())
        assertEquals(
            continuation,
            (0 until 10).map { GameRandomSource.nextInt(100) },
            "a reloaded save must face the same future the save was looking at",
        )
    }

    @Test
    fun nextInRangeIsInclusiveAndSurvivesADegenerateRange() {
        GameRandomSource.start(3L)
        repeat(100) { assertTrue(GameRandomSource.nextInRange(2, 5) in 2..5) }
        assertEquals(4, GameRandomSource.nextInRange(4, 4), "an empty range must not throw or draw nonsense")
    }

    // ---- the invariant: only a committed exchange may draw --------------------------------------

    @Test
    fun previewingAnAttackNeverAdvancesTheCursor() {
        ruleset(RuleKey.INITIATIVE_MODEL to 1)
        val map = world()
        val attacker = place(map, 3, 3, side = 0)
        val defender = place(map, 3, 4, side = 1)
        GameRandomSource.start(42L)

        repeat(10) {
            CombatResolver.calculateAttackResults(attacker, defender, useRandom = true, units = map.getUnits().toList())
        }

        assertEquals(
            0L,
            GameRandomSource.cursor(),
            "a hover or a repaint that drew would move one peer's stream and not the other's",
        )
    }

    @Test
    fun aCommittedExchangeDrawsExactlyOncePerSide() {
        ruleset(RuleKey.INITIATIVE_MODEL to 1)
        val map = world()
        val attacker = place(map, 3, 3, side = 0)
        val defender = place(map, 3, 4, side = 1)
        GameRandomSource.start(42L)

        CombatResolver.calculateAttackResults(
            attacker,
            defender,
            useRandom = true,
            units = map.getUnits().toList(),
            committed = true,
        )

        assertEquals(2L, GameRandomSource.cursor(), "one swing per combatant, and nothing else")
    }

    @Test
    fun withTheKeyOffACommittedExchangeDrawsNothingAtAll() {
        val map = world()
        val attacker = place(map, 3, 3, side = 0)
        val defender = place(map, 3, 4, side = 1)
        GameRandomSource.start(42L)

        CombatResolver.calculateAttackResults(
            attacker,
            defender,
            useRandom = true,
            units = map.getUnits().toList(),
            committed = true,
        )

        assertEquals(
            0L,
            GameRandomSource.cursor(),
            "every shipped scenario runs with this key off; it must not even touch the stream",
        )
    }

    @Test
    fun twoPeersReplayingTheSameCommittedExchangeGetTheSameResult() {
        ruleset(RuleKey.INITIATIVE_MODEL to 1)
        val map = world()
        val attacker = place(map, 3, 3, side = 0)
        val defender = place(map, 3, 4, side = 1)

        GameRandomSource.start(2024L)
        val host =
            (0 until 8).map {
                CombatResolver
                    .calculateAttackResults(
                        attacker,
                        defender,
                        useRandom = true,
                        units = map.getUnits().toList(),
                        committed = true,
                    ).kills
            }

        GameRandomSource.start(2024L)
        val client =
            (0 until 8).map {
                CombatResolver
                    .calculateAttackResults(
                        attacker,
                        defender,
                        useRandom = true,
                        units = map.getUnits().toList(),
                        committed = true,
                    ).kills
            }

        assertEquals(host, client, "this is the desync the whole seeded stream exists to prevent")
    }

    @Test
    fun theSwingStaysInsideItsDocumentedBound() {
        ruleset(RuleKey.INITIATIVE_MODEL to 1)
        GameRandomSource.start(5L)
        repeat(200) {
            val roll = InitiativeModel.randomAdjustment(committed = true)
            assertTrue(roll in -2..2, "a swing wider than the attack-bonus cap would decide exchanges outright")
        }
    }

    // ---- harness ---------------------------------------------------------------------------

    private fun ruleset(vararg overrides: Pair<RuleKey, Int>) {
        ActiveRuleset.set(
            RulesetResolver.fromEffective(
                id = "custom-1",
                name = "Test",
                source = RulesetSource.CUSTOM,
                schemaVersion = RULESET_SCHEMA_VERSION,
                effective = RulesetDefaults.OSADA + overrides.toMap(),
            ),
        )
    }

    private val friendly =
        Player().apply {
            id = 0
            side = 0
            country = 1
        }
    private val enemy =
        Player().apply {
            id = 1
            side = 1
            country = 2
        }

    private fun world(): GameMap =
        GameMap().apply {
            rows = 8
            cols = 8
            allocMap()
            addPlayer(friendly)
            addPlayer(enemy)
            currentPlayer = friendly
            for (r in 0 until rows) {
                for (c in 0 until cols) map!![r][c].terrain = TerrainType.CLEAR.value
            }
            GameHolder.instance?.scenario?.map = this
        }

    private fun place(
        map: GameMap,
        row: Int,
        col: Int,
        side: Int,
    ): GameUnit {
        val owner = if (side == 0) friendly else enemy
        val unit =
            GameUnit(tankEqid).apply {
                id = row * 100 + col + side * 10_000
                this.owner = owner.id
                player = owner
                experience = 150
            }
        map.map!![row][col].setUnit(unit)
        map.addUnit(unit)
        return unit
    }
}
