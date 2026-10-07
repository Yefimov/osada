package org.osada.rules

import org.osada.GameHolder
import org.osada.TerrainType
import org.osada.model.EfileConfig
import org.osada.model.EngineeringActionResult
import org.osada.model.GameUnit
import org.osada.model.beginEngineering
import org.osada.rules.ruleset.RuleKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tier 3 attachments (`docs/design/attachments.md` §4): Forward Observer, Special Munition and Fast
 * Builder, each pinned to the OG sentence it implements. Attachments are fitted straight onto the
 * unit's store ([GameUnit.purchasedAttachmentIds]) -- no campaign runs here, so that is where a
 * purchase would land.
 */
class Tier3AttachmentsTest : OgRulesTestHarness() {
    @BeforeTest
    fun setup() {
        installTestWorld()
        EfileConfig.setForTest(attachmentConfigValue = config(fastBuilderBonus = 1))
    }

    @AfterTest
    fun tearDown() = clearTestWorld()

    private fun slot(
        name: String,
        bonus: Int,
    ) = EfileConfig.AttachmentSlot(name, false, bonus, 0, 30, 25, 0)

    private fun config(fastBuilderBonus: Int) =
        EfileConfig.AttachmentConfig(
            armyCost = false,
            minFuel = 0,
            minMove = 0,
            factorDefaultPct = null,
            minCostDefault = null,
            slots =
                mapOf(
                    Attachments.SLOT_FORWARD_OBSERVER to slot("Forward Observer", FO_BONUS),
                    Attachments.SLOT_SPECIAL_MUNITION to slot("Special Munition", 2),
                    Attachments.SLOT_FAST_BUILDER to slot("Fast Builder", fastBuilderBonus),
                ),
        )

    private fun GameUnit.fit(vararg slots: Int) = apply { purchasedAttachmentIds = slots.toList() }

    /** [firing]'s attack against [target] after the attachment step -- nothing else applied. */
    private fun attackAfterAttachments(
        firing: GameUnit,
        target: GameUnit,
        units: List<GameUnit>,
    ): Int {
        val context = assertNotNull(AttackCalculation.resolveCombatContext(firing, target))
        val stats = AttackCalculation.resolveCrossIndexedStats(context)
        AttackCalculation.applyAttachmentBonuses(stats, firing, target, context, units)
        return stats.attackerAttack
    }

    // ---- Forward Observer: "for each A's friend-unit having ForwardObserver attachment" ----------

    @Test
    fun observersAdjacentToTheTargetRaiseTheGunsAttackAndStack() {
        val map = world()
        GameHolder.instance = holderFor(map)
        val gun = place(map, gunEqid, 2, 1, side = 0)
        val target = place(map, infantryEqid, 2, 4, side = 1)
        val base = attackAfterAttachments(gun, target, map.units)

        place(map, infantryEqid, 2, 3, side = 0).fit(Attachments.SLOT_FORWARD_OBSERVER)
        assertEquals(base + FO_BONUS, attackAfterAttachments(gun, target, map.units))

        place(map, infantryEqid, 3, 4, side = 0).fit(Attachments.SLOT_FORWARD_OBSERVER)
        assertEquals(base + 2 * FO_BONUS, attackAfterAttachments(gun, target, map.units), "observers stack")
    }

    @Test
    fun anObserverNotBesideTheTargetOrAnEnemyOneAddsNothing() {
        val map = world()
        GameHolder.instance = holderFor(map)
        val gun = place(map, gunEqid, 2, 1, side = 0)
        val target = place(map, infantryEqid, 2, 4, side = 1)
        val base = attackAfterAttachments(gun, target, map.units)

        place(map, infantryEqid, 2, 2, side = 0).fit(Attachments.SLOT_FORWARD_OBSERVER) // beside the gun
        place(map, infantryEqid, 2, 5, side = 1).fit(Attachments.SLOT_FORWARD_OBSERVER) // the enemy's

        assertEquals(base, attackAfterAttachments(gun, target, map.units))
    }

    /** OG: *"If A is Arty-class, AD-class or naval-unit"* -- an infantry attack is not observed. */
    @Test
    fun aDirectFireUnitIsNotObservedFor() {
        val map = world()
        GameHolder.instance = holderFor(map)
        val rifles = place(map, infantryEqid, 2, 3, side = 0)
        val target = place(map, infantryEqid, 2, 4, side = 1)
        val base = attackAfterAttachments(rifles, target, map.units)

        place(map, infantryEqid, 3, 4, side = 0).fit(Attachments.SLOT_FORWARD_OBSERVER)

        assertEquals(base, attackAfterAttachments(rifles, target, map.units))
    }

    // ---- Special Munition: "DiceToKill is reduced by attach-bonus" ---------------------------------

    /**
     * Net attack +1 against the default threshold 15: q = 1 + 21 - 15 = 7, kills =
     * (5 * 7 * 10 + 50) / 100 = 4.0. With the +2 munition the threshold is 13: q = 9, kills =
     * (5 * 9 * 10 + 50) / 100 = 5.0.
     */
    @Test
    fun specialMunitionLowersTheKillThresholdOfTheUnitFiring() {
        val map = world()
        GameHolder.instance = holderFor(map)
        val rifles = place(map, infantryEqid, 2, 3, side = 0)
        val target = place(map, infantryEqid, 2, 4, side = 1)

        assertEquals(4, CombatResolver.attackValue(11, 10, rifles, target, true))
        rifles.fit(Attachments.SLOT_SPECIAL_MUNITION)
        assertEquals(5, CombatResolver.attackValue(11, 10, rifles, target, true))
        assertEquals(4, CombatResolver.attackValue(11, 10, target, rifles, true), "the target's fire is unchanged")
    }

    // ---- Fast Builder: "Reduces 1 turn building/repairing time" ------------------------------------

    @Test
    fun fastBuilderTakesATurnOffTheBridge() {
        ruleset(RuleKey.BUILD_AND_REPAIR to 1, RuleKey.ATTACHMENTS to 1)
        val map = world(prestige = 100)
        GameHolder.instance = holderFor(map)
        map.map!![2][2].terrain = TerrainType.RIVER.value
        val sapper = place(map, sapperEqid, 2, 2, side = 0).fit(Attachments.SLOT_FAST_BUILDER)
        val shortened = EngineeringWork.BRIDGE.turns - 1

        assertEquals(shortened, EngineeringWork.BRIDGE.turnsForBuilder(sapper))
        assertEquals(EngineeringActionResult.STARTED, map.beginEngineering(sapper, EngineeringWork.BRIDGE))
        repeat(shortened) { Engineering.advanceTurn(map.map, 0, builderOwner()) }
        assertTrue(map.map!![2][2].road > 0, "the bridge is finished a turn early")
    }

    @Test
    fun fastBuilderNeverMakesAJobInstant() {
        val map = world()
        GameHolder.instance = holderFor(map)
        EfileConfig.setForTest(attachmentConfigValue = config(fastBuilderBonus = 9))
        val sapper = place(map, sapperEqid, 2, 2, side = 0).fit(Attachments.SLOT_FAST_BUILDER)

        assertEquals(1, EngineeringWork.BRIDGE.turnsForBuilder(sapper), "a construction keeps one turn")
        assertEquals(0, EngineeringWork.BLOW_BRIDGE.turnsForBuilder(sapper), "a demolition stays instant")
    }

    private companion object {
        const val FO_BONUS = 3
    }
}
