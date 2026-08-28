package org.osada.rules

import org.osada.UNIT_MAX_EXPERIENCE
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The bar count, which is the part of `rules/UnitExperience` that survived.
 *
 * This class also covered `exp_unit_cap` and `exp_bar_factor` on 2026-08-28. Both were reverted
 * the same day (§AB): they are per-efile divergences in EXPERIENCE, and equipment is merged into
 * one `eqp-united` database, so a unit earns and holds experience at one rate whatever efile it
 * came from. §AB records both keys' arithmetic in case a per-campaign ruleset key ever wants them.
 *
 * What is left is the reason the object exists at all: `experience / 100` had three private copies,
 * so a bar count silently meant three things at once.
 */
class UnitExperienceTest : OgRulesTestHarness() {
    @BeforeTest
    fun setup() {
        installTestWorld()
    }

    @AfterTest
    fun teardown() {
        clearTestWorld()
    }

    @Test
    fun barsAreUnchangedUnderTheDefaultCeiling() {
        val map = world()
        val unit = place(map, infantryEqid, 2, 2, 0)

        unit.experience = 0
        assertEquals(0, UnitExperience.bars(unit))
        unit.experience = 349
        assertEquals(3, UnitExperience.bars(unit))
        unit.experience = UNIT_MAX_EXPERIENCE
        assertEquals(5, UnitExperience.bars(unit))
    }

    @Test
    fun barsCanNeverExceedFive() {
        val map = world()
        val unit = place(map, infantryEqid, 2, 2, 0)

        // The clamp is what makes the shared helper safe: without it a formation carried above the
        // ceiling by a campaign effect would lend a combat-support bonus and a sabotage chance that
        // scale without limit.
        unit.experience = UNIT_MAX_EXPERIENCE * 10
        assertEquals(UnitExperience.MAX_BARS, UnitExperience.bars(unit))
    }

    @Test
    fun theCeilingIsOneNumberForEveryUnit() {
        assertEquals(UNIT_MAX_EXPERIENCE, UnitExperience.cap(), "merged equipment, one ceiling")
    }
}
