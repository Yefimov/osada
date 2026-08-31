package org.osada.rules

import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.model.EfileConfig
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.ReserveRefit
import org.osada.rules.ruleset.RuleKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OG's `elite_cost`, and the dominated action it leaves behind when nothing reads it.
 *
 * `eqp-gce` is the only shipped efile that authors `green = 1`, and it prices the pair
 * `elite_cost=133` / `green_cost=100` / `green_exp=50`. With `elite_cost` unread the two actions
 * cost the same and the cheap one diluted less, so nobody would ever take the expensive one. These
 * tests pin both halves of the fix: the price key is read, and the ordinary replacement stops
 * diluting exactly when a green alternative exists to do the diluting instead.
 */
class EliteReplacementsTest : OgRulesTestHarness() {
    /** A record with a real cost -- the harness's own units are free, and percentages of nothing
     *  are all equal. */
    private val pricedEqid = 981

    private val efileKeys = mutableMapOf<String, Int>()

    private fun efile(
        key: String,
        value: Int,
    ) {
        efileKeys[key] = value
        EfileConfig.setForTest(efileKeys.toMap())
    }

    @BeforeTest
    fun setup() {
        installTestWorld()
        Equipment.putEquipment(
            pricedEqid,
            EquipmentData().apply {
                name = "Guards Rifle Regiment"
                uclass = UnitClass.INFANTRY.value
                movmethod = MovMethod.LEG.value
                cost = 100
            },
        )
    }

    @AfterTest
    fun teardown() {
        efileKeys.clear()
        clearTestWorld()
    }

    /** *"default 0 means 100% (same than PG2)"* -- an efile that says nothing changes no price. */
    @Test
    fun anEfileThatDoesNotPriceEliteReplacementsChangesNothing() {
        val map = world(prestige = 10_000)
        val unit = place(map, pricedEqid, 2, 2, 0).apply { strength = 5 }
        val standard = CostCalculator.calculateUnitCostPerStrength(unit)

        assertEquals(100, EliteReplacements.costPercent(), "0 means 100%")
        assertEquals(standard, CostCalculator.reinforceCostPerStrength(unit, false))

        efile("elite_cost", 0)
        assertEquals(standard, CostCalculator.reinforceCostPerStrength(unit, false), "an explicit 0 is still 100%")
    }

    /** `eqp-gce`'s own number, and it must reach the price the player is actually charged. */
    @Test
    fun eliteCostSurchargesTheOrdinaryReplacement() {
        val map = world(prestige = 10_000)
        val unit = place(map, pricedEqid, 2, 2, 0).apply { strength = 5 }
        val standard = CostCalculator.calculateUnitCostPerStrength(unit)

        efile("elite_cost", 133)
        assertEquals(133, EliteReplacements.costPercent())
        assertEquals(standard * 133 / 100, CostCalculator.reinforceCostPerStrength(unit, false))
    }

    /**
     * The two percentages are siblings, not nested.
     *
     * `equip.cfg` says both are *"relative to standar cost"*, so GCE's `green_cost=100` must cost
     * exactly the standard price -- not 100% of the elite price, which would make the "cheap"
     * action cost 133% and invert the whole mechanic.
     */
    @Test
    fun greenCostIsAPercentageOfTheStandardPriceNotOfTheElitePrice() {
        ruleset(RuleKey.GREEN_REPLACEMENTS to 1)
        val map = world(prestige = 10_000)
        val unit = place(map, pricedEqid, 2, 2, 0).apply { strength = 5 }
        val standard = CostCalculator.calculateUnitCostPerStrength(unit)

        efile("green", 1)
        efile("green_cost", 100)
        efile("elite_cost", 133)

        assertEquals(standard, GreenReplacements.costPerStrength(unit), "green is a percentage of the STANDARD cost")
        assertTrue(
            GreenReplacements.costPerStrength(unit) < CostCalculator.reinforceCostPerStrength(unit, false),
            "so GCE's cheap action really is the cheaper one",
        )
    }

    /**
     * The dominance this whole change exists to remove, stated as an inequality.
     *
     * With GCE's three numbers in place the elite action must be strictly dearer AND strictly
     * kinder to veterancy. If either half fails, one of the two actions is pointless.
     */
    @Test
    fun neitherReplacementActionDominatesTheOtherUnderGcesNumbers() {
        ruleset(RuleKey.GREEN_REPLACEMENTS to 1)
        efile("green", 1)
        efile("green_cost", 100)
        efile("green_exp", 50)
        efile("elite_cost", 133)
        val map = world(prestige = 10_000)
        val unit =
            place(map, pricedEqid, 2, 2, 0).apply {
                strength = 5
                experience = 400
            }

        val elitePrice = CostCalculator.reinforceCostPerStrength(unit, false)
        val greenPrice = GreenReplacements.costPerStrength(unit)
        assertTrue(greenPrice < elitePrice, "green is cheaper")

        val eliteExperience = ReplacementExperience.afterReplacement(unit.experience, unit.strength, 5)
        val greenExperience = GreenReplacements.experienceAfter(unit, 5)
        assertEquals(400, eliteExperience, "the elite intake arrives knowing what the formation knows")
        assertTrue(greenExperience < eliteExperience, "and the cheap one costs veterancy")
    }

    /** The house rule survives everywhere the efile offers no second action -- 9 of the 10 shipped
     *  efiles, and every campaign running on them. */
    @Test
    fun theHouseRuleStillDilutesWhenTheEfileAuthorsNoGreenAlternative() {
        ruleset(RuleKey.GREEN_REPLACEMENTS to 1)
        efile("green", 0)
        efile("elite_cost", 133)

        assertFalse(EliteReplacements.preservesExperience())
        assertTrue(ReplacementExperience.dilutes(), "no green action means the ordinary one is still OSADA's")
        assertEquals(120, ReplacementExperience.afterReplacement(400, 3, 7))
    }

    /** And the player's own key still wins: turning green replacements off in the ruleset puts the
     *  house rule back even under an efile that authors them. */
    @Test
    fun thePlayersKeyStillDecidesWhetherTheGreenActionExistsAtAll() {
        ruleset(RuleKey.GREEN_REPLACEMENTS to 0)
        efile("green", 1)

        assertFalse(GreenReplacements.enabled())
        assertFalse(EliteReplacements.preservesExperience())
        assertTrue(ReplacementExperience.dilutes())
    }

    // ---- `green_autorefit` -------------------------------------------------------------------

    /** *"default 0, autorefit use elite"* -- the tray pass costs the elite price and keeps the
     *  formation's experience. */
    @Test
    fun theTrayRefitUsesElitePointsByDefault() {
        ruleset(RuleKey.GREEN_REPLACEMENTS to 1)
        efile("green", 1)
        efile("elite_cost", 133)
        val map = world(prestige = 100_000)
        val unit =
            place(map, pricedEqid, 2, 2, 0).apply {
                strength = 5
                experience = 400
            }
        unit.isDeployed = false
        friendly.addCoreUnit(unit)

        val standard = CostCalculator.calculateUnitCostPerStrength(unit)
        assertEquals(5 * (standard * 133 / 100), ReserveRefit.quote(unit).strengthCost)
        ReserveRefit.refit(friendly, unit)
        assertEquals(10, unit.strength)
        assertEquals(400, unit.experience, "an elite refit costs no veterancy")
    }

    /** *"green_autorefit -- If automatic refit should use greens, thus reducing experience"*. */
    @Test
    fun theTrayRefitSpendsGreensWhenTheEfileAsksForIt() {
        ruleset(RuleKey.GREEN_REPLACEMENTS to 1)
        efile("green", 1)
        efile("green_cost", 100)
        efile("green_exp", 0)
        efile("elite_cost", 133)
        efile("green_autorefit", 1)
        val map = world(prestige = 100_000)
        val unit =
            place(map, pricedEqid, 2, 2, 0).apply {
                strength = 5
                experience = 400
            }
        unit.isDeployed = false
        friendly.addCoreUnit(unit)

        val standard = CostCalculator.calculateUnitCostPerStrength(unit)
        assertEquals(5 * standard, ReserveRefit.quote(unit).strengthCost, "greens are billed at green_cost")
        ReserveRefit.refit(friendly, unit)
        assertEquals(10, unit.strength)
        assertEquals(200, unit.experience, "and the intake halves a half-strength veteran")
    }
}
