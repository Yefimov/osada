package org.osada.ui

import kotlinx.browser.document
import org.osada.LeaderType
import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.resetEquipment
import org.w3c.dom.HTMLElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The enemy card draws the enemy's commander the way the player's own card does: the
 * `.uc-commander-icon` cap plus every trait, with each trait's effect in the tooltip. It used to be a
 * bare "LEADER" chip naming only the first trait.
 */
class EnemyCardCommanderTest {
    private val infantryEqid = 931

    @BeforeTest
    fun setUp() {
        Equipment.resetEquipment()
        Equipment.putEquipment(
            infantryEqid,
            EquipmentData().apply {
                name = "Volksgrenadier"
                uclass = UnitClass.INFANTRY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.LEG.value
            },
        )
        val card = document.createElement("div")
        card.id = "osadaEnemyCard"
        val main = document.createElement("div")
        main.id = "ecMain"
        listOf("ecName", "ecSub", "ecStrRow", "ecStat").forEach { id ->
            main.appendChild(document.createElement("div").also { it.id = id })
        }
        card.appendChild(main)
        document.body!!.appendChild(card)
    }

    @AfterTest
    fun tearDown() {
        document.getElementById("osadaEnemyCard")?.remove()
    }

    private fun enemy(leader: LeaderType?) =
        GameUnit(infantryEqid).apply {
            flag = 8
            this.leader = leader?.value ?: -1
            player =
                Player().apply {
                    id = 1
                    side = 1
                }
        }

    private fun line(): HTMLElement? = document.getElementById("ecCommanderLine") as? HTMLElement

    @Test
    fun aLedEnemyShowsTheCommanderIconAndBothTraits() {
        CombatTransparencyPresenter.presentEnemy(enemy(LeaderType.FOREST_CAMOUFLAGE))

        val line = assertNotNull(line())
        assertTrue(line.style.display != "none", "the line is shown")
        assertNotNull(line.querySelector(".uc-commander-icon"), "the commander cap is drawn")
        // Infantry's class attribute is Tenacious Defense, so the unit has TWO traits -- the old chip
        // showed only the first.
        val tooltip = line.title.split("\n")
        assertEquals(3, tooltip.size, "a heading plus one line per trait: ${line.title}")
        assertTrue(tooltip.drop(1).all { it.contains(": ") }, "each trait line carries its effect")
        assertTrue(line.textContent!!.contains(" · "), "both trait names are on the card")
    }

    @Test
    fun anEnemyWithoutACommanderShowsNoLine() {
        CombatTransparencyPresenter.presentEnemy(enemy(null))
        assertEquals("none", line()?.style?.display)
    }

    @Test
    fun theLineIsClearedWhenTheCardMovesToAnUnledUnit() {
        CombatTransparencyPresenter.presentEnemy(enemy(LeaderType.OVERWATCH))
        CombatTransparencyPresenter.presentEnemy(enemy(null))
        assertEquals("none", line()?.style?.display)
        assertEquals("", line()?.textContent)
    }
}
