package org.osada.rules

import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.model.EfileConfig
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.move
import org.osada.model.resetEquipment
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fast Entrench (slot 8): OG's *"Increase entrench to TerrainBaseEntrench when entering an hex"*.
 * The attachment works the moment the unit arrives, not at the next turn's lifecycle snap
 * (`docs/og/mechanics-coverage.md` A2).
 */
class FastEntrenchArrivalTest {
    private val eqid = 532
    private val bonus = 3

    @BeforeTest
    fun setup() {
        Equipment.resetEquipment()
        Equipment.putEquipment(
            eqid,
            EquipmentData().apply {
                uclass = UnitClass.INFANTRY.value
                movpoints = 3
            },
        )
        EfileConfig.setForTest(
            attachmentConfigValue =
                EfileConfig.AttachmentConfig(
                    armyCost = true,
                    minFuel = 0,
                    minMove = 0,
                    factorDefaultPct = 25,
                    minCostDefault = 30,
                    slots =
                        mapOf(
                            Attachments.SLOT_FAST_ENTRENCH to
                                EfileConfig.AttachmentSlot(
                                    name = "Fast Entrench",
                                    disabled = false,
                                    bonus = bonus,
                                    penalty = -1,
                                    minCost = 30,
                                    factCost = 25,
                                    penaltyType = 2,
                                ),
                        ),
                ),
        )
    }

    @AfterTest
    fun cleanup() {
        EfileConfig.resetForTest()
        Equipment.resetEquipment()
    }

    private fun arrive(fitted: Boolean): GameUnit {
        val unit =
            GameUnit(eqid).apply {
                if (fitted) authoredAttachmentIds = listOf(Attachments.SLOT_FAST_ENTRENCH)
                hex = Hex(0, 0).apply { terrain = TerrainType.CITY.value }
                moveLeft = 3
                entrenchment = 4
            }
        unit.move(1)
        EntrenchmentDefence.onEntry(unit, unit.hex!!)
        return unit
    }

    @Test
    fun anOrdinaryUnitArrivesWithNothing() {
        assertEquals(0, arrive(fitted = false).entrenchment)
    }

    @Test
    fun aFittedUnitArrivesAtItsTerrainBaseline() {
        val unit = arrive(fitted = true)

        assertEquals(EntrenchmentDefence.terrainBaseline(unit, unit.hex!!), unit.entrenchment)
        assertTrue(unit.entrenchment >= bonus, "the terrain floor plus the attachment bonus")
    }

    @Test
    fun arrivalNeverLowersALevelAlreadyHeld() {
        val unit = arrive(fitted = true)
        unit.entrenchment = 20

        EntrenchmentDefence.onEntry(unit, unit.hex!!)

        assertEquals(20, unit.entrenchment)
    }
}
