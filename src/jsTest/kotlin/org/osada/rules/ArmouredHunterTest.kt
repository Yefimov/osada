package org.osada.rules

import org.osada.LeaderType
import org.osada.UnitType
import org.osada.hero.HeroTraitCatalog
import org.osada.model.GameUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Armoured Hunter (owner, 2026-10-08): +1 initiative against armoured targets, nothing else. */
class ArmouredHunterTest : OgRulesTestHarness() {
    @BeforeTest
    fun setup() = installTestWorld()

    @AfterTest
    fun teardown() = clearTestWorld()

    @Test
    fun plusOneInitiativeOnlyAgainstArmour() {
        val hunter = GameUnit(riflemanEqid).apply { leader = LeaderType.ARMOURED_HUNTER.value }
        val plain = GameUnit(riflemanEqid)

        assertEquals(1, LeaderInitiative.armouredHunterBonus(hunter, UnitType.HARD.value))
        assertEquals(0, LeaderInitiative.armouredHunterBonus(hunter, UnitType.SOFT.value))
        assertEquals(0, LeaderInitiative.armouredHunterBonus(plain, UnitType.HARD.value))
    }

    @Test
    fun theTankPromotionGrantsItAndTheAntiTankOneGrantsTankKiller() {
        val byId = HeroTraitCatalog.ALL.associateBy { it.id }

        assertEquals(LeaderType.ARMOURED_HUNTER, byId.getValue("armor_hunter").legacyTrait)
        assertEquals(LeaderType.TANK_KILLER, byId.getValue("tank_killer").legacyTrait)
    }
}
