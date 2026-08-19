package org.osada.rules

import org.osada.Game
import org.osada.GameHolder
import org.osada.LeaderType
import org.osada.MovMethod
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.WeatherCondition
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.TerrainEx
import org.osada.model.addPlayer
import org.osada.model.addUnit
import org.osada.model.allocMap
import org.osada.model.move
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The three quoted Open General rules OSADA did not execute at all before 2026-08-18, each behind its
 * own schema-4 key and each defaulting OFF (`docs/og-fidelity-plan.md` B.2, B.8 and `UnitConcealment`).
 *
 * Every test here asserts the OFF case too. That is the substance of the promise these keys were
 * admitted on: nothing in the 502 shipped scenarios changes arithmetic until somebody selects a
 * profile that asks for it.
 */
class OgRuleKeysTest {
    private val tankEqid = 950
    private val infantryEqid = 951

    @BeforeTest
    fun setup() {
        // Both halves of this file read the LIVE game through `GameHolder` -- the weather rules do
        // (as `WeatherCombatRulesTest` already does), and so does `UnitConcealment`, which has to
        // find the mover's neighbours from a renderer that holds no map reference.
        GameHolder.instance = Game().apply { scenario = Scenario(null) }
        TerrainEx.resetForTest()
        ActiveRuleset.resetForTest()
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
            },
        )
        Equipment.putEquipment(
            infantryEqid,
            EquipmentData().apply {
                name = "Rifle Division"
                uclass = UnitClass.INFANTRY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.LEG.value
                movpoints = 4
                ammo = 8
                fuel = 0
                initiative = 4
            },
        )
    }

    @AfterTest
    fun tearDown() {
        TerrainEx.resetForTest()
        ActiveRuleset.resetForTest()
        GameHolder.instance = null
    }

    // ---- B.2 snow_fuel --------------------------------------------------------------------------

    @Test
    fun fairWeatherAlwaysSpendsOneFuelPerMovementPoint() {
        assertEquals(1, WeatherCombatRules.fuelPerMovePoint())
        ruleset(RuleKey.SNOW_FUEL to 1)
        assertEquals(1, WeatherCombatRules.fuelPerMovePoint(), "the rule is about snow, not about weather")
    }

    @Test
    fun snowDoublesFuelOnlyWhenTheKeyIsOn() {
        snowing()
        assertEquals(1, WeatherCombatRules.fuelPerMovePoint(), "off by default -- no shipped scenario changes")
        ruleset(RuleKey.SNOW_FUEL to 1)
        assertEquals(2, WeatherCombatRules.fuelPerMovePoint())
    }

    @Test
    fun theFuelSpentByAMoveFollowsTheRate() {
        snowing()
        ruleset(RuleKey.SNOW_FUEL to 1)
        val unit = unit(tankEqid)
        val startingFuel = unit.fuel
        unit.move(3)
        assertEquals(startingFuel - 6, unit.fuel, "three movement points cost six fuel in snow")
        assertEquals(3, unit.moveLeft, "but only three MOVEMENT points -- the two must not be conflated")
    }

    @Test
    fun theMoveRangePreviewIsClampedByTheDoubledRate() {
        snowing()
        val unit = unit(tankEqid)
        unit.fuel = 4
        assertEquals(4, MovementRules.getUnitMoveRange(unit), "four fuel, four movement points")
        ruleset(RuleKey.SNOW_FUEL to 1)
        assertEquals(
            2,
            MovementRules.getUnitMoveRange(unit),
            "a preview that promised four would strand the tank halfway",
        )
    }

    // ---- B.8 dry_unit_penalties -------------------------------------------------------------------

    @Test
    fun anEmptyUnitKeepsItsDefenceWithTheKeyOff() {
        val stats = AttackCalculation.CombatStats(defenderDefense = 12)
        val empty = unit(infantryEqid).apply { ammo = 0 }
        UnitConditionPenalties.applyDryUnitPenalties(stats, unit(tankEqid), empty)
        assertEquals(12, stats.defenderDefense)
    }

    @Test
    fun withTheKeyOnAnEmptyUnitDefendsAtHalf() {
        ruleset(RuleKey.DRY_UNIT_PENALTIES to 1)
        val stats = AttackCalculation.CombatStats(defenderDefense = 12)
        val empty = unit(infantryEqid).apply { ammo = 0 }
        UnitConditionPenalties.applyDryUnitPenalties(stats, unit(tankEqid), empty)
        assertEquals(6, stats.defenderDefense)
    }

    @Test
    fun aSuppliedUnitIsNeverPenalised() {
        ruleset(RuleKey.DRY_UNIT_PENALTIES to 1)
        val stats = AttackCalculation.CombatStats(defenderDefense = 12)
        UnitConditionPenalties.applyDryUnitPenalties(stats, unit(tankEqid), unit(infantryEqid))
        assertEquals(12, stats.defenderDefense)
    }

    @Test
    fun aDryTankLosesHalfItsInitiative() {
        ruleset(RuleKey.DRY_UNIT_PENALTIES to 1)
        val dry = unit(tankEqid).apply { fuel = 0 }
        assertEquals(4, UnitConditionPenalties.dryInitiative(dry, 8))
        assertEquals(8, UnitConditionPenalties.dryInitiative(unit(tankEqid), 8))
    }

    // ---- Forest Camouflage ------------------------------------------------------------------------

    @Test
    fun aCamouflagedFormationInAForestIsNotVisibleToASpottingEnemy() {
        val map = world()
        val hidden = place(map, infantryEqid, 3, 3, LeaderType.FOREST_CAMOUFLAGE, side = 1)
        map.map!![3][3].terrain = TerrainType.FOREST.value
        hidden.tempSpotted = true

        assertTrue(UnitConcealment.isConcealed(hidden, 0))
        assertFalse(UnitConcealment.isVisibleTo(hidden, 0))
        assertTrue(UnitConcealment.isVisibleTo(hidden, 1), "its own side always sees it")
    }

    @Test
    fun movingAdjacentBreaksTheCamouflage() {
        val map = world()
        val hidden = place(map, infantryEqid, 3, 3, LeaderType.FOREST_CAMOUFLAGE, side = 1)
        map.map!![3][3].terrain = TerrainType.FOREST.value
        hidden.tempSpotted = true
        assertTrue(UnitConcealment.isConcealed(hidden, 0))

        place(map, infantryEqid, 3, 4, side = 0)
        assertFalse(UnitConcealment.isConcealed(hidden, 0), "an enemy is now adjacent")
    }

    @Test
    fun theTraitDoesNothingOutsideAForest() {
        val map = world()
        val exposed = place(map, infantryEqid, 3, 3, LeaderType.FOREST_CAMOUFLAGE, side = 1)
        map.map!![3][3].terrain = TerrainType.CLEAR.value
        exposed.tempSpotted = true
        assertFalse(UnitConcealment.isConcealed(exposed, 0))
    }

    @Test
    fun aFormationWithoutTheTraitIsNeverConcealed() {
        val map = world()
        val plain = place(map, infantryEqid, 3, 3, side = 1)
        map.map!![3][3].terrain = TerrainType.FOREST.value
        plain.tempSpotted = true
        assertFalse(UnitConcealment.isConcealed(plain, 0))
    }

    // ---- harness -----------------------------------------------------------------------------------

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

    private fun snowing() {
        GameHolder.instance?.scenario?.atmosferic = WeatherCondition.SNOW.value
    }

    private val friendly =
        Player().apply {
            id = 0
            side = 0
        }
    private val enemy =
        Player().apply {
            id = 1
            side = 1
        }

    private fun unit(eqid: Int): GameUnit =
        GameUnit(eqid).apply {
            id = eqid
            player = friendly
        }

    /** Builds the map AND publishes it as the live scenario's, which is where [UnitConcealment]
     *  looks for a concealed unit's neighbours. */
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
        eqid: Int,
        row: Int,
        col: Int,
        leader: LeaderType? = null,
        side: Int = 0,
    ): GameUnit {
        val owner = if (side == 0) friendly else enemy
        val unit =
            GameUnit(eqid).apply {
                id = row * 100 + col + side * 10_000
                this.owner = owner.id
                this.leader = leader?.value ?: -1
                player = owner
            }
        map.map!![row][col].setUnit(unit)
        map.addUnit(unit)
        return unit
    }
}
