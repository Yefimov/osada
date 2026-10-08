package org.osada.rules

import org.osada.UnitClass
import org.osada.UnitType
import org.osada.model.EfileConfig
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.resetEquipment
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Air Defense (slot 2) is *"Increase AA/AD"* on the author's attachment page: air attack and air
 * defence (`docs/og/mechanics-coverage.md` A1). Both halves, for both sides of the exchange.
 */
class AirDefenceAttachmentTest {
    private val flakEqid = 530
    private val bomberEqid = 531
    private val bonus = 2

    @BeforeTest
    fun setup() {
        Equipment.resetEquipment()
        Equipment.putEquipment(
            flakEqid,
            EquipmentData().apply {
                uclass = UnitClass.INFANTRY.value
                target = UnitType.SOFT.value
                airatk = 4
                airdef = 5
            },
        )
        Equipment.putEquipment(
            bomberEqid,
            EquipmentData().apply {
                uclass = UnitClass.TACTICAL_BOMBER.value
                target = UnitType.AIR.value
                softatk = 8
                airdef = 6
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
                            Attachments.SLOT_AIR_DEFENSE to
                                EfileConfig.AttachmentSlot(
                                    name = "Air Defense",
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

    private fun context(
        attacker: GameUnit,
        defender: GameUnit,
    ) = AttackCalculation.CombatContext(
        attackerData = attacker.unitData(true),
        defenderData = defender.unitData(true),
        attackerTarget = attacker.unitData(true).target,
        defenderTarget = defender.unitData(true).target,
        aTerrain = 0,
        dTerrain = 0,
        attackerHex = Hex(0, 0),
        defenderHex = Hex(0, 1),
        distance = 1,
        entrenchmentIntact = true,
        attackerEntrenchmentIntact = true,
    )

    private fun stats(
        attacker: GameUnit,
        defender: GameUnit,
    ): AttackCalculation.CombatStats {
        val context = context(attacker, defender)
        val stats = AttackCalculation.resolveCrossIndexedStats(context)
        AttackCalculation.applyAttachmentBonuses(stats, attacker, defender, context)
        return stats
    }

    @Test
    fun aFittedUnitBombedFromTheAirDefendsBetterAsWellAsFiringBack() {
        val flak = GameUnit(flakEqid).apply { authoredAttachmentIds = listOf(Attachments.SLOT_AIR_DEFENSE) }
        val bomber = GameUnit(bomberEqid)

        val result = stats(bomber, flak)

        assertEquals(4 + bonus, result.defenderAttack, "the AA half")
        assertEquals(5 + bonus, result.defenderDefense, "the AD half -- missing before 2026-10-08")
        assertEquals(0, result.attackerDefense, "the bomber defends with its own grounddef and gains nothing")
    }

    @Test
    fun aFittedUnitFiringAtAircraftAlsoDefendsBetterAgainstTheReply() {
        val flak = GameUnit(flakEqid).apply { authoredAttachmentIds = listOf(Attachments.SLOT_AIR_DEFENSE) }
        val bomber = GameUnit(bomberEqid)

        val result = stats(flak, bomber)

        assertEquals(4 + bonus, result.attackerAttack)
        assertEquals(5 + bonus, result.attackerDefense)
    }

    @Test
    fun noBonusAgainstAGroundOpponent() {
        val flak = GameUnit(flakEqid).apply { authoredAttachmentIds = listOf(Attachments.SLOT_AIR_DEFENSE) }
        val other = GameUnit(flakEqid)

        val result = stats(other, flak)

        assertEquals(0, result.defenderAttack, "softatk 0, unchanged")
        assertEquals(0, result.defenderDefense, "grounddef 0, unchanged")
    }
}
