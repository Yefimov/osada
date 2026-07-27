package org.osada

import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.Player
import org.osada.model.Transport
import org.osada.model.addPlayer
import org.osada.model.allocMap
import org.osada.model.resetEquipment
import org.osada.rules.UnitCapabilities
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Only ground combat units take hexes (`DEFERRED.md` §5.4, reversed in Open General's favour on
 * 2026-07-26). PM had no class check at all, which is how a destroyer came to "capture" the port at
 * N_Kiel without ownership ever transferring.
 */
class HexCaptureTest {
    @BeforeTest
    fun setup() {
        Equipment.resetEquipment()
        classes.forEach { (eqid, uclass) ->
            Equipment.putEquipment(
                eqid,
                EquipmentData().apply {
                    name = "eq$eqid"
                    this.uclass = uclass
                    target = UnitType.SOFT.value
                    movmethod = MovMethod.LEG.value
                },
            )
        }
    }

    @Test
    fun groundCombatClassesTakeTheHex() {
        listOf(UnitClass.INFANTRY, UnitClass.TANK, UnitClass.RECON, UnitClass.ANTI_TANK).forEach { cls ->
            val (map, player, hex) = enemyHeldHex()
            map.combatApplication.captureHex(hex, unit(eqidFor(cls), player))

            assertEquals(player.id, hex.owner, "$cls must take the hex")
            assertEquals(player.country, hex.flag, "$cls must plant its flag")
        }
    }

    @Test
    fun shipsArtilleryAndAircraftOccupyButNeverCapture() {
        listOf(
            UnitClass.DESTROYER,
            UnitClass.BATTLESHIP,
            UnitClass.NAVAL_TRANSPORT,
            UnitClass.ARTILLERY,
            UnitClass.AIR_DEFENCE,
            UnitClass.FIGHTER,
            UnitClass.GROUND_TRANSPORT,
            UnitClass.FORTIFICATION,
        ).forEach { cls ->
            val (map, player, hex) = enemyHeldHex()
            val result = map.combatApplication.captureHex(hex, unit(eqidFor(cls), player))

            assertEquals(ENEMY_ID, hex.owner, "$cls must not flip ownership")
            assertEquals(ENEMY_COUNTRY, hex.flag, "$cls must not plant a flag")
            assertEquals(false, result["isCapture"], "$cls must not report a capture")
        }
    }

    /** A victory hex a ship "took" would otherwise have ended the scenario — the N_Kiel case. */
    @Test
    fun aShipCannotWinTheScenarioOnAVictoryHex() {
        val (map, player, hex) = enemyHeldHex()
        hex.victorySide = 1

        val result = map.combatApplication.captureHex(hex, unit(eqidFor(UnitClass.DESTROYER), player))

        assertEquals(1, hex.victorySide, "the objective must stay in enemy hands")
        assertEquals(false, result["isWin"])
    }

    /**
     * `applyHexCapture` reads `unitData(true)` — the REAL unit, not whatever it is riding in. A
     * rifle company that drives into a town in a truck is still a rifle company.
     */
    @Test
    fun mountedInfantryStillCaptures() {
        val (map, player, hex) = enemyHeldHex()
        val infantry =
            unit(eqidFor(UnitClass.INFANTRY), player).apply {
                isMounted = true
                transport = Transport(eqidFor(UnitClass.GROUND_TRANSPORT))
            }

        map.combatApplication.captureHex(hex, infantry)

        assertEquals(player.id, hex.owner, "mounting must not remove the ability to capture")
    }

    @Test
    fun theCapturingClassSetIsTheOneTheRuleAdvertises() {
        assertTrue(UnitCapabilities.canCaptureHex(EquipmentData().apply { uclass = UnitClass.INFANTRY.value }))
        assertFalse(UnitCapabilities.canCaptureHex(EquipmentData().apply { uclass = UnitClass.DESTROYER.value }))
        assertFalse(UnitCapabilities.canCaptureHex(EquipmentData().apply { uclass = UnitClass.ARTILLERY.value }))
    }

    // ------------------------------------------------------------------ fixtures

    private companion object {
        const val ENEMY_ID = 1
        const val ENEMY_COUNTRY = 7

        val classes: List<Pair<Int, Int>> =
            UnitClass.entries.map { it.value + EQID_BASE to it.value }

        const val EQID_BASE = 100
    }

    private fun eqidFor(cls: UnitClass): Int = cls.value + EQID_BASE

    /** A 4x4 map whose (1,1) hex is owned and flagged by the enemy side. */
    private fun enemyHeldHex(): Triple<GameMap, Player, Hex> {
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
                country = 3
            }
        val enemy =
            Player().apply {
                id = ENEMY_ID
                side = 1
                country = ENEMY_COUNTRY
            }
        map.addPlayer(player)
        map.addPlayer(enemy)
        val hex =
            map.map!![1][1].apply {
                owner = ENEMY_ID
                flag = ENEMY_COUNTRY
            }
        return Triple(map, player, hex)
    }

    private fun unit(
        eqid: Int,
        player: Player,
    ): GameUnit =
        GameUnit(eqid).apply {
            owner = player.id
            this.player = player
        }
}
