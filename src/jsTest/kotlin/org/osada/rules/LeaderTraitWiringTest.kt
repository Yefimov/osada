package org.osada.rules

import org.osada.LeaderType
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
import org.osada.model.fire
import org.osada.model.hit
import org.osada.model.resetEquipment
import org.osada.model.unitEndTurn
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RULESET_SCHEMA_VERSION
import org.osada.rules.ruleset.RuleKey
import org.osada.rules.ruleset.RulesetDefaults
import org.osada.rules.ruleset.RulesetResolver
import org.osada.rules.ruleset.RulesetSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The nine leader traits that were advertised to the player and read by no rule anywhere
 * (`docs/og-fidelity-plan.md` A.4), each with the test that entry demands: *"Minimum acceptable
 * outcome per trait is one of: wired (with a test), or removed."*
 *
 * Three of the nine are covered by their own existing files rather than here — `SKILLED_INTERCEPTOR`
 * belongs with the interception suite and `FOREST_CAMOUFLAGE` with concealment — so this file owns
 * the six that live in the unit's own action and cost paths, plus the `MECHANIZED_VETERAN` exemption
 * that only exists once `heavy_move_fire` is switched on.
 */
class LeaderTraitWiringTest {
    private val gunEqid = 810
    private val mechGunEqid = 811
    private val infantryEqid = 812

    @BeforeTest
    fun setup() {
        TerrainEx.resetForTest()
        ActiveRuleset.resetForTest()
        Equipment.resetEquipment()
        Equipment.putEquipment(
            gunEqid,
            EquipmentData().apply {
                name = "76mm Divisional Gun"
                uclass = UnitClass.ARTILLERY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.TOWED.value
                movpoints = 4
                ammo = 8
                fuel = 0
                softatk = 10
                gunrange = 2
                cost = 30
            },
        )
        Equipment.putEquipment(
            mechGunEqid,
            EquipmentData().apply {
                name = "SU-76"
                uclass = UnitClass.ARTILLERY.value
                target = UnitType.HARD.value
                movmethod = MovMethod.TRACKED.value
                movpoints = 5
                ammo = 8
                fuel = 30
                softatk = 10
                gunrange = 2
                cost = 50
                // OG's `Mechanized`, attr bit 21.
                attr = 2097152
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
                softatk = 8
                cost = 20
            },
        )
    }

    @AfterTest
    fun tearDown() {
        TerrainEx.resetForTest()
        ActiveRuleset.resetForTest()
    }

    // ---- Fire Discipline: half an ammunition point per attack ---------------------------------

    @Test
    fun fireDisciplineSpendsOnePointOverTwoAttacks() {
        val unit = unit(gunEqid, LeaderType.FIRE_DISCIPLINE)
        val startingAmmo = unit.ammo

        unit.fire(false)
        assertEquals(startingAmmo - 1, unit.ammo, "the first shot debits a whole point")
        assertTrue(unit.halfShotPending, "half of it is still unspent")

        unit.fire(false)
        assertEquals(startingAmmo - 1, unit.ammo, "the second shot spends the unpaid half")
        assertFalse(unit.halfShotPending)
    }

    @Test
    fun withoutFireDisciplineEveryShotCostsAWholePoint() {
        val unit = unit(gunEqid)
        val startingAmmo = unit.ammo
        unit.fire(false)
        unit.fire(false)
        assertEquals(startingAmmo - 2, unit.ammo)
        assertFalse(unit.halfShotPending)
    }

    // ---- Devastating Fire: two attacks in a turn -----------------------------------------------

    @Test
    fun devastatingFireWithholdsTheSpentAttackOnTheFirstShot() {
        val unit = unit(gunEqid, LeaderType.DEVASTATING_FIRE)
        unit.fire(true)
        assertFalse(unit.hasFired, "the formation is still owed a second attack")
        unit.fire(true)
        assertTrue(unit.hasFired, "the second attack spends the turn")
    }

    @Test
    fun withoutDevastatingFireOneAttackSpendsTheTurn() {
        val unit = unit(gunEqid)
        unit.fire(true)
        assertTrue(unit.hasFired)
    }

    @Test
    fun theSecondAttackIsReturnedAtTheEndOfTheTurn() {
        val unit = unit(gunEqid, LeaderType.DEVASTATING_FIRE)
        unit.fire(true)
        unit.fire(true)
        unit.unitEndTurn(0)
        assertEquals(0, unit.shotsThisTurn)
        assertFalse(unit.hasFired)
    }

    // ---- Shock Tactics: suppression that survives the round wrap -------------------------------

    @Test
    fun shockTacticsSuppressionSurvivesOneClear() {
        val victim = unit(infantryEqid)
        victim.hit(1, lasting = true)
        assertEquals(1, victim.hits)

        victim.unitEndTurn(0)
        assertEquals(1, victim.hits, "the point survives the round wrap that clears everyone else's")
        assertEquals(0, victim.lastingHits, "but only once")

        victim.unitEndTurn(0)
        assertEquals(0, victim.hits)
    }

    @Test
    fun ordinarySuppressionClearsAtTheRoundWrap() {
        val victim = unit(infantryEqid)
        victim.hit(1)
        assertEquals(1, victim.hits)
        victim.unitEndTurn(0)
        assertEquals(0, victim.hits)
    }

    // ---- Influence: the re-equipping surcharge is waived ---------------------------------------

    @Test
    fun influenceRemovesTheUpgradeSurcharge() {
        val plain = unit(gunEqid)
        val influential = unit(gunEqid, LeaderType.INFLUENCE)
        val plainCost = CostCalculator.calculateUpgradeCosts(plain, mechGunEqid, -1)
        val influentialCost = CostCalculator.calculateUpgradeCosts(influential, mechGunEqid, -1)
        assertTrue(influentialCost < plainCost, "an Influence commander re-equips for less")
    }

    // ---- Alpine Training: forest and mountain cost what clear costs ----------------------------

    @Test
    fun alpineTrainingMakesAMountainCostWhatClearCosts() {
        val world = world()
        val plain = place(world, infantryEqid, 2, 2)
        val alpine = place(world, infantryEqid, 4, 2, LeaderType.ALPINE_TRAINING)
        world.map!![2][3].terrain = TerrainType.MOUNTAIN.value
        world.map!![4][3].terrain = TerrainType.MOUNTAIN.value

        val plainCost = costOfEntering(world, plain, 2, 3)
        val alpineCost = costOfEntering(world, alpine, 4, 3)
        assertTrue(plainCost > alpineCost, "the mountain is dearer without the trait")
        assertEquals(costOfEnteringClear(world, alpine), alpineCost, "and costs exactly clear with it")
    }

    // ---- Mechanized Veteran: only real once `heavy_move_fire` is on ----------------------------

    @Test
    fun withoutTheKeyAGunThatHasMovedMayStillFire() {
        val unit = unit(gunEqid)
        unit.hasMoved = true
        assertFalse(AttackEligibility.blockedByMoveThenFire(unit))
    }

    @Test
    fun withTheKeyAGunThatHasMovedMayNotFire() {
        ruleset(RuleKey.HEAVY_MOVE_FIRE to 1)
        val unit = unit(gunEqid)
        unit.hasMoved = true
        assertTrue(AttackEligibility.blockedByMoveThenFire(unit))
    }

    @Test
    fun mechanizedEquipmentIsExempt() {
        ruleset(RuleKey.HEAVY_MOVE_FIRE to 1)
        val unit = unit(mechGunEqid)
        unit.hasMoved = true
        assertFalse(AttackEligibility.blockedByMoveThenFire(unit))
    }

    @Test
    fun aMechanizedVeteranCommanderIsTheSecondSourceOfTheExemption() {
        ruleset(RuleKey.HEAVY_MOVE_FIRE to 1)
        val unit = unit(gunEqid, LeaderType.MECHANIZED_VETERAN)
        unit.hasMoved = true
        assertFalse(AttackEligibility.blockedByMoveThenFire(unit))
    }

    @Test
    fun aGunThatHasNotMovedIsNeverBlocked() {
        ruleset(RuleKey.HEAVY_MOVE_FIRE to 1)
        val unit = unit(gunEqid)
        assertFalse(AttackEligibility.blockedByMoveThenFire(unit))
    }

    @Test
    fun infantryIsNotAHeavyWeaponAndIsNeverBlocked() {
        ruleset(RuleKey.HEAVY_MOVE_FIRE to 1)
        val unit = unit(infantryEqid)
        unit.hasMoved = true
        assertFalse(AttackEligibility.blockedByMoveThenFire(unit))
    }

    // ---- harness -------------------------------------------------------------------------------

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

    private fun unit(
        eqid: Int,
        leader: LeaderType? = null,
    ): GameUnit =
        GameUnit(eqid).apply {
            id = eqid
            this.leader = leader?.value ?: -1
            player =
                Player().apply {
                    id = 0
                    side = 0
                }
        }

    private val friendly =
        Player().apply {
            id = 0
            side = 0
        }

    private fun world(): GameMap =
        GameMap()
            .apply {
                rows = 8
                cols = 8
                allocMap()
                addPlayer(friendly)
                addPlayer(
                    Player().apply {
                        id = 1
                        side = 1
                    },
                )
                currentPlayer = friendly
            }

    private fun place(
        map: GameMap,
        eqid: Int,
        row: Int,
        col: Int,
        leader: LeaderType? = null,
    ): GameUnit {
        val unit =
            GameUnit(eqid).apply {
                id = row * 100 + col
                owner = 0
                this.leader = leader?.value ?: -1
                player = friendly
            }
        map.map!![row][col].terrain = TerrainType.CLEAR.value
        map.map!![row][col].setUnit(unit)
        map.addUnit(unit)
        return unit
    }

    /** The move-range entry cost this unit is charged for [row]/[col], or -1 when unreachable. */
    private fun costOfEntering(
        map: GameMap,
        unit: GameUnit,
        row: Int,
        col: Int,
    ): Int =
        MovementRules
            .getMoveRange(map, unit)
            .firstOrNull { it.row == row && it.col == col }
            ?.cost ?: -1

    /** The cost of an adjacent CLEAR hex for the same unit, as the baseline Alpine Training claims
     *  a mountain now matches. */
    private fun costOfEnteringClear(
        map: GameMap,
        unit: GameUnit,
    ): Int {
        val pos = unit.getPos()!!
        return MovementRules
            .getMoveRange(map, unit)
            .first { cell ->
                cell.range == 1 &&
                    cell.row != pos.row &&
                    map.map!![cell.row][cell.col].terrain == TerrainType.CLEAR.value
            }.cost
    }
}
