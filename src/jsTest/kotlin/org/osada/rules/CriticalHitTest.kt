package org.osada.rules

import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.model.EfileConfig
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.rules.ruleset.RuleKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OG's `critical_hit`, built 2026-08-28 (`docs/og-fidelity-plan.md` §AA.6) — the complete combat
 * formula `eqp-lxf` has been setting to 2 all along while nothing read it.
 *
 * Every number here comes from the quoted formula. The one place this project departs from the
 * literal text — reading *"If C(Firing) < Dice(1,100)"* as inverted — is asserted explicitly, so a
 * correction fails loudly rather than drifting.
 */
class CriticalHitTest : OgRulesTestHarness() {
    private val battleshipEqid = 1300
    private val destroyerEqid = 1301
    private val submarineEqid = 1302

    @BeforeTest
    fun setup() {
        installTestWorld()
        Equipment.putEquipment(
            battleshipEqid,
            EquipmentData().apply {
                name = "Battleship"
                uclass = UnitClass.BATTLESHIP.value
                movmethod = MovMethod.DEEP_NAVAL.value
                navalatk = 20
                grounddef = 10
                airdef = 4
                ammo = 8
            },
        )
        Equipment.putEquipment(
            destroyerEqid,
            EquipmentData().apply {
                name = "Destroyer"
                uclass = UnitClass.DESTROYER.value
                movmethod = MovMethod.DEEP_NAVAL.value
                navalatk = 8
                grounddef = 4
                airdef = 6
                ammo = 6
            },
        )
        Equipment.putEquipment(
            submarineEqid,
            EquipmentData().apply {
                name = "Submarine"
                uclass = UnitClass.SUBMARINE.value
                movmethod = MovMethod.DEEP_NAVAL.value
                navalatk = 12
                grounddef = 3
                airdef = 1
                ammo = 4
            },
        )
    }

    @AfterTest
    fun teardown() {
        clearTestWorld()
    }

    @Test
    fun theRuleIsOffUntilTheRulesetAsksForIt() {
        EfileConfig.setForTest(intKeyMap = mapOf("critical_hit" to 2))
        ruleset(RuleKey.NAVAL_CRITICAL_HITS to 0)
        val map = world()
        val firing = place(map, battleshipEqid, 3, 3, 0)
        val target = place(map, destroyerEqid, 3, 4, 1)

        // `eqp-lxf` sets 2, and the shipped game still must not sink ships outright.
        assertEquals(0, CriticalHit.percentFor(firing, target))
    }

    @Test
    fun anEfileThatSetsNothingNeverProducesOne() {
        EfileConfig.setForTest(intKeyMap = emptyMap())
        ruleset(RuleKey.NAVAL_CRITICAL_HITS to 1)
        val map = world()
        val firing = place(map, battleshipEqid, 3, 3, 0)
        val target = place(map, destroyerEqid, 3, 4, 1)

        assertEquals(0, CriticalHit.percentFor(firing, target), "the key says 'honour N', and N is absent")
    }

    @Test
    fun theFormulaIsOgsOwnArithmetic() {
        EfileConfig.setForTest(intKeyMap = mapOf("critical_hit" to 2))
        ruleset(RuleKey.NAVAL_CRITICAL_HITS to 1)
        val map = world()
        val firing = place(map, battleshipEqid, 3, 3, 0)
        val target = place(map, destroyerEqid, 3, 4, 1)
        firing.strength = 10
        firing.experience = 0
        target.strength = 10
        target.experience = 0

        // ( NA 20 x (1+0 bars) x SP 10 x N 2  -  GD 4 x (1+0) x SP 10 x N 2 ) / 30
        //   = (400 - 80) / 30 = 10
        assertEquals(10, CriticalHit.percentFor(firing, target))
    }

    @Test
    fun barsAndStrengthBothCount() {
        EfileConfig.setForTest(intKeyMap = mapOf("critical_hit" to 2))
        ruleset(RuleKey.NAVAL_CRITICAL_HITS to 1)
        val map = world()
        val firing = place(map, battleshipEqid, 3, 3, 0)
        val target = place(map, destroyerEqid, 3, 4, 1)
        firing.strength = 10
        firing.experience = 200
        target.strength = 5
        target.experience = 0

        // ( 20 x 3 x 10 x 2 - 4 x 1 x 5 x 2 ) / 30 = (1200 - 40) / 30 = 38
        assertEquals(38, CriticalHit.percentFor(firing, target))
    }

    @Test
    fun theChanceIsCappedAtSeventyFive() {
        EfileConfig.setForTest(intKeyMap = mapOf("critical_hit" to 20))
        ruleset(RuleKey.NAVAL_CRITICAL_HITS to 1)
        val map = world()
        val firing = place(map, battleshipEqid, 3, 3, 0)
        val target = place(map, destroyerEqid, 3, 4, 1)
        firing.strength = 10
        firing.experience = 500
        target.strength = 1

        assertEquals(75, CriticalHit.percentFor(firing, target), "'If C(firing) > 75 then C(firing)=75'")
    }

    @Test
    fun aSubmarineAddsTenWhenItIsTheOneFiring() {
        EfileConfig.setForTest(intKeyMap = mapOf("critical_hit" to 1))
        ruleset(RuleKey.NAVAL_CRITICAL_HITS to 1)
        val map = world()
        val boat = place(map, submarineEqid, 3, 3, 0)
        val target = place(map, destroyerEqid, 3, 4, 1)
        boat.strength = 10
        target.strength = 10

        // ( 12 x 1 x 10 x 1 - 4 x 1 x 10 x 1 ) / 30 = 80/30 = 2, plus the submarine's 10.
        assertEquals(12, CriticalHit.percentFor(boat, target))
    }

    @Test
    fun aLandTargetCanNeverBeSunk() {
        EfileConfig.setForTest(intKeyMap = mapOf("critical_hit" to 2))
        ruleset(RuleKey.NAVAL_CRITICAL_HITS to 1)
        val map = world()
        val firing = place(map, battleshipEqid, 3, 3, 0)
        val ashore = place(map, infantryEqid, 3, 4, 1)

        // The formula's own terms are naval: NA is naval attack and the outcome is "sunk".
        assertEquals(0, CriticalHit.percentFor(firing, ashore))
    }

    @Test
    fun aFiringUnitWithNoNavalAttackCanNeverProduceOne() {
        EfileConfig.setForTest(intKeyMap = mapOf("critical_hit" to 2))
        ruleset(RuleKey.NAVAL_CRITICAL_HITS to 1)
        val map = world()
        val gun = place(map, gunEqid, 3, 3, 0)
        val target = place(map, destroyerEqid, 3, 4, 1)

        assertEquals(0, CriticalHit.percentFor(gun, target))
    }

    @Test
    fun anOutmatchedFiringUnitGetsNothingRatherThanACertainty() {
        EfileConfig.setForTest(intKeyMap = mapOf("critical_hit" to 2))
        ruleset(RuleKey.NAVAL_CRITICAL_HITS to 1)
        val map = world()
        val weak = place(map, destroyerEqid, 3, 3, 0)
        val strong = place(map, battleshipEqid, 3, 4, 1)
        weak.strength = 1
        strong.strength = 10

        // This is the assertion that pins the inverted reading of "If C(Firing) < Dice(1,100)":
        // taken literally, the WEAKEST attacker would sink the strongest ship every time.
        assertEquals(0, CriticalHit.percentFor(weak, strong))
        assertTrue((0 until 50).none { CriticalHit.sinks(weak, strong) }, "and it never rolls one")
    }
}
