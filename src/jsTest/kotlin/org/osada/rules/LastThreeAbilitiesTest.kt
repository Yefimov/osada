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
import org.osada.model.moveUnit
import org.osada.model.selectUnit
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
    private val trainEqid = 1204

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
        // Every gate named explicitly: the unnamed ones default to reading `ActiveRuleset`, and
        // these three gained gates of their own on 2026-08-29 (they used to be badged even with
        // their rule off). An omitted gate here would silently hide the very abilities under test.
        val gates =
            AbilityGates(
                minefields = true,
                engineering = true,
                counterBattery = true,
                extendedLos = true,
                dryUnitPenalties = true,
                railTransport = true,
                carrierDeploy = true,
                depotSupply = true,
                heavyMoveFire = true,
            )
        val abilities = data.abilityCatalog(gates)

        assertEquals(3, abilities.size)
        assertTrue(abilities.all { it.wired }, "no ability is descriptive-only any more")
    }

    // ---- the keys gate the mechanics ---------------------------------------------------------

    @Test
    fun allThreeMechanicsAreOffUntilTheRulesetAsksForThem() {
        putTrain()
        val map = world()
        GameHolder.instance = holderFor(map)
        friendly.railTransports = 4
        friendly.railTransportsMax = 4
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
        // default ruleset both are off, which is a description of the game before §AA. The
        // railway was the third until schema 20 retired its key: it runs wherever a pool exists.
        assertFalse(CarrierDeploy.permits(map, fighter, 4, 4))
        assertTrue(RailTransport.canEntrain(marcher), "no ruleset key gates the railway any more")
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

    /** A rail transport as OG defines one: Ground Transport, RAIL movement, no weapon. */
    private fun putTrain() {
        Equipment.putEquipment(
            trainEqid,
            EquipmentData().apply {
                name = "Train"
                uclass = UnitClass.GROUND_TRANSPORT.value
                movmethod = org.osada.MovMethod.RAIL.value
                movpoints = 10
                grounddef = 2
                // As LXF's trains carry it. The infantry aboard has none, and a range clamped by
                // the RIDER's fuel left the first real train in the browser with nowhere to go.
                fuel = 255
            },
        )
    }

    private fun railWorld(pool: Int): org.osada.model.GameMap {
        putTrain()
        friendly.railTransports = pool
        friendly.railTransportsMax = pool
        val map = world()
        GameHolder.instance = holderFor(map)
        railLine(map, row = 3, fromCol = 1, toCol = 6)
        map.map!![3][1].station = true
        map.map!![3][6].station = true
        return map
    }

    @Test
    fun railIsInertUntilAScenarioGivesThePlayerAPool() {
        val map = railWorld(pool = 0)
        val unit = place(map, infantryEqid, 3, 1, 0)

        // A scenario whose author granted no rail pool -- 382 of the 502 deployed.
        assertFalse(RailTransport.canEntrain(unit))
        assertFalse(RailTransport.entrain(map, unit))
        assertEquals(0, unit.carrier)
    }

    @Test
    fun boardingPutsTheFormationAboardARealTrain() {
        val map = railWorld(pool = 2)
        val unit = place(map, infantryEqid, 3, 1, 0)

        assertTrue(RailTransport.entrain(map, unit))
        assertEquals(trainEqid, unit.carrier, "the formation rides the train record")
        assertTrue(RailTransport.isEntrained(unit))
        assertEquals(trainEqid, unit.getEqid(), "so it moves, defends and is drawn as the train")
        assertEquals(1, friendly.railTransports, "one train taken from the pool")
        assertFalse(unit.hasMoved, "and the train may leave on this same turn")
    }

    @Test
    fun aTrainMovesAlongTheTrackAndNowhereElse() {
        val map = railWorld(pool = 1)
        val unit = place(map, infantryEqid, 3, 1, 0)
        RailTransport.entrain(map, unit)

        val range = GameRules.getMoveRange(map, unit)
        assertTrue(range.isNotEmpty(), "the train can go somewhere")
        assertTrue(range.all { map.map!![it.row][it.col].rail > 0 }, "only onto track: $range")
        assertTrue(range.any { it.row == 3 && it.col == 6 }, "as far as the far station")
    }

    @Test
    fun aJourneySpansTurnsUntilTheFormationStepsOffAtAStation() {
        val map = railWorld(pool = 1)
        val unit = place(map, infantryEqid, 3, 1, 0)
        RailTransport.entrain(map, unit)
        map.selectUnit(unit)
        map.moveUnit(unit, 3, 4)
        assertEquals(4, unit.getPos()?.col, "the train moved")

        assertTrue(RailTransport.isEntrained(unit), "still aboard between stations")
        assertFalse(RailTransport.canDetrain(unit), "and with no station here it cannot get off")

        map.endTurn()
        map.endTurn()
        assertEquals(0, friendly.railTransports, "the train is still in use on the next turn")
        map.selectUnit(unit)
        map.moveUnit(unit, 3, 6)
        assertEquals(6, unit.getPos()?.col)

        assertTrue(RailTransport.detrain(map, unit))
        assertEquals(0, unit.carrier)
        assertEquals(1, friendly.railTransports, "given back when the formation is off")
        assertEquals(0, unit.moveLeft, "leaving after the journey ends the move")
    }

    @Test
    fun boardingAndSteppingStraightOffIsFree() {
        val map = railWorld(pool = 1)
        val unit = place(map, infantryEqid, 3, 1, 0)
        RailTransport.entrain(map, unit)

        assertTrue(RailTransport.detrain(map, unit))
        assertFalse(unit.hasMoved, "nothing happened, so nothing is spent")
        assertEquals(1, friendly.railTransports)
    }

    @Test
    fun noNeedStationBoardsAndLeavesAnywhereOnTheTrack() {
        val map = railWorld(pool = 1)
        val unit = place(map, infantryEqid, 3, 3, 0)
        assertFalse(RailTransport.canEntrain(unit), "plain track, no station")

        unit.unitData(true).attrEx = attrExNoNeedStation
        // 11,003 shipped records carry this bit; bn9s20's Soviet infantry among them.
        assertTrue(RailTransport.entrain(map, unit))
        map.selectUnit(unit)
        map.moveUnit(unit, 3, 5)
        assertEquals(5, unit.getPos()?.col)
        assertTrue(RailTransport.detrain(map, unit), "and it may step off between stations too")
    }

    @Test
    fun aFormationThatHasActedCannotBoard() {
        val map = railWorld(pool = 1)
        val unit = place(map, infantryEqid, 3, 1, 0)

        unit.hasMoved = true
        assertFalse(RailTransport.canEntrain(unit), "OG: the unit must not already have acted")
    }

    @Test
    fun theTurnStartPoolCountsTrainsStillCarryingSomething() {
        val map = railWorld(pool = 2)
        val aboard = place(map, infantryEqid, 3, 1, 0)
        RailTransport.entrain(map, aboard)
        // A save from the atomic railway spent the pool and refreshed it each turn; whatever it
        // stored, the recount gives the authored size less the trains in use.
        friendly.railTransports = 0

        RailTransport.recountPool(map, friendly)
        assertEquals(1, friendly.railTransports)

        aboard.destroyed = true
        RailTransport.recountPool(map, friendly)
        assertEquals(2, friendly.railTransports, "a destroyed train is no longer in use")
    }

    @Test
    fun railMoveIsOnTheActionStripAsABoardAndLeaveToggle() {
        val map = railWorld(pool = 4)
        val unit = place(map, infantryEqid, 3, 1, 0)
        val context = { UnitActionContext(map = map, unit = unit, currentPlayer = friendly) }

        val board = UnitActionAvailability.all(context()).single { it.action == UnitActionId.RAIL_MOVE }
        assertTrue(board.applicable && board.enabled, board.reasons.toString())
        assertEquals(ActionEffectKind.BOARD_TRAIN, board.effects.single().kind)

        RailTransport.entrain(map, unit)
        val strip = UnitActionAvailability.all(context())
        val leave = strip.single { it.action == UnitActionId.RAIL_MOVE }
        assertTrue(leave.applicable && leave.enabled, leave.reasons.toString())
        assertEquals(ActionEffectKind.LEAVE_TRAIN, leave.effects.single().kind)
        assertFalse(strip.single { it.action == UnitActionId.EMBARK }.applicable, "a train is not a ship")
        assertFalse(strip.single { it.action == UnitActionId.MOUNT }.applicable, "nor a lorry")
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
