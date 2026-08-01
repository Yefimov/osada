package org.osada

import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.awardPrestige
import org.osada.model.resetEquipment
import org.osada.model.synchronizeStalinRegime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StalinRegimeTest {
    @BeforeTest
    fun setUp() {
        Equipment.resetEquipment()
        uiSettings.stalinRegime = false
        Equipment.putEquipment(
            1,
            EquipmentData().apply {
                eqid = 1
                name = "Test tank"
                uclass = UnitClass.TANK.value
                target = UnitType.HARD.value
                movmethod = MovMethod.TRACKED.value
                cost = 42
                hardatk = 7
                softatk = 5
                airatk = 2
                navalatk = 1
                grounddef = 6
                airdef = 3
                closedef = 4
                rangedefmod = 2
                initiative = 8
                movpoints = 6
                spotrange = 3
                gunrange = 2
                ammo = 9
                fuel = 40
            },
        )
    }

    @AfterTest
    fun tearDown() {
        uiSettings.stalinRegime = false
    }

    @Test
    fun boostMultipliesCapabilitiesAndMutableResourcesButNotMetadataOrCost() {
        val player = Player().apply { type = PlayerType.HUMAN_LOCAL }
        val unit =
            GameUnit(1).apply {
                this.player = player
                moveLeft = 6
                ammo = 9
                fuel = 40
            }

        unit.synchronizeStalinRegime(true)
        val boosted = unit.unitData()

        assertTrue(unit.stalinRegimeBoosted)
        assertEquals(70, boosted.hardatk)
        assertEquals(50, boosted.softatk)
        assertEquals(60, boosted.grounddef)
        assertEquals(80, boosted.initiative)
        assertEquals(60, boosted.movpoints)
        // spotrange is NOT multiplied: it is the fog-of-war input, not a strength stat. At x10
        // every unit saw the whole map, so Stalin Regime silently doubled as "disable fog of war" —
        // and because Hex.setSpotted is a reference count, the hexes it revealed stayed revealed
        // after the mode was switched back off. Observer Mode's own noFOW toggle is the way to
        // lift the fog; these two must stay independent.
        assertEquals(3, boosted.spotrange)
        assertEquals(20, boosted.gunrange)
        assertEquals(90, boosted.ammo)
        assertEquals(400, boosted.fuel)
        assertEquals(60, unit.moveLeft)
        assertEquals(90, unit.ammo)
        assertEquals(400, unit.fuel)
        assertEquals(UnitClass.TANK.value, boosted.uclass)
        assertEquals(UnitType.HARD.value, boosted.target)
        assertEquals(MovMethod.TRACKED.value, boosted.movmethod)
        assertEquals(42, boosted.cost)

        unit.synchronizeStalinRegime(false)
        assertFalse(unit.stalinRegimeBoosted)
        assertEquals(6, unit.moveLeft)
        assertEquals(9, unit.ammo)
        assertEquals(40, unit.fuel)
        assertEquals(7, unit.unitData().hardatk)
    }

    @Test
    fun positivePrestigeIncomeIsMultipliedOnlyForLocalHumanPlayer() {
        uiSettings.stalinRegime = true
        val human = Player().apply { type = PlayerType.HUMAN_LOCAL }
        val ai = Player().apply { type = PlayerType.AI_LOCAL }

        assertEquals(250, human.awardPrestige(25))
        assertEquals(250, human.prestige)
        assertEquals(25, ai.awardPrestige(25))
        assertEquals(25, ai.prestige)
        assertEquals(-40, human.awardPrestige(-40))
        assertEquals(210, human.prestige)
    }

    @Test
    fun settingFlagDefaultsOffAndCanBePersistedByStableKey() {
        assertFalse(uiSettings.getFlag("stalinRegime"))
        uiSettings.setFlag("stalinRegime", true)

        assertTrue(uiSettings.stalinRegime)
        assertTrue(JSON.stringify(uiSettings).contains("\"stalinRegime\":true"))
    }
}
