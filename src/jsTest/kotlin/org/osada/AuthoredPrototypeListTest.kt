package org.osada

import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.scenario.Scenario
import org.osada.scenario.getAwardPrototype
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** OG's `.xproto` lists (`<map protolist>`): the brilliant-victory award comes from the author's list. */
class AuthoredPrototypeListTest {
    @BeforeTest
    fun setUp() {
        js("if (typeof window.scenariolist === 'undefined') { window.scenariolist = []; }")
        Equipment.putEquipment(OWN, EquipmentData().apply { country = PLAYER_COUNTRY })
        Equipment.putEquipment(ENEMY, EquipmentData().apply { country = PLAYER_COUNTRY + 1 })
    }

    @Test
    fun drawsOnlyFromTheListEntriesOfThePlayersCountry() {
        val scenario = Scenario("t.xml").apply { authoredPrototypes = listOf(ENEMY, OWN, ENEMY) }
        repeat(20) { assertEquals(OWN, scenario.getAwardPrototype(PLAYER_COUNTRY)) }
    }

    /** `volarm` after the side flip: every listed tank is the enemy's, so the list is inert and the
     *  date-window draw runs instead. There is no dated equipment here, so that draw finds none. */
    @Test
    fun aListWithNothingOfThePlayersFallsBackToTheDateDraw() {
        val scenario = Scenario("t.xml").apply { authoredPrototypes = listOf(ENEMY) }
        assertEquals(-1, scenario.getAwardPrototype(PLAYER_COUNTRY))
    }

    private companion object {
        const val OWN = 990_001
        const val ENEMY = 990_002
        const val PLAYER_COUNTRY = 77
    }
}
