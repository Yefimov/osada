package org.osada

import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.addPlayer
import org.osada.model.allocMap
import org.osada.model.resetEquipment
import org.osada.rules.UnitCapabilities
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CombatSupportTest {
    @BeforeTest
    fun setup() {
        Equipment.resetEquipment()
        Equipment.putEquipment(
            1,
            EquipmentData().apply {
                name = "Regular Infantry"
                uclass = UnitClass.INFANTRY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.LEG.value
            },
        )
        Equipment.putEquipment(
            2,
            EquipmentData().apply {
                name = "Headquarters"
                uclass = UnitClass.INFANTRY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.LEG.value
            },
        )
    }

    @Test
    fun adjacentHeadquartersLendAndStackExperienceBars() {
        val (map, player) = mapAndPlayer()
        val recipient = unit(1, player, experience = 50)
        val firstHq = unit(2, player, experience = 250)
        val secondHq = unit(2, player, experience = 390)
        place(map, recipient, 1, 1)
        place(map, firstHq, 1, 2)
        place(map, secondHq, 0, 1)

        assertEquals(5, UnitCapabilities.combatSupportBars(listOf(recipient, firstHq, secondHq), recipient))
    }

    @Test
    fun distantAndEnemyHeadquartersDoNotSupport() {
        val (map, player) = mapAndPlayer()
        val enemy =
            Player().apply {
                id = 1
                side = 1
                country = 1
            }
        map.addPlayer(enemy)
        val recipient = unit(1, player, experience = 0)
        val distant = unit(2, player, experience = 500)
        val enemyHq = unit(2, enemy, experience = 500)
        place(map, recipient, 1, 1)
        place(map, distant, 3, 3)
        place(map, enemyHq, 1, 2)

        assertEquals(0, UnitCapabilities.combatSupportBars(listOf(recipient, distant, enemyHq), recipient))
    }

    @Test
    fun intrinsicClassCapabilitiesComeFromTheClassNotTheName() {
        val recon = EquipmentData().apply { uclass = UnitClass.RECON.value }
        val tank = EquipmentData().apply { uclass = UnitClass.TANK.value }
        val depot = EquipmentData().apply { name = "Supply Depot" }

        assertTrue(UnitCapabilities.hasPhasedMovement(recon))
        assertTrue(UnitCapabilities.canOverrun(tank))
        assertFalse(UnitCapabilities.hasPhasedMovement(depot))
        assertFalse(UnitCapabilities.canOverrun(depot))
    }

    private fun mapAndPlayer(): Pair<GameMap, Player> {
        val map =
            GameMap().apply {
                rows = 4
                cols = 4
                allocMap()
            }
        val player =
            Player().apply {
                id = 0
                side = 0
                country = 0
            }
        map.addPlayer(player)
        return map to player
    }

    private fun unit(
        eqid: Int,
        player: Player,
        experience: Int,
    ): GameUnit =
        GameUnit(eqid).apply {
            owner = player.id
            this.player = player
            this.experience = experience
        }

    private fun place(
        map: GameMap,
        unit: GameUnit,
        row: Int,
        col: Int,
    ) {
        map.map
            ?.get(row)
            ?.get(col)
            ?.setUnit(unit)
    }
}
