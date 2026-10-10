package org.osada.rules

import org.osada.GameHolder
import org.osada.GameStateSerializer
import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.restoreCoreUnitList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A reserve formation keeps the fuel and ammunition it has left across a reload.
 *
 * `CoreUnitListOperations.buildRestoredUnit` starts from a fresh unit, which is full, and read
 * neither value -- so any save, Continue, Restart Mission or co-op recovery refilled every partly
 * spent reserve formation. Found 2026-10-10 by the co-op campaign rejoin probe: a carried-over tank
 * at 50 fuel came back at 57 on every screen, against the room's own snapshot.
 */
class ReserveSupplyRoundTripTest : OgRulesTestHarness() {
    private val tankEqid = 990_201

    @BeforeTest
    fun setup() {
        installTestWorld()
        Equipment.putEquipment(
            tankEqid,
            EquipmentData().apply {
                eqid = tankEqid
                name = "T-34"
                uclass = UnitClass.TANK.value
                movmethod = MovMethod.TRACKED.value
                movpoints = 6
                fuel = 57
                ammo = 9
            },
        )
    }

    @AfterTest
    fun teardown() = clearTestWorld()

    private fun reserveTank(
        fuel: Int,
        ammo: Int,
    ) = GameUnit(tankEqid).apply {
        id = 77
        owner = friendly.id
        player = friendly
        isCore = true
        isDeployed = false
        this.fuel = fuel
        this.ammo = ammo
    }

    private fun roundTrip(serialized: dynamic): GameUnit {
        val map = world()
        GameHolder.instance = holderFor(map)
        val payload: dynamic = JSON.parse<dynamic>(JSON.stringify(serialized))
        map.restoreCoreUnitList(friendly, listOf(payload))
        return friendly.getCoreUnitList().single { it.id == 77 }
    }

    @Test
    fun theCampaignRosterKeepsWhatAReserveFormationHasLeft() {
        val restored = roundTrip(GameStateSerializer.serializeCoreUnit(reserveTank(fuel = 50, ammo = 4)))
        assertEquals(50, restored.fuel)
        assertEquals(4, restored.ammo)
    }

    @Test
    fun theFullUnitShapeKeepsItToo() {
        val restored = roundTrip(GameStateSerializer.serializeUnit(reserveTank(fuel = 31, ammo = 2)))
        assertEquals(31, restored.fuel)
        assertEquals(2, restored.ammo)
    }

    /** The optional-key rule: a full reserve writes nothing new, so its save keeps its exact shape. */
    @Test
    fun aFullReserveFormationWritesNoSupplyKeys() {
        val payload: dynamic = GameStateSerializer.serializeCoreUnit(reserveTank(fuel = 57, ammo = 9))
        assertEquals(undefined, payload.fuel)
        assertEquals(undefined, payload.ammo)
        val restored = roundTrip(payload)
        assertEquals(57, restored.fuel)
        assertEquals(9, restored.ammo)
    }

    /** Stalin Regime keeps pools at ten times the scale; a boosted full unit is still "full". */
    @Test
    fun aBoostedReserveKeepsItsScaledValues() {
        val tank = reserveTank(fuel = 570, ammo = 30).apply { stalinRegimeBoosted = true }
        val payload: dynamic = GameStateSerializer.serializeCoreUnit(tank)
        assertEquals(undefined, payload.fuel, "570 is a boosted full tank")
        assertEquals(30, payload.ammo)
        val restored = roundTrip(payload)
        assertEquals(570, restored.fuel)
        assertEquals(30, restored.ammo)
    }
}
