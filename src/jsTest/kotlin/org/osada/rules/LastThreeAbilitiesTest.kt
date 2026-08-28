package org.osada.rules

import org.osada.GameHolder
import org.osada.RoadType
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.model.AbilityGates
import org.osada.model.EfileConfig
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.abilityCatalog
import org.osada.rules.ruleset.RuleKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The last three descriptive-only abilities, wired 2026-08-28 (`docs/og-fidelity-plan.md` §AA):
 * `Carrier Deploy`, `No Need Station` and `Supply Unit`.
 *
 * Two of the three are **inert on shipped content by construction** — no scenario authors
 * `railtrans`, no efile sets `supply_ex` — so these tests are the only place their behaviour is
 * exercised at all. They are correspondingly the only guard against the rules rotting.
 */
class LastThreeAbilitiesTest : OgRulesTestHarness() {
    /** OG's `Carrier Deploy`, `attr` bit 19. */
    private val attrCarrierDeploy = 524288

    /** OG's `No Need Station`, `attrEx` bit 7. */
    private val attrExNoNeedStation = 128

    /** OG's `Supply Unit`, `attrEx` bit 20. */
    private val attrExSupplyUnit = 1048576

    private val fighterEqid = 1200
    private val carrierEqid = 1201
    private val hangarlessCarrierEqid = 1202
    private val depotEqid = 1203

    @BeforeTest
    fun setup() {
        installTestWorld()
        Equipment.putEquipment(
            fighterEqid,
            EquipmentData().apply {
                name = "Carrier Fighter"
                uclass = UnitClass.FIGHTER.value
                // `UnitPredicates.isAir` reads the MOVEMENT METHOD, not the class.
                movmethod = org.osada.MovMethod.AIR.value
                movpoints = 6
                ammo = 4
                attr = attrCarrierDeploy
            },
        )
        Equipment.putEquipment(
            carrierEqid,
            EquipmentData().apply {
                name = "Fleet Carrier"
                uclass = UnitClass.CARRIER.value
                movmethod = org.osada.MovMethod.DEEP_NAVAL.value
                movpoints = 5
                hangarCap = 6
            },
        )
        Equipment.putEquipment(
            hangarlessCarrierEqid,
            EquipmentData().apply {
                name = "Converted Liner"
                uclass = UnitClass.CARRIER.value
                movmethod = org.osada.MovMethod.DEEP_NAVAL.value
                movpoints = 5
                hangarCap = 0
            },
        )
        Equipment.putEquipment(
            depotEqid,
            EquipmentData().apply {
                name = "Supply Column"
                uclass = UnitClass.GROUND_TRANSPORT.value
                movpoints = 6
                ammo = 4
                attrEx = attrExSupplyUnit
            },
        )
    }

    @AfterTest
    fun teardown() {
        clearTestWorld()
    }

    // ---- the milestone itself -----------------------------------------------------------------

    @Test
    fun allThreeReportThemselvesAsWired() {
        val data =
            EquipmentData().apply {
                attr = attrCarrierDeploy
                attrEx = attrExNoNeedStation or attrExSupplyUnit
            }
        val gates =
            AbilityGates(minefields = true, engineering = true, counterBattery = true, extendedLos = true)
        val abilities = data.abilityCatalog(gates)

        assertEquals(3, abilities.size)
        assertTrue(abilities.all { it.wired }, "no ability is descriptive-only any more")
    }

    // ---- the keys gate the mechanics ---------------------------------------------------------

    @Test
    fun allThreeMechanicsAreOffUntilTheRulesetAsksForThem() {
        val map = world()
        GameHolder.instance = holderFor(map)
        friendly.railTransports = 4
        railLine(map, row = 3, fromCol = 1, toCol = 6)
        map.map!![3][1].station = true
        map.map!![3][6].station = true
        EfileConfig.setForTest(intKeyMap = mapOf("supply_ex" to 1))
        map.map!![4][4].terrain = TerrainType.OCEAN.value
        place(map, carrierEqid, 4, 4, 0)
        place(map, depotEqid, 2, 2, 0)
        val fighter = place(map, fighterEqid, 1, 1, 0)
        val marcher = place(map, infantryEqid, 3, 1, 0)
        val hungry = place(map, infantryEqid, 2, 3, 0)

        // Schema 12: each mechanic has a key of its own so a player can choose it. With the
        // default ruleset all three are off, which is a description of the game before §AA.
        assertFalse(CarrierDeploy.permits(map, fighter, 4, 4))
        assertFalse(RailTransport.canEntrain(marcher))
        assertFalse(DepotSupply.enabled())
        assertNull(DepotSupply.supplierFor(map, hungry))
    }

    // ---- Carrier Deploy -----------------------------------------------------------------------

    @Test
    fun anAircraftWithTheBitMayDeployOntoAFriendlyCarrier() {
        ruleset(RuleKey.CARRIER_DEPLOY to 1)
        val map = world()
        GameHolder.instance = holderFor(map)
        map.map!![4][4].terrain = TerrainType.OCEAN.value
        place(map, carrierEqid, 4, 4, 0)
        val fighter = place(map, fighterEqid, 1, 1, 0)

        assertTrue(CarrierDeploy.permits(map, fighter, 4, 4))
    }

    @Test
    fun anAircraftWithoutTheBitMayNot() {
        ruleset(RuleKey.CARRIER_DEPLOY to 1)
        val map = world()
        GameHolder.instance = holderFor(map)
        place(map, carrierEqid, 4, 4, 0)
        val plain = place(map, infantryEqid, 1, 1, 0)

        // Neither an aircraft nor a carrier-capable one -- both halves refuse.
        assertFalse(CarrierDeploy.permits(map, plain, 4, 4))
    }

    @Test
    fun anEnemyCarrierIsNotADeployTarget() {
        ruleset(RuleKey.CARRIER_DEPLOY to 1)
        val map = world()
        GameHolder.instance = holderFor(map)
        place(map, carrierEqid, 4, 4, 1)
        val fighter = place(map, fighterEqid, 1, 1, 0)

        assertFalse(CarrierDeploy.permits(map, fighter, 4, 4))
    }

    @Test
    fun aCarrierWithNoHangarCapacityCannotReceiveAircraft() {
        ruleset(RuleKey.CARRIER_DEPLOY to 1)
        val map = world()
        GameHolder.instance = holderFor(map)
        place(map, hangarlessCarrierEqid, 4, 4, 0)
        val fighter = place(map, fighterEqid, 1, 1, 0)

        // `hangarCap` was imported in §U.8 and read by nothing until now.
        assertFalse(CarrierDeploy.permits(map, fighter, 4, 4))
    }

    @Test
    fun air2containerDeployRequiresThePort() {
        ruleset(RuleKey.CARRIER_DEPLOY to 1)
        EfileConfig.setForTest(intKeyMap = mapOf("air2container_deploy" to 1))
        val map = world()
        GameHolder.instance = holderFor(map)
        place(map, carrierEqid, 4, 4, 0)
        val fighter = place(map, fighterEqid, 1, 1, 0)

        assertFalse(CarrierDeploy.permits(map, fighter, 4, 4), "carrier is at sea")

        map.map!![4][4].terrain = TerrainType.PORT.value
        assertTrue(CarrierDeploy.permits(map, fighter, 4, 4), "and in port it may")
    }

    // ---- No Need Station / rail ---------------------------------------------------------------

    @Test
    fun railIsInertUntilAScenarioGivesThePlayerAPool() {
        val map = world()
        railLine(map, row = 3, fromCol = 1, toCol = 6)
        map.map!![3][1].station = true
        map.map!![3][6].station = true
        val unit = place(map, infantryEqid, 3, 1, 0)

        // No shipped scenario authors `railtrans`, so this is every scenario today.
        assertEquals(0, friendly.railTransports)
        assertFalse(RailTransport.canEntrain(unit))
        assertTrue(RailTransport.destinations(map, unit).isEmpty())
    }

    @Test
    fun withAPoolAFormationRailsBetweenStations() {
        ruleset(RuleKey.RAIL_TRANSPORT to 1)
        friendly.railTransports = 2
        val map = world()
        railLine(map, row = 3, fromCol = 1, toCol = 6)
        map.map!![3][1].station = true
        map.map!![3][6].station = true
        val unit = place(map, infantryEqid, 3, 1, 0)

        assertTrue(RailTransport.canEntrain(unit))
        val destinations = RailTransport.destinations(map, unit)
        assertEquals(listOf(6), destinations.map { it.col }, "only the other STATION is offered")

        assertTrue(RailTransport.entrain(map, unit, 3, 6))
        assertEquals(6, unit.getPos()?.col)
        assertEquals(1, friendly.railTransports, "one train spent")
        assertTrue(unit.hasMoved, "the journey is the formation's move")
        assertFalse(unit.hasFired, "and leaves its shot alone")
    }

    @Test
    fun noNeedStationOffersEveryHexOnTheTrack() {
        ruleset(RuleKey.RAIL_TRANSPORT to 1)
        friendly.railTransports = 1
        val map = world()
        railLine(map, row = 3, fromCol = 1, toCol = 6)
        map.map!![3][1].station = true
        val unit = place(map, infantryEqid, 3, 1, 0)
        unit.unitData(true).attrEx = attrExNoNeedStation

        // 11,003 shipped records carry this bit. With no station at the far end, it is the only
        // thing that makes the far end reachable at all.
        assertEquals(listOf(2, 3, 4, 5, 6), RailTransport.destinations(map, unit).map { it.col }.sorted())
    }

    @Test
    fun anEnemyOnTheLineCutsIt() {
        ruleset(RuleKey.RAIL_TRANSPORT to 1)
        friendly.railTransports = 1
        val map = world()
        railLine(map, row = 3, fromCol = 1, toCol = 6)
        map.map!![3][1].station = true
        map.map!![3][6].station = true
        place(map, infantryEqid, 3, 4, 1)
        val unit = place(map, infantryEqid, 3, 1, 0)

        assertTrue(RailTransport.destinations(map, unit).isEmpty(), "the far station is behind the enemy")
    }

    @Test
    fun aFormationThatHasActedCannotBoard() {
        ruleset(RuleKey.RAIL_TRANSPORT to 1)
        friendly.railTransports = 1
        val map = world()
        railLine(map, row = 3, fromCol = 1, toCol = 6)
        map.map!![3][1].station = true
        map.map!![3][6].station = true
        val unit = place(map, infantryEqid, 3, 1, 0)

        unit.hasMoved = true
        assertFalse(RailTransport.canEntrain(unit), "OG: the unit must not already have acted")
    }

    // ---- Supply Unit / supply_ex --------------------------------------------------------------

    @Test
    fun supplyExIsOffForEveryShippedEfile() {
        EfileConfig.setForTest(intKeyMap = emptyMap())

        assertFalse(DepotSupply.enabled(), "no shipped efile sets supply_ex")
    }

    @Test
    fun aDepotSuppliesItsNeighbourEvenAfterItHasMovedAndFired() {
        ruleset(RuleKey.DEPOT_SUPPLY to 1)
        EfileConfig.setForTest(intKeyMap = mapOf("supply_ex" to 1))
        val map = world()
        GameHolder.instance = holderFor(map)
        place(map, depotEqid, 3, 3, 0)
        val hungry = place(map, infantryEqid, 3, 4, 0)
        hungry.ammo = 0
        hungry.hasMoved = true
        hungry.hasFired = true

        // OG's changelog lists "units failing to resupply from an adjacent Depot after moving or
        // firing" as a BUG it fixed, so neither may disqualify.
        assertNotNull(DepotSupply.supplierFor(map, hungry))
        assertTrue(GameRules.canResupply(map, hungry))
    }

    @Test
    fun aDepotIsNotSuppliedByAnotherUnlessTheEfileAddsEight() {
        ruleset(RuleKey.DEPOT_SUPPLY to 1)
        EfileConfig.setForTest(intKeyMap = mapOf("supply_ex" to 1))
        val map = world()
        GameHolder.instance = holderFor(map)
        place(map, depotEqid, 3, 3, 0)
        val otherDepot = place(map, depotEqid, 3, 4, 0)
        otherDepot.ammo = 0

        assertNull(DepotSupply.supplierFor(map, otherDepot), "'except if unit is also a Depot'")

        ruleset(RuleKey.DEPOT_SUPPLY to 1)
        EfileConfig.setForTest(intKeyMap = mapOf("supply_ex" to 9))
        assertNotNull(DepotSupply.supplierFor(map, otherDepot))
    }

    @Test
    fun aDepotThatHasActedCannotSupplyAnotherDepot() {
        ruleset(RuleKey.DEPOT_SUPPLY to 1)
        EfileConfig.setForTest(intKeyMap = mapOf("supply_ex" to 9))
        val map = world()
        GameHolder.instance = holderFor(map)
        val supplier = place(map, depotEqid, 3, 3, 0)
        val otherDepot = place(map, depotEqid, 3, 4, 0)
        otherDepot.ammo = 0
        supplier.hasFired = true

        // "as far as they have not supplied other units, nor moved nor fired" -- the conditions
        // belong to the SUPPLIER.
        assertNull(DepotSupply.supplierFor(map, otherDepot))
    }

    @Test
    fun modeFourSpendsOneAmmoForTheWholeTurn() {
        ruleset(RuleKey.DEPOT_SUPPLY to 1)
        EfileConfig.setForTest(intKeyMap = mapOf("supply_ex" to 5))
        val map = world()
        GameHolder.instance = holderFor(map)
        val depot = place(map, depotEqid, 3, 3, 0)
        depot.ammo = 2
        val first = place(map, infantryEqid, 3, 4, 0)
        val second = place(map, infantryEqid, 2, 3, 0)

        DepotSupply.chargeSupplier(map, first)
        DepotSupply.chargeSupplier(map, second)

        // "not 1 ammo per unit, just 1 ammo to resupply any number of units in that turn"
        assertEquals(1, depot.ammo)
    }

    @Test
    fun aDepotWithNoAmmoCannotSupplyUnderModeFour() {
        ruleset(RuleKey.DEPOT_SUPPLY to 1)
        EfileConfig.setForTest(intKeyMap = mapOf("supply_ex" to 5))
        val map = world()
        GameHolder.instance = holderFor(map)
        val depot = place(map, depotEqid, 3, 3, 0)
        depot.ammo = 0
        val hungry = place(map, infantryEqid, 3, 4, 0)

        assertNull(DepotSupply.supplierFor(map, hungry), "'if no ammo, cannot resupply units'")
    }

    @Test
    fun airUnitsAreNeverAffectedByTheKey() {
        ruleset(RuleKey.DEPOT_SUPPLY to 1)
        EfileConfig.setForTest(intKeyMap = mapOf("supply_ex" to 1))
        val map = world()
        GameHolder.instance = holderFor(map)
        val fighter = place(map, fighterEqid, 3, 4, 0)

        // "Air units are not affected by this config var."
        assertNull(DepotSupply.supplierFor(map, fighter))
        assertTrue(DepotSupply.permitsSupply(map, fighter))
    }

    @Test
    fun aNonDefaultModeRestrictsEverybodyElse() {
        ruleset(RuleKey.DEPOT_SUPPLY to 1)
        EfileConfig.setForTest(intKeyMap = mapOf("supply_ex" to 1))
        val map = world()
        GameHolder.instance = holderFor(map)
        val stranded = place(map, infantryEqid, 6, 6, 0)

        // "Any other value restricts units to resupply only from Depots and/or Cities/Ports."
        assertFalse(DepotSupply.permitsSupply(map, stranded))
    }

    private fun railLine(
        map: org.osada.model.GameMap,
        row: Int,
        fromCol: Int,
        toCol: Int,
    ) {
        for (c in fromCol..toCol) map.map!![row][c].rail = RoadType.NORTH.value
    }
}
