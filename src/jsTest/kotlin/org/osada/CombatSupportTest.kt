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
        // Carries OG's `Combat Support` ATTRIBUTE (attr bit 16), which is what grants the role.
        // Its name is deliberately NOT HQ-like: sourcing the capability from the name missed 85% of
        // the records that actually have the bit (see UnitCapabilities.hasCombatSupport).
        Equipment.putEquipment(
            2,
            EquipmentData().apply {
                name = "04 General Staff"
                uclass = UnitClass.INFANTRY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.LEG.value
                attr = COMBAT_SUPPORT_ATTR
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

    /**
     * OG's `Support Fire` (`attr` bit 12) is a **TOGGLE that reverses the class default**, not a
     * grant — `OG_ABILITY_AUDIT.md` §2, confirmed by the data: all three toggles that survive the
     * importer are rare on the class they default to (`Recon Skill` is on 10 of 2,880 Recon records).
     *
     * So the rule is `classDefault xor bit`. These four cases are the whole truth table.
     */
    @Test
    fun supportFireIsTheClassDefaultToggledByTheAttribute() {
        fun eq(
            cls: UnitClass,
            flagged: Boolean,
        ) = EquipmentData().apply {
            uclass = cls.value
            attr = if (flagged) SUPPORT_FIRE_ATTR else 0
        }

        assertTrue(
            UnitCapabilities.hasSupportFire(eq(UnitClass.ARTILLERY, flagged = false)),
            "artillery defaults to fire support — 87% of OG artillery, unflagged",
        )
        assertFalse(
            UnitCapabilities.hasSupportFire(eq(UnitClass.ARTILLERY, flagged = true)),
            "the flag REVERSES it: the 700 artillery records OG switches off",
        )
        assertFalse(
            UnitCapabilities.hasSupportFire(eq(UnitClass.ANTI_TANK, flagged = false)),
            "anti-tank has no default fire support",
        )
        assertTrue(
            UnitCapabilities.hasSupportFire(eq(UnitClass.ANTI_TANK, flagged = true)),
            "and the flag switches it on — 74% of OG anti-tank",
        )
    }

    /** BASEKORP's `Fort` (`E 335`), the record that decoded both bits: it reports exactly
     *  `Support Fire` + `NoSurrender`, and a fortification does not default to fire support, so the
     *  toggle grants it. */
    @Test
    fun fortE335GetsSupportFireFromItsToggle() {
        val fort =
            EquipmentData().apply {
                uclass = UnitClass.FORTIFICATION.value
                attr = SUPPORT_FIRE_ATTR or NO_SURRENDER_ATTR
            }

        assertTrue(UnitCapabilities.hasSupportFire(fort))
    }

    /** The two bits must not bleed into each other — 12 is Support Fire, 23 is NoSurrender, and a
     *  record carrying only one must not answer for the other. */
    @Test
    fun noSurrenderAloneDoesNotToggleSupportFire() {
        val coastalBattery =
            EquipmentData().apply {
                uclass = UnitClass.FORTIFICATION.value
                attr = NO_SURRENDER_ATTR
            }

        assertFalse(
            UnitCapabilities.hasSupportFire(coastalBattery),
            "`8\" Coastal Battery` carries NoSurrender without Support Fire, and forts do not default to it",
        )
    }

    /**
     * `Combat Support` is the ATTRIBUTE (bit 16), never the name. Confirmed by BASEKORP `43 HQ`
     * (`E 3814`), whose sole enabled ability is `Combat Support` and whose `attr` is exactly 65536.
     *
     * The old name test (`isHeadquarters`) matched 306 of the 56,970 shipped records and agreed on
     * 290, missing **1,355** that carry the bit — `04 General Staff`, `70 Estado Mayor`,
     * `21 Alpini`, `24 KOP`, commissars, squadron leaders — and inventing 16 that do not, five of
     * them Chinese `HQ-x` surface-to-air missiles. §1 of `OG_ABILITY_AUDIT.md`: *never infer a
     * layer from a name.* The predicate itself was deleted 2026-08-23, once the equipment card's
     * "headquarters" note (its last reader) moved onto the bit as well; this test now pins the
     * whole surface, not just the combat half.
     */
    @Test
    fun combatSupportComesFromTheAttributeNotTheName() {
        val (map, player) = mapAndPlayer()
        Equipment.putEquipment(
            3,
            EquipmentData().apply {
                name = "Divisional HQ"
                uclass = UnitClass.INFANTRY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.LEG.value
            },
        )
        val recipient = unit(1, player, experience = 50)
        val hqNamedButUnflagged = unit(3, player, experience = 250)
        place(map, recipient, 1, 1)
        place(map, hqNamedButUnflagged, 1, 2)

        assertEquals(
            0,
            UnitCapabilities.combatSupportBars(listOf(recipient, hqNamedButUnflagged), recipient),
            "an HQ-sounding name without the attribute lends nothing",
        )
        assertFalse(
            UnitCapabilities.grantsCombatSupport(Equipment.getEquipment(3)!!),
            "and the record-level predicate the badge and the equipment card both read agrees",
        )
    }

    private companion object {
        /** `Equipment.attr` bit 12 — OG's `Support Fire`. */
        const val SUPPORT_FIRE_ATTR = 4096

        /** `Equipment.attr` bit 16 — OG's `Combat Support`. */
        const val COMBAT_SUPPORT_ATTR = 65536

        /** `Equipment.attr` bit 23 — OG's `NoSurrender`. */
        const val NO_SURRENDER_ATTR = 8388608
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
