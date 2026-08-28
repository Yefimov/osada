package org.osada.rules.ruleset

import org.osada.model.EfileConfig
import org.osada.uiSettings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Author's Vision, after it was made to mean what its name says (`docs/og-fidelity-plan.md` §AC).
 *
 * This class replaces `OgFidelityProfileTest`, which asserted a third built-in profile that no
 * longer exists. The reason it no longer exists is the thing under test here: **Author's Vision
 * now defers to each scenario's own authored switches**, so it already runs what Open General runs
 * and a bundled "Open General Fidelity" preset had nothing left to add.
 *
 * Two failures would matter most, and both are one line away:
 *
 *  * **overriding the author.** Resolving a scenario-authored rule to OSADA's off is exactly the
 *    bug §AC fixed: it silently discarded the option bits §O imported into 397 scenario XMLs.
 *  * **flattening a content value the profile has no opinion about.** LXF ships `flak_range = 4`;
 *    narrowing it would rewrite that campaign's air war, which is what
 *    `docs/design/ruleset-profiles.md` §2 exists to prevent.
 */
class AuthorsVisionProfileTest {
    private val stalinBefore = uiSettings.stalinRegime

    @BeforeTest
    fun setup() {
        EfileConfig.resetForTest()
        ActiveRuleset.resetForTest()
        uiSettings.stalinRegime = false
    }

    @AfterTest
    fun tearDown() {
        EfileConfig.resetForTest()
        ActiveRuleset.resetForTest()
        uiSettings.stalinRegime = stalinBefore
    }

    private fun authorsVision(): ResolvedRuleset =
        RulesetResolver.resolve(
            RulesetProfileStore.builtIns().first { it.source == RulesetSource.AUTHORS_VISION },
        )

    private fun osadaDefault(): ResolvedRuleset =
        RulesetResolver.resolve(
            RulesetProfileStore.builtIns().first { it.source == RulesetSource.OSADA_DEFAULT },
        )

    /** The rules whose master switch Author's Vision hands to the scenario. */
    private val scenarioAuthored =
        listOf(
            RuleKey.EXTENDED_LOS,
            RuleKey.AIR_ZOC,
            RuleKey.EXTENDED_NAVAL,
            RuleKey.BARRAGE,
            RuleKey.BUILD_AND_REPAIR,
        )

    @Test
    fun thereAreExactlyTwoBuiltInProfiles() {
        val builtIns = RulesetProfileStore.builtIns()

        assertEquals(2, builtIns.size, "Author's Vision and OSADA Default; the OG preset was retired")
        assertEquals(
            listOf(RulesetSource.AUTHORS_VISION, RulesetSource.OSADA_DEFAULT),
            builtIns.map { it.source },
        )
    }

    @Test
    fun authorsVisionHandsEveryScenarioAuthoredRuleToTheScenario() {
        val resolved = authorsVision()

        scenarioAuthored.forEach { rule ->
            assertEquals(1, resolved.effective(rule), "${rule.key} must not override the author")
            assertEquals(
                RuleProvenance.SCENARIO_AUTHORED,
                resolved.rule(rule).provenance,
                "${rule.key} should say the SCENARIO chose this, not OSADA",
            )
        }
    }

    @Test
    fun osadaDefaultStillOverridesTheAuthor() {
        val resolved = osadaDefault()

        // The whole point of the second profile: one documented baseline, identical for every
        // content. A player who picks it is asking NOT to follow the author.
        scenarioAuthored.forEach { rule ->
            assertEquals(0, resolved.effective(rule), "${rule.key} is OSADA's baseline here")
            assertEquals(RuleProvenance.OSADA_DEFAULT, resolved.rule(rule).provenance)
        }
    }

    @Test
    fun rulesWithNoScenarioSwitchStayOnOsadasDefault() {
        val resolved = authorsVision()

        // Nothing in the content asks for these three, so Author's Vision has nobody to defer to.
        listOf(RuleKey.COUNTERBATTERY, RuleKey.MINEFIELDS, RuleKey.NAVAL_CRITICAL_HITS).forEach { rule ->
            assertEquals(0, resolved.effective(rule), "${rule.key} has no per-scenario switch")
            assertEquals(RuleProvenance.OSADA_DEFAULT, resolved.rule(rule).provenance)
        }
    }

    @Test
    fun aContentValueIsNeverNarrowedByTheProfile() {
        EfileConfig.setForTest(intKeyMap = mapOf("flak_range" to 4))

        val resolved = authorsVision()

        // §2: LXF's flak_range = 4 is the author's air war, not an out-of-range value to correct.
        assertEquals(4, resolved.effective(RuleKey.FLAK_RANGE))
        assertEquals(RuleProvenance.EFILE_EXPLICIT, resolved.rule(RuleKey.FLAK_RANGE).provenance)
    }

    @Test
    fun everyRuleResolvesUnderBothBuiltIns() {
        val vision = authorsVision()
        val osada = osadaDefault()

        // A key missing from either resolution is a `getValue` throw waiting to happen at launch.
        RuleKey.entries.forEach { rule ->
            assertTrue(vision.effective(rule) >= 0, "${rule.key} unresolved under Author's Vision")
            assertTrue(osada.effective(rule) >= 0, "${rule.key} unresolved under OSADA Default")
        }
    }
}
