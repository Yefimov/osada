package org.osada.rules

import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.Player
import org.osada.model.buyUnit
import org.osada.model.upgradeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OG's Fronts and Factions, as OpenSuite resolves them into a scenario's `.buy4` whitelist.
 *
 * Five deployed scenarios author one (`bn9s02`, `bn9s05`, `bn9s11`, `bn9s14`, `bn9s16`), and the
 * property that matters for the other 497 is the one asserted first: **no list means no change**.
 */
class ScenarioPurchaseListTest : OgRulesTestHarness() {
    private val allowedEqid = 951
    private val forbiddenEqid = 952
    private val allowedTransportEqid = 953
    private val forbiddenTransportEqid = 954

    @BeforeTest
    fun setup() {
        installTestWorld()
        Equipment.putEquipment(allowedEqid, rifle("Listed Regiment"))
        Equipment.putEquipment(forbiddenEqid, rifle("Unlisted Regiment"))
        Equipment.putEquipment(allowedTransportEqid, truck("Listed Truck"))
        Equipment.putEquipment(forbiddenTransportEqid, truck("Unlisted Truck"))
    }

    @AfterTest
    fun teardown() {
        clearTestWorld()
    }

    private fun rifle(label: String) =
        EquipmentData().apply {
            name = label
            uclass = UnitClass.INFANTRY.value
            movmethod = MovMethod.LEG.value
            cost = 10
        }

    private fun truck(label: String) =
        EquipmentData().apply {
            name = label
            uclass = UnitClass.GROUND_TRANSPORT.value
            movmethod = MovMethod.WHEELED.value
            cost = 5
        }

    private fun buyer(list: Set<Int>?) =
        Player().apply {
            id = 0
            side = 0
            prestige = 100_000
            purchaseList = list
        }

    /** 497 of the 502 deployed scenarios author nothing, and must behave exactly as before. */
    @Test
    fun aScenarioWithNoListRestrictsNothing() {
        val player = buyer(null)
        assertFalse(ScenarioPurchaseList.restricts(player))
        assertTrue(ScenarioPurchaseList.allows(player, forbiddenEqid))
        assertTrue(ScenarioPurchaseList.allows(null, forbiddenEqid), "and neither does an absent player")
    }

    /** The whitelist is exactly that: in the list is allowed, out of it is not. */
    @Test
    fun anAuthoredListAllowsOnlyWhatItNames() {
        val player = buyer(setOf(allowedEqid))
        assertTrue(ScenarioPurchaseList.restricts(player))
        assertTrue(ScenarioPurchaseList.allows(player, allowedEqid))
        assertFalse(ScenarioPurchaseList.allows(player, forbiddenEqid))
    }

    /** "No transport" is not an equipment id and must never be refused as one. */
    @Test
    fun theAbsentTransportSentinelIsAlwaysAllowed() {
        val player = buyer(setOf(allowedEqid))
        assertTrue(ScenarioPurchaseList.allows(player, -1))
        assertTrue(ScenarioPurchaseList.allows(player, 0))
    }

    /** The purchase itself is refused, not merely hidden -- `Player.buyUnit` is the one function
     *  every purchase passes through, including a replayed multiplayer order. */
    @Test
    fun buyingSomethingOffTheListIsRefused() {
        val player = buyer(setOf(allowedEqid))
        assertTrue(player.buyUnit(allowedEqid, -1))
        val before = player.prestige
        assertFalse(player.buyUnit(forbiddenEqid, -1))
        assertEquals(before, player.prestige, "and a refused purchase costs nothing")
    }

    /** A transport is attached at purchase time, so a whitelist that named the unit but not its
     *  prime mover would otherwise be walked around. */
    @Test
    fun anUnlistedTransportCannotRideInOnAListedUnit() {
        val player = buyer(setOf(allowedEqid, allowedTransportEqid))
        assertTrue(player.buyUnit(allowedEqid, allowedTransportEqid))
        assertFalse(player.buyUnit(allowedEqid, forbiddenTransportEqid))
    }

    /** *"available to the player to buy new units **or upgrade existing ones**"* -- both halves of
     *  the Suite's own sentence, and the difference between this and `Can't Buy`. */
    @Test
    fun upgradingIntoSomethingOffTheListIsRefusedToo() {
        val player = buyer(setOf(allowedEqid))
        assertTrue(player.buyUnit(allowedEqid, -1))
        val unit = player.getCoreUnitList().last()
        assertFalse(player.upgradeUnit(unit, forbiddenEqid, -1))
        assertEquals(allowedEqid, unit.eqid, "the formation kept its equipment")
    }
}
