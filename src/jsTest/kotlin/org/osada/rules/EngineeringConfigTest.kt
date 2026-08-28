package org.osada.rules

import org.osada.EmbarkType
import org.osada.GameHolder
import org.osada.MovMethod
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.model.EfileConfig
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.beginEngineering
import org.osada.model.embarkUnit
import org.osada.model.hasPurchaseAnchor
import org.osada.model.isInDeployZone
import org.osada.model.setTransport
import org.osada.rules.ruleset.RuleKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Build and Repair's four loose ends, closed 2026-08-27 (`docs/og-fidelity-plan.md` §V):
 * the efile's own `build_cost` / `build_turn` / `repair_turn`, work that advanced with nobody
 * doing it, `blow_any_terrain`'s real reach, and a wrecked facility that went on working.
 *
 * The column order these tests pin is OG's own, from `EFILE_NOKORP/equip.cfg`'s comments —
 * **Bridge, Airport, Port, Fort, Station** — so if a future reading of that file disagrees, these
 * are the assertions that should fail first.
 */
class EngineeringConfigTest : OgRulesTestHarness() {
    private val airliftableEqid = 1010
    private val flyingTruckEqid = 1011
    private val groundedTruckEqid = 1012
    private val tenderEqid = 1013
    private val plainShipEqid = 1014

    /** OG's `Air support`, `attr` bit 13. */
    private val attrAirSupport = 8192

    /** OG's `Air Transportable`, `attr` bit 14. */
    private val attrAirTransportable = 16384

    @BeforeTest
    fun setup() {
        installTestWorld()
        Equipment.putEquipment(
            airliftableEqid,
            EquipmentData().apply {
                name = "Parachute Battalion"
                uclass = UnitClass.INFANTRY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.LEG.value
                movpoints = 4
                ammo = 6
                softatk = 7
                grounddef = 5
                country = friendly.country + 1
                // Airborne: the formation itself may be flown in.
                embark = EmbarkType.AIRBORNE.value
            },
        )
        Equipment.putEquipment(flyingTruckEqid, truck("Airborne Jeep", attrAirTransportable))
        Equipment.putEquipment(tenderEqid, ship("Seaplane Tender", attrAirSupport))
        Equipment.putEquipment(plainShipEqid, ship("Destroyer", bits = 0))
        Equipment.putEquipment(groundedTruckEqid, truck("Heavy Lorry", bits = 0))
        // `GameUnit.embark` picks the first record of the carrier class for the player's country,
        // so the world needs one of each to embark ONTO.
        Equipment.putEquipment(
            airliftableEqid + 100,
            EquipmentData().apply {
                name = "Air Transport"
                uclass = UnitClass.AIR_TRANSPORT.value
                movmethod = MovMethod.AIR.value
                movpoints = 10
                country = friendly.country + 1
            },
        )
        Equipment.putEquipment(
            airliftableEqid + 101,
            EquipmentData().apply {
                name = "Naval Transport"
                uclass = UnitClass.NAVAL_TRANSPORT.value
                movmethod = MovMethod.NAVAL.value
                movpoints = 8
                country = friendly.country + 1
            },
        )
        indexCarriersForEmbark()
    }

    /**
     * `GameUnit.embark` resolves the carrier through `Equipment.getCountryEquipmentByClass`, which
     * reads the per-country CLASS INDEX rather than the record map — and `putEquipment` populates
     * only the latter. The live game builds this index while parsing each country file; a test that
     * wants a real embark has to stand it up itself.
     */
    private fun indexCarriersForEmbark() {
        val country = friendly.country + 1
        val classIndex = js("{}")
        classIndex[UnitClass.AIR_TRANSPORT.value.toString()] = arrayOf(airliftableEqid + 100)
        classIndex[UnitClass.NAVAL_TRANSPORT.value.toString()] = arrayOf(airliftableEqid + 101)
        val index = js("{}")
        index["unitclass"] = classIndex
        Equipment.equipmentIndexes[country] = index
    }

    private fun ship(
        label: String,
        bits: Int,
    ) = EquipmentData().apply {
        name = label
        uclass = UnitClass.BATTLESHIP.value
        target = UnitType.SEA.value
        movmethod = MovMethod.NAVAL.value
        movpoints = 8
        ammo = 8
        grounddef = 8
        country = friendly.country + 1
        attr = bits
    }

    private fun truck(
        label: String,
        bits: Int,
    ) = EquipmentData().apply {
        name = label
        uclass = UnitClass.GROUND_TRANSPORT.value
        movmethod = MovMethod.WHEELED.value
        movpoints = 8
        grounddef = 2
        country = friendly.country + 1
        attr = bits
        // NOT Airmobile: this is the half of OG's rule the `embark` field carries.
        embark = EmbarkType.NONE.value
    }

    @AfterTest
    fun teardown() = clearTestWorld()

    private fun engineeringWorld(prestige: Int = 200): GameMap {
        ruleset(RuleKey.BUILD_AND_REPAIR to 1)
        val map = world(prestige = prestige)
        GameHolder.instance = holderFor(map)
        return map
    }

    /** LXF's own lists, verbatim from `tools/og-import/out/efile-cfg/lxf.json`. */
    private fun withLxfCosts() {
        EfileConfig.setForTest(
            rawKeyMap =
                mapOf(
                    "build_cost" to "12,48,60,36,24",
                    "build_turn" to "2,3,3,3,2",
                    "repair_turn" to "1,2,2,2,1,1",
                ),
        )
    }

    // ---- build_cost / build_turn / repair_turn ------------------------------------------------

    @Test
    fun theEfilesOwnColumnsAreReadInOgsOrder() {
        withLxfCosts()

        assertEquals(12, EngineeringWork.BRIDGE.costFor(), "column 0 is Bridge")
        assertEquals(48, EngineeringWork.AIRFIELD.costFor(), "column 1 is Airport")
        assertEquals(60, EngineeringWork.PORT.costFor(), "column 2 is Port")
        assertEquals(36, EngineeringWork.FORTIFICATION.costFor(), "column 3 is Fort")
        assertEquals(24, EngineeringWork.STATION.costFor(), "column 4 is Station")
    }

    @Test
    fun durationsComeFromTheSameColumns() {
        withLxfCosts()

        assertEquals(2, EngineeringWork.BRIDGE.turnsFor())
        assertEquals(3, EngineeringWork.AIRFIELD.turnsFor())
        assertEquals(3, EngineeringWork.PORT.turnsFor())
        assertEquals(3, EngineeringWork.FORTIFICATION.turnsFor())
        assertEquals(2, EngineeringWork.STATION.turnsFor())
        assertEquals(1, EngineeringWork.REPAIR.turnsFor(), "repair_turn's sixth column is OG's 'rest'")
    }

    @Test
    fun anEfileThatSaysNothingKeepsTheManualsFigures() {
        EfileConfig.setForTest()

        assertEquals(EngineeringWork.PORT.cost, EngineeringWork.PORT.costFor())
        assertEquals(EngineeringWork.BRIDGE.turns, EngineeringWork.BRIDGE.turnsFor())
    }

    @Test
    fun aListThatDoesNotParseWholeIsTreatedAsAbsent() {
        EfileConfig.setForTest(rawKeyMap = mapOf("build_cost" to "12,,60,36,24"))

        assertEquals(
            EngineeringWork.BRIDGE.cost,
            EngineeringWork.BRIDGE.costFor(),
            "a half-read list would make column 0 right and column 2 silently wrong",
        )
    }

    @Test
    fun aDemolitionIsFreeWhateverTheEfileSays() {
        withLxfCosts()

        assertEquals(0, EngineeringWork.RAZE.costFor(), "build_cost has no column for a demolition")
        assertEquals(0, EngineeringWork.BLOW_BRIDGE.costFor())
    }

    @Test
    fun theEfilePriceIsWhatThePlayerActuallyPays() {
        val map = engineeringWorld()
        withLxfCosts()
        map.map!![2][2].terrain = TerrainType.RIVER.value
        val sapper = place(map, sapperEqid, 2, 2, side = 0)
        val before = friendly.prestige

        map.beginEngineering(sapper, EngineeringWork.BRIDGE)

        assertEquals(before - 12, friendly.prestige, "LXF's bridge, not the manual's 16")
    }

    // ---- work belongs to the engineer ----------------------------------------------------------

    @Test
    fun aSiteWithNoEngineerOnItDoesNotAdvance() {
        val map = engineeringWorld()
        map.map!![2][2].terrain = TerrainType.RIVER.value
        val sapper = place(map, sapperEqid, 2, 2, side = 0)
        map.beginEngineering(sapper, EngineeringWork.BRIDGE)
        val remaining = map.map!![2][2].constructionTurns
        map.map!![2][2].delUnit(sapper)

        Engineering.advanceTurn(map.map, 0, builderOwner())

        assertEquals(remaining, map.map!![2][2].constructionTurns, "nobody is building it")
    }

    @Test
    fun theWorkResumesWhenAnEngineerComesBack() {
        val map = engineeringWorld()
        map.map!![2][2].terrain = TerrainType.RIVER.value
        val sapper = place(map, sapperEqid, 2, 2, side = 0)
        map.beginEngineering(sapper, EngineeringWork.BRIDGE)
        val remaining = map.map!![2][2].constructionTurns
        map.map!![2][2].delUnit(sapper)
        Engineering.advanceTurn(map.map, 0, builderOwner())
        map.map!![2][2].setUnit(sapper)

        Engineering.advanceTurn(map.map, 0, builderOwner())

        assertEquals(remaining - 1, map.map!![2][2].constructionTurns, "it pauses, it does not cancel")
    }

    @Test
    fun anEnemyStandingOnTheSiteDoesNotBuildItForYou() {
        val map = engineeringWorld()
        map.map!![2][2].terrain = TerrainType.RIVER.value
        val sapper = place(map, sapperEqid, 2, 2, side = 0)
        map.beginEngineering(sapper, EngineeringWork.BRIDGE)
        val remaining = map.map!![2][2].constructionTurns
        map.map!![2][2].delUnit(sapper)
        place(map, sapperEqid, 2, 2, side = 1)

        Engineering.advanceTurn(map.map, 0, builderOwner())

        assertEquals(remaining, map.map!![2][2].constructionTurns)
    }

    // ---- blow_any_terrain's real reach ---------------------------------------------------------

    @Test
    fun withoutTheKeyOnlyOgsThreeNamedFacilitiesAreRazeable() {
        EfileConfig.setForTest()

        assertTrue(TerrainType.CITY.value in EngineeringWork.razeableTerrain())
        assertFalse(TerrainType.MOUNTAIN.value in EngineeringWork.razeableTerrain())
        assertFalse(TerrainType.FOREST.value in EngineeringWork.razeableTerrain())
    }

    @Test
    fun blowAnyTerrainExcludesOnlyWater() {
        EfileConfig.setForTest(intKeyMap = mapOf("blow_any_terrain" to 1))
        val razeable = EngineeringWork.razeableTerrain()

        // OG: "any terrain except Ocean, Impas.River, River and Shallow Sea".
        assertFalse(TerrainType.OCEAN.value in razeable)
        assertFalse(TerrainType.RIVER.value in razeable)
        assertFalse(TerrainType.IMPASSABLE_RIVER.value in razeable)
        assertTrue(TerrainType.MOUNTAIN.value in razeable, "the old inference refused high ground; OG does not")
        assertTrue(TerrainType.HILL.value in razeable)
        assertTrue(TerrainType.SWAMP.value in razeable)
        assertTrue(TerrainType.STREAM.value in razeable, "a stream is not on OG's exclusion list")
    }

    @Test
    fun clearGroundIsRazeableUnderTheKeyAndLeavesTheHexBlown() {
        val map = engineeringWorld()
        EfileConfig.setForTest(intKeyMap = mapOf("blow_any_terrain" to 1, "build_start_ex" to 0))
        map.map!![2][2].terrain = TerrainType.CLEAR.value
        val sapper = place(map, sapperEqid, 2, 2, side = 0)

        assertTrue(
            TerrainType.CLEAR.value in EngineeringWork.razeableTerrain(),
            "OG excludes only the four water types",
        )
        assertTrue(EngineeringWork.RAZE in Engineering.availableWork(sapper))

        map.beginEngineering(sapper, EngineeringWork.RAZE)

        assertTrue(map.map!![2][2].rubble, "OG records a BLOWN hex rather than re-terraining it")
        assertEquals(
            TerrainType.CLEAR.value,
            map.map!![2][2].terrain,
            "and the terrain is unchanged, because there was no feature to take away",
        )
        assertEquals(-1, map.map!![2][2].razedTerrain, "nothing was razed, so Repair restores nothing")
    }

    @Test
    fun groundThatIsAlreadyBlownOffersNoSecondDemolition() {
        val map = engineeringWorld()
        EfileConfig.setForTest(intKeyMap = mapOf("blow_any_terrain" to 1))
        map.map!![2][2].terrain = TerrainType.CLEAR.value
        map.map!![2][2].rubble = true
        val sapper = place(map, sapperEqid, 2, 2, side = 0)

        assertFalse(EngineeringWork.RAZE in Engineering.availableWork(sapper))
        assertTrue(EngineeringWork.REPAIR in Engineering.availableWork(sapper), "but it can be put right")
    }

    @Test
    fun clearGroundStaysUnrazeableWithoutTheKey() {
        EfileConfig.setForTest()

        assertFalse(TerrainType.CLEAR.value in EngineeringWork.razeableTerrain())
    }

    // ---- repair_turn is indexed by what is being put back ---------------------------------------

    @Test
    fun aRepairTakesTheColumnOfTheThingItIsRepairing() {
        val map = engineeringWorld()
        withLxfCosts()
        val hex = map.map!![2][2]

        hex.razedTerrain = TerrainType.AIRFIELD.value
        assertEquals(2, EngineeringWork.REPAIR.turnsFor(hex), "LXF's Airport repair column")

        hex.razedTerrain = TerrainType.FORTIFICATION.value
        assertEquals(2, EngineeringWork.REPAIR.turnsFor(hex), "Fort column")

        hex.razedTerrain = -1
        hex.blownRoad = 3
        assertEquals(1, EngineeringWork.REPAIR.turnsFor(hex), "Bridge column")

        hex.blownRoad = 0
        hex.rubble = true
        assertEquals(1, EngineeringWork.REPAIR.turnsFor(hex), "blown ground falls to the rest column")
    }

    @Test
    fun theActionStripAskingInTheAbstractStillGetsTheRestColumn() {
        withLxfCosts()

        assertEquals(1, EngineeringWork.REPAIR.turnsFor(null))
    }

    // ---- a wrecked facility is unusable --------------------------------------------------------

    @Test
    fun aWreckedPortAnchorsNoPurchasesAndOpensNoDeployZone() {
        val map = engineeringWorld()
        val hex = map.map!![3][3]
        hex.terrain = TerrainType.PORT.value
        hex.owner = friendly.id
        map.invalidateDeployZones()

        assertTrue(map.hasPurchaseAnchor(friendly.side), "a working port anchors buying")
        assertTrue(map.isInDeployZone(friendly.side, 3, 3))

        hex.rubble = true
        map.invalidateDeployZones()

        assertFalse(map.hasPurchaseAnchor(friendly.side), "OG: unusable until repaired")
        assertFalse(map.isInDeployZone(friendly.side, 3, 3))
    }

    @Test
    fun aWreckedAirfieldBasesNoAircraft() {
        val map = engineeringWorld()
        val hex = map.map!![2][2]
        hex.terrain = TerrainType.AIRFIELD.value
        hex.flag = friendly.country
        val plane = place(map, infantryEqid, 2, 2, side = 0)

        hex.rubble = true

        assertFalse(MovementRules.hasAirfield(map, plane))
    }

    // ---- Air Transportable: OG's organic-transport rule for an airlift --------------------------

    @Test
    fun aTruckThatCannotFlyIsLeftOnTheAirfield() {
        val map = engineeringWorld()
        map.map!![2][2].terrain = TerrainType.AIRFIELD.value
        friendly.airTransports = 1
        val infantry = place(map, airliftableEqid, 2, 2, side = 0)
        infantry.setTransport(groundedTruckEqid)

        assertTrue(map.embarkUnit(infantry))

        assertEquals(null, infantry.transport, "OG: the transport must also be Airmobile/Airborne")
    }

    @Test
    fun anAirmobileTruckWithoutTheSpecialIsStillLeftBehind() {
        val map = engineeringWorld()
        map.map!![2][2].terrain = TerrainType.AIRFIELD.value
        friendly.airTransports = 1
        Equipment.putEquipment(
            groundedTruckEqid + 5,
            truck("Airmobile Lorry", bits = 0).apply { embark = EmbarkType.AIRBORNE.value },
        )
        val infantry = place(map, airliftableEqid, 2, 2, side = 0)
        infantry.setTransport(groundedTruckEqid + 5)

        assertTrue(map.embarkUnit(infantry))

        assertEquals(
            null,
            infantry.transport,
            "OG: \"AirTransportable special is only needed to be set for transport\" — embark is not an alternative",
        )
    }

    @Test
    fun anAirTransportableTruckComesAlong() {
        val map = engineeringWorld()
        map.map!![2][2].terrain = TerrainType.AIRFIELD.value
        friendly.airTransports = 1
        val infantry = place(map, airliftableEqid, 2, 2, side = 0)
        infantry.setTransport(flyingTruckEqid)

        assertTrue(map.embarkUnit(infantry))

        assertTrue(infantry.transport != null, "the bit is exactly the permission to fly it in")
    }

    @Test
    fun aNavalEmbarkationKeepsTheTruckEitherWay() {
        val map = engineeringWorld()
        map.map!![2][2].terrain = TerrainType.PORT.value
        friendly.navalTransports = 1
        val infantry = place(map, airliftableEqid, 2, 2, side = 0)
        infantry.setTransport(groundedTruckEqid)

        assertTrue(map.embarkUnit(infantry))

        assertTrue(infantry.transport != null, "OG states the condition for AIR transport alone")
    }

    // ---- build_start_ex, build_terr_ex, build_mask / blow_mask ---------------------------------

    @Test
    fun aSapperThatHasMovedMayNotStartWorkByDefault() {
        val map = engineeringWorld()
        EfileConfig.setForTest()
        map.map!![2][2].terrain = TerrainType.RIVER.value
        val sapper = place(map, sapperEqid, 2, 2, side = 0)
        sapper.hasMoved = true

        assertFalse(Engineering.mayStartWork(sapper), "OG's own \"hasn't done any action\"")
    }

    @Test
    fun buildStartExLetsItStartAsLongAsItStillHasItsShot() {
        val map = engineeringWorld()
        EfileConfig.setForTest(intKeyMap = mapOf("build_start_ex" to 1))
        map.map!![2][2].terrain = TerrainType.RIVER.value
        val sapper = place(map, sapperEqid, 2, 2, side = 0)
        sapper.hasMoved = true

        assertTrue(Engineering.mayStartWork(sapper), "OG: \"regardless movement\"")

        sapper.hasFired = true
        assertFalse(Engineering.mayStartWork(sapper), "but the shot is what it must still have")
    }

    @Test
    fun buildTerrExIsWhatAllowsAFortInTheForest() {
        val map = engineeringWorld()
        map.map!![2][2].terrain = TerrainType.FOREST.value
        val sapper = place(map, sapperEqid, 2, 2, side = 0)

        EfileConfig.setForTest()
        assertFalse(EngineeringWork.FORTIFICATION in Engineering.availableWork(sapper))

        EfileConfig.setForTest(intKeyMap = mapOf("build_terr_ex" to 1))
        assertTrue(EngineeringWork.FORTIFICATION in Engineering.availableWork(sapper))
    }

    @Test
    fun aBuildMaskOfZeroAllowsEverythingAsOgSays() {
        EfileConfig.setForTest()

        assertTrue(EngineeringWork.BRIDGE.permittedByEfileMask())
        assertTrue(EngineeringWork.STATION.permittedByEfileMask())
    }

    @Test
    fun aBuildMaskNamesExactlyWhatItAllows() {
        // OG: Bridge=1, Airport=2, Port=4, Fort=8, Station=16. "Bridge and Station" is 1+16.
        EfileConfig.setForTest(intKeyMap = mapOf("build_mask" to 17))

        assertTrue(EngineeringWork.BRIDGE.permittedByEfileMask())
        assertTrue(EngineeringWork.STATION.permittedByEfileMask())
        assertFalse(EngineeringWork.AIRFIELD.permittedByEfileMask())
        assertFalse(EngineeringWork.PORT.permittedByEfileMask())
    }

    @Test
    fun theBlowMaskIsASeparateKeyWithACityBit() {
        EfileConfig.setForTest(intKeyMap = mapOf("blow_mask" to 32, "build_mask" to 1))

        assertTrue(EngineeringWork.RAZE.permittedByEfileMask(), "City = 32, and razing is what blows one")
        assertFalse(EngineeringWork.BLOW_BRIDGE.permittedByEfileMask(), "the blow mask does not name Bridge")
        assertTrue(EngineeringWork.BRIDGE.permittedByEfileMask(), "and the BUILD mask is a different key")
    }

    @Test
    fun repairIsInNeitherMask() {
        EfileConfig.setForTest(intKeyMap = mapOf("build_mask" to 1, "blow_mask" to 1))

        assertTrue(EngineeringWork.REPAIR.permittedByEfileMask(), "OG masks what may be built and blown")
    }

    // ---- Air Support ---------------------------------------------------------------------------

    @Test
    fun anAirSupportShipServicesAircraftLikeAnAirfield() {
        val map = engineeringWorld()
        val tender = place(map, tenderEqid, 3, 3, side = 0)
        val plane = place(map, airliftableEqid, 3, 4, side = 0)

        assertTrue(MovementRules.hasAirfield(map, plane), "OG: the same than an airfield, ring included")
        assertTrue(tender.owner == friendly.id)
    }

    @Test
    fun anEnemyTenderServicesNothingOfYours() {
        val map = engineeringWorld()
        place(map, tenderEqid, 3, 3, side = 1)
        val plane = place(map, airliftableEqid, 3, 4, side = 0)

        assertFalse(MovementRules.hasAirfield(map, plane))
    }

    @Test
    fun anOrdinaryShipServicesNothing() {
        val map = engineeringWorld()
        place(map, plainShipEqid, 3, 3, side = 0)
        val plane = place(map, airliftableEqid, 3, 4, side = 0)

        assertFalse(MovementRules.hasAirfield(map, plane))
    }
}
