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
import org.osada.model.move
import org.osada.model.recomputeSpotting
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
 * The five schema-5 keys: OG's aircraft fuel model, its initiative model, its two spotting rules and
 * the scope of automatic ground resupply
 * (`docs/og-fidelity-plan.md` B.3, B.6, B.4, B.5 and A.3 item 2).
 *
 * Every test asserts the OFF case as well as the ON one. That is the substance of the promise these
 * keys were admitted on: none of the 502 shipped scenarios changes arithmetic until somebody selects
 * a profile that asks for it. [OgRuleKeysTest] makes the same promise for schema 4.
 */
@Suppress("LargeClass")
class OgFidelityKeysTest {
    private val fighterEqid = 960
    private val tankEqid = 961

    @BeforeTest
    fun setup() {
        GameHolder.instance = Game().apply { scenario = Scenario(null) }
        TerrainEx.resetForTest()
        ActiveRuleset.resetForTest()
        Equipment.resetEquipment()
        Equipment.putEquipment(
            fighterEqid,
            EquipmentData().apply {
                name = "Yak-1"
                uclass = UnitClass.FIGHTER.value
                target = UnitType.AIR.value
                movmethod = MovMethod.AIR.value
                movpoints = 9
                ammo = 6
                fuel = 40
                initiative = 9
            },
        )
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
    }

    @AfterTest
    fun tearDown() {
        TerrainEx.resetForTest()
        ActiveRuleset.resetForTest()
        GameHolder.instance = null
    }

    // ---- B.3 air_fuel: the sortie floor ------------------------------------------------------

    @Test
    fun aShortHopCostsOnlyItsOwnFuelWithTheKeyOff() {
        val map = world()
        val plane = place(map, fighterEqid, 3, 3)
        val before = plane.fuel
        plane.move(1)
        assertEquals(before - 1, plane.fuel, "one movement point, one fuel -- OSADA's own rule")
    }

    @Test
    fun withTheKeyOnTheFirstHopPaysTheWholeSortieFloor() {
        ruleset(RuleKey.AIR_FUEL to 1)
        val map = world()
        val plane = place(map, fighterEqid, 3, 3)
        val before = plane.fuel
        plane.move(1)
        // Nine movement points, so the floor is three (rounded up).
        assertEquals(before - 3, plane.fuel, "a sortie costs a third of full movement however short it is")
    }

    @Test
    fun theFloorIsPaidOncePerSortieNotOncePerMove() {
        ruleset(RuleKey.AIR_FUEL to 1)
        val map = world()
        val plane = place(map, fighterEqid, 3, 3)
        val before = plane.fuel
        plane.move(1)
        plane.move(1)
        assertEquals(
            before - 3,
            plane.fuel,
            "the second point is inside the floor already paid; two floors would double-charge phased movement",
        )
    }

    @Test
    fun beyondTheFloorEveryFurtherPointCostsItsOwnFuel() {
        ruleset(RuleKey.AIR_FUEL to 1)
        val map = world()
        val plane = place(map, fighterEqid, 3, 3)
        val before = plane.fuel
        plane.move(5)
        assertEquals(before - 5, plane.fuel, "five points is past the floor of three")
    }

    @Test
    fun groundUnitsAreUntouchedByTheAircraftRule() {
        ruleset(RuleKey.AIR_FUEL to 1)
        val map = world()
        val tank = place(map, tankEqid, 3, 3)
        val before = tank.fuel
        tank.move(1)
        assertEquals(before - 1, tank.fuel, "this key is about aircraft, not about fuel in general")
    }

    @Test
    fun anAircraftThatCannotAffordTheFloorIsPreviewedAsGrounded() {
        val map = world()
        val plane = place(map, fighterEqid, 3, 3)
        plane.fuel = 2
        assertEquals(2, MovementRules.getUnitMoveRange(plane), "two fuel, two movement points with the key off")
        ruleset(RuleKey.AIR_FUEL to 1)
        assertEquals(
            0,
            MovementRules.getUnitMoveRange(plane),
            "it cannot pay the floor, so promising it two hexes would strand it",
        )
    }

    @Test
    fun anAircraftThatCanAffordTheFloorKeepsItsFullPreview() {
        ruleset(RuleKey.AIR_FUEL to 1)
        val map = world()
        val plane = place(map, fighterEqid, 3, 3)
        plane.fuel = 5
        assertEquals(5, MovementRules.getUnitMoveRange(plane))
    }

    // ---- B.3 air_fuel: the crash rule --------------------------------------------------------

    @Test
    fun aDryAircraftAwayFromBaseSurvivesWithTheKeyOff() {
        val map = world()
        val plane = place(map, fighterEqid, 3, 3)
        plane.fuel = 0
        assertTrue(AirOperations.strandedAircraft(map, 0).isEmpty(), "off by default -- OSADA only grounds it")
    }

    @Test
    fun withTheKeyOnADryAircraftAwayFromBaseIsLost() {
        ruleset(RuleKey.AIR_FUEL to 1)
        val map = world()
        val plane = place(map, fighterEqid, 3, 3)
        plane.fuel = 0
        assertEquals(listOf(plane), AirOperations.strandedAircraft(map, 0))
    }

    @Test
    fun anAircraftOnItsOwnAirfieldIsNeverLost() {
        ruleset(RuleKey.AIR_FUEL to 1)
        val map = world()
        val plane = place(map, fighterEqid, 3, 3)
        plane.fuel = 0
        map.map!![3][3].terrain = TerrainType.AIRFIELD.value
        map.map!![3][3].flag = friendly.country
        assertTrue(
            AirOperations.strandedAircraft(map, 0).isEmpty(),
            "the crash test uses the same predicate air resupply does; the two must not disagree",
        )
    }

    @Test
    fun anAircraftBesideAnAirfieldIsAlsoSafe() {
        ruleset(RuleKey.AIR_FUEL to 1)
        val map = world()
        val plane = place(map, fighterEqid, 3, 3)
        plane.fuel = 0
        map.map!![3][4].terrain = TerrainType.AIRFIELD.value
        map.map!![3][4].flag = friendly.country
        assertTrue(AirOperations.strandedAircraft(map, 0).isEmpty(), "OG says in OR adjacent")
    }

    @Test
    fun anAircraftWithFuelLeftIsNeverLostHoweverFarFromBase() {
        ruleset(RuleKey.AIR_FUEL to 1)
        val map = world()
        place(map, fighterEqid, 3, 3)
        assertTrue(
            AirOperations.strandedAircraft(map, 0).isEmpty(),
            "the trigger is running dry, not being unbased -- otherwise authored deployments would be deleted",
        )
    }

    // ---- B.6 initiative_model ----------------------------------------------------------------

    @Test
    fun experienceDoesNotTouchInitiativeWithTheKeyOff() {
        val veteran = unit(tankEqid).apply { experience = 400 }
        assertEquals(0, InitiativeModel.experienceBonus(veteran))
    }

    @Test
    fun withTheKeyOnEachCompletedBarIsOneInitiativePoint() {
        ruleset(RuleKey.INITIATIVE_MODEL to 1)
        assertEquals(4, InitiativeModel.experienceBonus(unit(tankEqid).apply { experience = 400 }))
        assertEquals(
            1,
            InitiativeModel.experienceBonus(unit(tankEqid).apply { experience = 199 }),
            "a partial bar buys nothing, as in OG 6.7",
        )
    }

    /**
     * The half of OG 6.10 this key deliberately does NOT implement.
     *
     * Multiplayer replays every attack on both machines rather than transmitting its result
     * (`OsadaGameCommandHandlers`), and every production call site resolves through
     * `CombatResolver.attackValue`'s expected-value branch, so a die roll in the initiative
     * calculation would leave two peers holding different units. The test that matters is therefore
     * that combat is REPEATABLE with the key on, not that some swing stays inside a range.
     */
    @Test
    fun initiativeStaysRepeatableWithTheKeyOn() {
        ruleset(RuleKey.INITIATIVE_MODEL to 1)
        val veteran = unit(tankEqid).apply { experience = 350 }
        val first = InitiativeModel.experienceBonus(veteran)
        repeat(50) {
            assertEquals(
                first,
                InitiativeModel.experienceBonus(veteran),
                "combat is replayed on both peers; a random term here would desync their unit strengths",
            )
        }
    }

    // ---- B.4 spotting_memory ------------------------------------------------------------------

    @Test
    fun visibilityFollowsTheUnitWithTheKeyOff() {
        val map = world()
        val hex = map.map!![3][3]
        hex.setSpotted(0, true)
        assertTrue(hex.isSpotted(0))
        hex.setSpotted(0, false)
        assertFalse(hex.isSpotted(0), "the reference count is the whole of OSADA's fog")
    }

    @Test
    fun withTheKeyOnAHexStaysSpottedAfterTheUnitLeaves() {
        ruleset(RuleKey.SPOTTING_MEMORY to 1)
        val map = world()
        val hex = map.map!![3][3]
        hex.setSpotted(0, true)
        hex.setSpotted(0, false)
        assertTrue(hex.isSpotted(0), "OG remembers the hex for the rest of the active turn")
        assertFalse(hex.isSpotted(1), "and remembers it for one side only")
    }

    @Test
    fun theMemoryIsDroppedWhenThatSidesTurnEnds() {
        ruleset(RuleKey.SPOTTING_MEMORY to 1)
        val map = world()
        val hex = map.map!![3][3]
        hex.setSpotted(0, true)
        hex.setSpotted(0, false)
        SpottingModel.forgetTurnMemory(map, 0)
        assertFalse(hex.isSpotted(0), "clearing on the way out is what stops it leaking across the hand-over")
    }

    @Test
    fun oneSidesTurnEndingDoesNotClearTheOthersMemory() {
        ruleset(RuleKey.SPOTTING_MEMORY to 1)
        val map = world()
        val hex = map.map!![3][3]
        hex.setSpotted(0, true)
        hex.setSpotted(1, true)
        hex.setSpotted(0, false)
        hex.setSpotted(1, false)
        SpottingModel.forgetTurnMemory(map, 0)
        assertFalse(hex.isSpotted(0))
        assertTrue(hex.isSpotted(1))
    }

    // ---- B.5 installation_spotting -------------------------------------------------------------

    @Test
    fun anOwnedCitySeesNothingWithTheKeyOff() {
        val map = world()
        map.map!![3][3].terrain = TerrainType.CITY.value
        map.map!![3][3].flag = friendly.country
        map.recomputeSpotting()
        assertFalse(map.map!![3][3].isSpotted(0))
    }

    @Test
    fun withTheKeyOnAnOwnedCitySeesItselfAndItsNeighbours() {
        ruleset(RuleKey.INSTALLATION_SPOTTING to 1)
        val map = world()
        map.map!![3][3].terrain = TerrainType.CITY.value
        map.map!![3][3].flag = friendly.country
        map.recomputeSpotting()
        assertTrue(map.map!![3][3].isSpotted(0), "its own hex")
        assertTrue(map.map!![3][4].isSpotted(0), "and the ones next to it")
        assertFalse(map.map!![3][3].isSpotted(1), "but not for the enemy")
        assertFalse(map.map!![6][6].isSpotted(0), "and not the whole map")
    }

    @Test
    fun portsAndAirfieldsWatchTooButOpenGroundDoesNot() {
        ruleset(RuleKey.INSTALLATION_SPOTTING to 1)
        val map = world()
        map.map!![1][1].terrain = TerrainType.PORT.value
        map.map!![1][1].flag = friendly.country
        map.map!![5][5].terrain = TerrainType.AIRFIELD.value
        map.map!![5][5].flag = friendly.country
        map.map!![7][7].terrain = TerrainType.CLEAR.value
        map.map!![7][7].flag = friendly.country
        map.recomputeSpotting()
        assertTrue(map.map!![1][1].isSpotted(0))
        assertTrue(map.map!![5][5].isSpotted(0))
        assertFalse(map.map!![7][7].isSpotted(0), "a flagged clear hex is not an installation")
    }

    @Test
    fun anUnownedCitySeesForNobody() {
        ruleset(RuleKey.INSTALLATION_SPOTTING to 1)
        val map = world()
        map.map!![3][3].terrain = TerrainType.CITY.value
        map.map!![3][3].flag = -1
        map.recomputeSpotting()
        assertFalse(map.map!![3][3].isSpotted(0))
        assertFalse(map.map!![3][3].isSpotted(1))
    }

    @Test
    fun turningTheKeyOffTakesEffectOnTheNextRecompute() {
        ruleset(RuleKey.INSTALLATION_SPOTTING to 1)
        val map = world()
        map.map!![3][3].terrain = TerrainType.CITY.value
        map.map!![3][3].flag = friendly.country
        map.recomputeSpotting()
        assertTrue(map.map!![3][3].isSpotted(0))

        ActiveRuleset.resetForTest()
        map.recomputeSpotting()
        assertFalse(map.map!![3][3].isSpotted(0), "a wholesale rebuild is what makes the layer reversible")
    }

    // ---- A.3 item 2: ground_auto_supply --------------------------------------------------------

    @Test
    fun anIdleFormationInTheFieldGetsNothingWithTheKeyOff() {
        val map = world()
        val tank = place(map, tankEqid, 3, 3)
        tank.ammo = 1
        tank.fuel = 1
        val supply = SupplyRules.getResupplyValue(map, tank, full = true)
        assertEquals(0, supply.ammo, "OSADA requires a city hex, and that restriction is a balance decision")
        assertEquals(0, supply.fuel)
    }

    @Test
    fun withTheKeyOnAnIdleFormationResuppliesWhereItStands() {
        ruleset(RuleKey.GROUND_AUTO_SUPPLY to 1)
        val map = world()
        val tank = place(map, tankEqid, 3, 3)
        tank.ammo = 1
        tank.fuel = 1
        val supply = SupplyRules.getResupplyValue(map, tank, full = true)
        assertTrue(supply.ammo > 0, "OG names no terrain condition for an idle ground unit")
        assertTrue(supply.fuel > 0)
    }

    @Test
    fun aFormationInACityResuppliesUnderEitherSetting() {
        val map = world()
        val tank = place(map, tankEqid, 3, 3)
        map.map!![3][3].terrain = TerrainType.CITY.value
        tank.ammo = 1
        assertTrue(SupplyRules.getResupplyValue(map, tank, full = true).ammo > 0)
        ruleset(RuleKey.GROUND_AUTO_SUPPLY to 1)
        assertTrue(SupplyRules.getResupplyValue(map, tank, full = true).ammo > 0)
    }

    @Test
    fun anAdjacentEnemyStillBlocksResupplyWithTheKeyOn() {
        ruleset(RuleKey.GROUND_AUTO_SUPPLY to 1)
        val map = world()
        val tank = place(map, tankEqid, 3, 3)
        tank.ammo = 1
        place(map, tankEqid, 3, 4, side = 1)
        assertEquals(
            0,
            SupplyRules.getResupplyValue(map, tank, full = true).ammo,
            "a formation under fire is idle in neither game, so the key does not reach this condition",
        )
    }

    // ---- harness -----------------------------------------------------------------------------

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

    private fun unit(eqid: Int): GameUnit =
        GameUnit(eqid).apply {
            id = eqid
            player = friendly
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
        eqid: Int,
        row: Int,
        col: Int,
        side: Int = 0,
    ): GameUnit {
        val owner = if (side == 0) friendly else enemy
        val unit =
            GameUnit(eqid).apply {
                id = row * 100 + col + side * 10_000
                this.owner = owner.id
                player = owner
            }
        map.map!![row][col].setUnit(unit)
        map.addUnit(unit)
        return unit
    }
}
