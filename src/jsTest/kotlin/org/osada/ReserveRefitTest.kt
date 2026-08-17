package org.osada

import org.osada.hero.HeroCampaign
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.ReserveRefit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The paid reserve refit that replaced `Player.setPlayerToHQ`'s free end-of-scenario restore
 * (2026-08-01). Strength is priced at the same per-point rate reinforcing uses in battle; ammo and
 * fuel are free; a unit in the tray refits at the full city rate.
 */
class ReserveRefitTest {
    // cost 10 * CURRENCY_MULTIPLIER 12 / 10 strength points = 12 prestige per point.
    private val costPerPoint = 10 * CURRENCY_MULTIPLIER / 10

    @BeforeTest
    fun putTestEquipment() {
        Equipment.putEquipment(
            EQID,
            EquipmentData().apply {
                name = "Test Rifles"
                cost = 10
                ammo = 6
                fuel = 4
                movpoints = 3
            },
        )
    }

    @AfterTest
    fun cleanup() {
        HeroCampaign.reset()
        GameHolder.instance = null
    }

    private fun player(prestige: Int) =
        Player().apply {
            id = 0
            this.prestige = prestige
        }

    private fun reserveUnit(
        owner: Player,
        strength: Int,
        ammo: Int = 6,
        fuel: Int = 4,
    ) = GameUnit(EQID).apply {
        this.owner = owner.id
        player = owner
        this.strength = strength
        this.ammo = ammo
        this.fuel = fuel
        isDeployed = false
        owner.addCoreUnit(this)
    }

    @Test
    fun aFullyReadyUnitNeedsNoRefitAndIsNotCharged() {
        val owner = player(1000)
        reserveUnit(owner, strength = 10)

        assertFalse(ReserveRefit.quote(owner.getCoreUnitList()[0]).isNeeded)
        assertEquals(0, ReserveRefit.refitAll(owner).prestigeSpent)
        assertEquals(1000, owner.prestige, "an army already at full readiness costs nothing")
    }

    @Test
    fun strengthIsChargedAtTheInBattleReinforcementRate() {
        val owner = player(1000)
        val unit = reserveUnit(owner, strength = 4)

        val quote = ReserveRefit.quote(unit)
        assertEquals(6, quote.strengthPoints)
        assertEquals(6 * costPerPoint, quote.strengthCost)

        ReserveRefit.refit(owner, unit)
        assertEquals(10, unit.strength)
        assertEquals(1000 - 6 * costPerPoint, owner.prestige)
    }

    @Test
    fun ammoAndFuelAreRestoredWithoutCharge() {
        val owner = player(0)
        val unit = reserveUnit(owner, strength = 10, ammo = 1, fuel = 0)

        assertTrue(ReserveRefit.quote(unit).isNeeded, "an empty magazine is a reason to refit")
        val applied = ReserveRefit.refit(owner, unit)

        assertEquals(6, unit.ammo)
        assertEquals(4, unit.fuel)
        assertEquals(0, applied.strengthCost)
        assertEquals(0, owner.prestige, "supply is free even with an empty treasury")
    }

    @Test
    fun aPartiallyAffordableRefitBuysWhatItCanRatherThanRefusing() {
        val owner = player(costPerPoint * 2)
        val unit = reserveUnit(owner, strength = 3)

        val applied = ReserveRefit.refit(owner, unit)

        assertEquals(2, applied.strengthPoints)
        assertEquals(5, unit.strength)
        assertEquals(0, owner.prestige)
    }

    @Test
    fun refitAllSpendsOnTheWeakestFormationsFirst() {
        val owner = player(costPerPoint * 3)
        val strong = reserveUnit(owner, strength = 9)
        val weak = reserveUnit(owner, strength = 2)

        val summary = ReserveRefit.refitAll(owner)

        assertEquals(5, weak.strength, "the formation closest to destruction is restored first")
        assertEquals(9, strong.strength, "the nearly-whole formation waits for the next battle's budget")
        assertEquals(3, summary.strengthRestored)
        assertEquals(costPerPoint * 3, summary.prestigeSpent)
        assertTrue(summary.unitsUnaffordable > 0, "the shortfall has to be reportable to the player")
    }

    @Test
    fun aDeployedUnitIsNotRefittableFromTheTray() {
        val owner = player(1000)
        val unit = reserveUnit(owner, strength = 3)
        unit.isDeployed = true

        assertEquals(emptyList(), ReserveRefit.refittable(owner))
        assertEquals(0, ReserveRefit.refitAll(owner).prestigeSpent)
        assertEquals(3, unit.strength, "a unit on the map uses the in-battle rules, not this one")
    }

    /** The flags `GameMap.isInitialDeploymentWindow` reads. A unit refitted at HQ has not acted:
     *  marking it as having done so would slam the commander-transfer window shut on deployment. */
    @Test
    fun refittingDoesNotConsumeTheUnitsTurn() {
        val owner = player(1000)
        val unit = reserveUnit(owner, strength = 1, ammo = 0, fuel = 0)

        ReserveRefit.refit(owner, unit)

        assertFalse(unit.hasMoved)
        assertFalse(unit.hasFired)
        assertFalse(unit.hasResupplied)
    }

    /** `setPlayerToHQ` used to hand out this refit for free, which is what made every authored
     *  `resupply` campaign effect a no-op. Attrition has to survive the scenario boundary. */
    @Test
    fun endOfScenarioNoLongerRestoresStrengthOrSupply() {
        val owner = player(1000)
        val unit = reserveUnit(owner, strength = 3, ammo = 1, fuel = 1)
        unit.isDeployed = true

        owner.setPlayerToHQ()

        assertEquals(3, unit.strength)
        assertEquals(1, unit.ammo)
        assertEquals(1, unit.fuel)
        assertFalse(unit.isDeployed, "the unit still returns to the tray ready to act")
        assertFalse(unit.hasMoved)
    }

    private companion object {
        const val EQID = 9101
    }
}
