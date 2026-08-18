package org.osada.rules

import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.reinforce
import org.osada.model.resetEquipment
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RULESET_SCHEMA_VERSION
import org.osada.rules.ruleset.RuleKey
import org.osada.rules.ruleset.RulesetDefaults
import org.osada.rules.ruleset.RulesetResolver
import org.osada.rules.ruleset.RulesetSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The replacement-experience rule decided by the owner on 2026-08-18
 * (`docs/player-comfort-roadmap.md` P2 item 9).
 *
 * The arithmetic is tested separately from the ruleset gate, because the action tooltip and the
 * mutation both call [ReplacementExperience] and a divergence between preview and effect is the one
 * failure this feature must not have.
 */
class ReplacementExperienceTest {
    private val infantryEqid = 811

    @BeforeTest
    fun setUp() {
        Equipment.resetEquipment()
        Equipment.putEquipment(
            infantryEqid,
            EquipmentData().apply {
                name = "Guards Rifle Division"
                uclass = UnitClass.INFANTRY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.LEG.value
                movpoints = 3
                ammo = 6
                fuel = 0
                cost = 20
            },
        )
    }

    @AfterTest
    fun tearDown() {
        ActiveRuleset.resetForTest()
        Equipment.resetEquipment()
    }

    private fun veteran(
        strength: Int,
        experience: Int,
    ) = GameUnit(infantryEqid).apply {
        this.strength = strength
        this.experience = experience
    }

    private fun lockRule(value: Int) {
        ActiveRuleset.set(
            RulesetResolver.fromEffective(
                id = "custom-1",
                name = "Replacement test",
                source = RulesetSource.CUSTOM,
                schemaVersion = RULESET_SCHEMA_VERSION,
                effective = RulesetDefaults.OSADA + (RuleKey.REPLACEMENT_EXPERIENCE to value),
            ),
        )
    }

    /** The roadmap's own worked example, which the rules-window help also quotes. */
    @Test
    fun rebuildingAVeteranFromThreeToTenKeepsAWeightedShare() {
        assertEquals(120, ReplacementExperience.diluted(currentExperience = 400, currentStrength = 3, restored = 7))
    }

    @Test
    fun aSinglePointBarelyMovesAFullFormation() {
        // 400 x 9 / 10 = 360: still three whole bars, so combat sees no change at all.
        assertEquals(360, ReplacementExperience.diluted(400, 9, 1))
        assertEquals(3, 360 / 100, "a 360-experience formation should still read as three bars")
    }

    @Test
    fun dilutionScalesWithHowMuchOfTheFormationIsNew() {
        val heavy = ReplacementExperience.diluted(500, 2, 8)
        val light = ReplacementExperience.diluted(500, 8, 2)
        assertEquals(100, heavy)
        assertEquals(400, light)
        assertTrue(heavy < light, "rebuilding more of the formation must cost more experience")
    }

    @Test
    fun restoringNothingChangesNothing() {
        assertEquals(400, ReplacementExperience.diluted(400, 5, 0))
        assertEquals(400, ReplacementExperience.diluted(400, 5, -3))
    }

    @Test
    fun aGreenFormationHasNothingToDilute() {
        assertEquals(0, ReplacementExperience.diluted(0, 4, 6))
    }

    /** Total rather than throwing: a strength-0 formation is destroyed, not reinforceable, and the
     *  callers must not each have to prove they checked. */
    @Test
    fun aDestroyedFormationDoesNotDivideByZero() {
        assertEquals(0, ReplacementExperience.diluted(400, 0, 10))
    }

    @Test
    fun theRuleIsOnByDefault() {
        lockRule(RulesetDefaults.OSADA.getValue(RuleKey.REPLACEMENT_EXPERIENCE))
        assertTrue(ReplacementExperience.dilutes())
        assertEquals(120, ReplacementExperience.afterReplacement(400, 3, 7))
    }

    /** The pre-2026-08-18 behaviour, still selectable per campaign. */
    @Test
    fun preserveKeepsExperienceCompletely() {
        lockRule(0)
        assertTrue(!ReplacementExperience.dilutes())
        assertEquals(400, ReplacementExperience.afterReplacement(400, 3, 7))
    }

    // ---- the mutation itself ------------------------------------------------------------------

    @Test
    fun reinforcingActuallyWritesTheDilutedExperience() {
        lockRule(1)
        val unit = veteran(strength = 3, experience = 400)
        unit.reinforce(7, overStrength = false)
        assertEquals(10, unit.strength)
        assertEquals(120, unit.experience)
    }

    @Test
    fun reinforcingUnderPreserveLeavesExperienceAlone() {
        lockRule(0)
        val unit = veteran(strength = 3, experience = 400)
        unit.reinforce(7, overStrength = false)
        assertEquals(10, unit.strength)
        assertEquals(400, unit.experience)
    }

    /**
     * Overstrength is exempt on purpose: it is priced at a premium, gated behind a minimum
     * experience and capped by a ceiling that scales with experience, so diluting it would let a
     * formation spend the veterancy that granted the purchase.
     */
    @Test
    fun overstrengthNeverDilutes() {
        lockRule(1)
        val unit = veteran(strength = 10, experience = 400)
        unit.reinforce(1, overStrength = true)
        assertEquals(11, unit.strength)
        assertEquals(400, unit.experience)
        assertTrue(unit.hasOverstrength)
    }

    /** The preview the tooltip shows and the value the command writes come from one function, so
     *  this asserts they cannot drift rather than asserting a number twice. */
    @Test
    fun thePreviewMatchesWhatReinforcingWrites() {
        lockRule(1)
        val unit = veteran(strength = 4, experience = 550)
        val previewed = ReplacementExperience.afterReplacement(unit.experience, unit.strength, 6)
        unit.reinforce(6, overStrength = false)
        assertEquals(previewed, unit.experience)
    }
}
