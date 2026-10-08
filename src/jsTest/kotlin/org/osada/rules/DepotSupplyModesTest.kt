package org.osada.rules

import org.osada.GameHolder
import org.osada.GameStateDeserializer
import org.osada.GameStateSerializer
import org.osada.UnitClass
import org.osada.model.EfileConfig
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.unitEndTurn
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `supply_ex` against the author's current mode table, the one-ammo payment and the supply
 * explanation (`docs/design/regional-supply.md` §2, 2026-10-08), plus the reaction budgets that a
 * mid-turn save must carry (`docs/README.md` C2).
 */
class DepotSupplyModesTest : OgRulesTestHarness() {
    private val depotEqid = 1203

    @BeforeTest
    fun setup() {
        installTestWorld()
        Equipment.putEquipment(
            depotEqid,
            EquipmentData().apply {
                name = "Supply Column"
                uclass = UnitClass.GROUND_TRANSPORT.value
                movpoints = 6
                ammo = 4
            },
        )
    }

    @AfterTest
    fun teardown() {
        clearTestWorld()
    }

    private fun mode(value: Int?) {
        EfileConfig.setForTest(intKeyMap = if (value == null) emptyMap() else mapOf("supply_ex" to value))
    }

    private fun depotWorld(): Triple<GameMap, GameUnit, GameUnit> {
        val map = world()
        GameHolder.instance = holderFor(map)
        val depot = place(map, depotEqid, 3, 3, 0).apply { isScenarioDepot = true }
        val hungry = place(map, infantryEqid, 3, 4, 0).apply { ammo = 0 }
        return Triple(map, depot, hungry)
    }

    @Test
    fun anAbsentKeyIsNormalSupplyWithDepotsActive() {
        mode(null)
        val (map, _, hungry) = depotWorld()
        val stranded = place(map, infantryEqid, 6, 6, 0).apply { ammo = 0 }

        // OG's normal rules list "resupply ... if adjacent to a Depot"; zero is not "no depots".
        assertNotNull(DepotSupply.supplierFor(map, hungry), "an authored depot works without supply_ex")
        assertFalse(DepotSupply.enabled(), "normal supply is not restricted")
        assertTrue(DepotSupply.permitsSupply(map, stranded), "field supply still works away from depots")
    }

    /** The published table: restricts / depots / ammo cost, per mode. */
    @Test
    fun modeTruthTable() {
        data class Row(
            val mode: Int,
            val restricts: Boolean,
            val depots: Boolean,
            val spends: Boolean,
            val cities: Boolean,
        )
        listOf(
            Row(0, restricts = false, depots = true, spends = false, cities = false),
            Row(1, restricts = true, depots = true, spends = false, cities = false),
            Row(2, restricts = true, depots = false, spends = false, cities = true),
            Row(3, restricts = true, depots = true, spends = false, cities = true),
            // "4 standard rules plus Depots spending 1 ammo"
            Row(4, restricts = false, depots = true, spends = true, cities = false),
            Row(5, restricts = true, depots = true, spends = true, cities = false),
            // "6 (2+4) would be taken as 2 by engine"
            Row(6, restricts = true, depots = false, spends = false, cities = true),
            Row(7, restricts = true, depots = true, spends = true, cities = true),
        ).forEach { row ->
            mode(row.mode)
            val (map, _, hungry) = depotWorld()
            assertEquals(row.restricts, DepotSupply.enabled(), "mode ${row.mode} restricts")
            assertEquals(row.depots, DepotSupply.supplierFor(map, hungry) != null, "mode ${row.mode} depots")
            assertEquals(row.spends, DepotSupply.depotSpendsAmmo(), "mode ${row.mode} spends ammo")
            assertEquals(row.cities, DepotSupply.citiesSupply(), "mode ${row.mode} cities")
        }
    }

    @Test
    fun aDepotPayingItsLastAmmoStillServesEveryoneThatTurn() {
        mode(5)
        val (map, depot, first) = depotWorld()
        depot.ammo = 1
        val second = place(map, infantryEqid, 2, 3, 0).apply { ammo = 0 }

        DepotSupply.chargeSupplier(map, first)
        assertEquals(0, depot.ammo)
        assertNotNull(DepotSupply.supplierFor(map, second), "one ammo pays for the whole turn")
        DepotSupply.chargeSupplier(map, second)
        assertEquals(0, depot.ammo, "no second charge")
        assertFalse(depot.hasResupplied, "paying is not the depot resupplying itself")

        depot.unitEndTurn(0)
        assertFalse(depot.depotPaidThisTurn)
        assertNull(DepotSupply.supplierFor(map, second), "a new turn needs a new payment")
    }

    @Test
    fun theExplanationNamesTheDepotForResupplyOnly() {
        mode(null)
        val (map, _, hungry) = depotWorld()
        place(map, infantryEqid, 4, 4, 1)

        val resupply = SupplyContextRules.getSupplyContext(map, hungry)
        assertEquals(SupplySource.DEPOT, resupply.source)
        assertEquals(100, resupply.efficiencyPercent)
        assertEquals(0, resupply.adjacentEnemies, "enemies do not reduce depot supply, so none are cited")

        val reinforce = SupplyContextRules.getSupplyContext(map, hungry, reinforcement = true)
        assertEquals(SupplySource.GROUND, reinforce.source)
        assertEquals(1, reinforce.adjacentEnemies)
    }

    @Test
    fun reactionBudgetsAndDepotPaymentSurviveASave() {
        val unit =
            GameUnit(infantryEqid).apply {
                hasInterceptedThisTurn = true
                hasInterceptedMovingAirThisTurn = true
                hasAmbushedThisTurn = true
                hasSupportedThisTurn = true
                depotPaidThisTurn = true
            }
        val restored = GameStateDeserializer.deserializeUnit(reparse(GameStateSerializer.serializeUnit(unit)))

        assertTrue(restored.hasInterceptedThisTurn)
        assertTrue(restored.hasInterceptedMovingAirThisTurn)
        assertTrue(restored.hasAmbushedThisTurn)
        assertTrue(restored.hasSupportedThisTurn)
        assertTrue(restored.depotPaidThisTurn)

        val fresh = GameUnit(infantryEqid)
        val json = reparse(GameStateSerializer.serializeUnit(fresh))
        assertEquals(undefined, json.hasInterceptedMovingAirThisTurn, "unspent budgets add no keys")
        assertFalse(GameStateDeserializer.deserializeUnit(json).hasInterceptedMovingAirThisTurn)
    }
}
