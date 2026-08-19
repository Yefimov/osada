package org.osada.rules.ruleset

import org.osada.model.EfileConfig
import org.osada.uiSettings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The third built-in profile (`docs/og-fidelity-plan.md` D.1-D.3).
 *
 * Two failures would matter most here, and both are one line away from happening:
 *
 *  * **flattening a content value the profile has no opinion about.** LXF ships `flak_range = 4`;
 *    a profile that named the key would silently rewrite that campaign's air war, which is the
 *    failure `docs/design/ruleset-profiles.md` §2 exists to prevent.
 *  * **claiming a rule the engine does not run.** Every value this profile asserts has to be a key
 *    the build actually executes, or the picker becomes the list of promises §10 forbids.
 */
class OgFidelityProfileTest {
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

    private fun ogFidelity(): ResolvedRuleset =
        RulesetResolver.resolve(
            RulesetProfileStore.builtIns().first { it.source == RulesetSource.OG_FIDELITY },
        )

    @Test
    fun theProfileIsAThirdBuiltInAndTheLastOfThem() {
        val builtIns = RulesetProfileStore.builtIns()
        assertEquals(3, builtIns.size)
        assertEquals(RulesetProfile.OG_FIDELITY_ID, builtIns.last().id)
        assertEquals(RulesetSource.OG_FIDELITY, builtIns.last().source)
    }

    @Test
    fun everyOpenGeneralRuleTheEngineRunsIsOn() {
        val resolved = ogFidelity()
        listOf(
            RuleKey.HEAVY_MOVE_FIRE,
            RuleKey.SNOW_FUEL,
            RuleKey.SUPPORT_FIRE_FALLOFF,
            RuleKey.DRY_UNIT_PENALTIES,
            RuleKey.MINEFIELDS,
            RuleKey.AIR_FUEL,
            RuleKey.INITIATIVE_MODEL,
            RuleKey.SPOTTING_MEMORY,
            RuleKey.INSTALLATION_SPOTTING,
            RuleKey.GROUND_AUTO_SUPPLY,
        ).forEach { rule ->
            assertEquals(1, resolved.effective(rule), "${rule.key} is an Open General rule and must be on")
            assertEquals(RuleProvenance.OG_FIDELITY, resolved.rule(rule).provenance)
        }
    }

    @Test
    fun replacementsPreserveExperienceTheWayOgSaysTheyDo() {
        // OG 6.19: "Using replacements preserve the unit's experience and leaders." OSADA's own
        // default is the opposite by an explicit owner decision, which is why the key exists at all.
        assertEquals(0, ogFidelity().effective(RuleKey.REPLACEMENT_EXPERIENCE))
        assertEquals(1, RulesetDefaults.OSADA.getValue(RuleKey.REPLACEMENT_EXPERIENCE))
    }

    @Test
    fun aContentValueOutsideTheEditorRangeSurvivesSelectingTheProfile() {
        // The `flak_range = 4` case, verbatim from §2.
        EfileConfig.setForTest(mapOf("flak_range" to 4, "g2a_intercept_mode" to 2))
        val resolved = ogFidelity()
        assertEquals(4, resolved.effective(RuleKey.FLAK_RANGE), "the profile must not flatten the author's air war")
        assertEquals(2, resolved.effective(RuleKey.AA_INTERCEPT_MODE))
        assertEquals(RuleProvenance.EFILE_EXPLICIT, resolved.rule(RuleKey.FLAK_RANGE).provenance)
    }

    @Test
    fun theProfileHasNoOpinionOnOsadasOwnStalinRule() {
        uiSettings.stalinRegime = true
        val resolved = ogFidelity()
        assertEquals(1, resolved.effective(RuleKey.STALIN_REGIME), "an OSADA rule with no OG counterpart is left alone")
        assertEquals(RuleProvenance.OSADA_DEFAULT, resolved.rule(RuleKey.STALIN_REGIME).provenance)
    }

    @Test
    fun itNamesNoRuleThisBuildCannotExecute() {
        RulesetDefaults.OG_FIDELITY.keys.forEach { rule ->
            assertTrue(RuleKey.entries.contains(rule), "${rule.key} is not in this build's catalogue")
        }
    }

    @Test
    fun itIsADifferentGameFromOsadaDefaultAndSaysSoInTheHash() {
        val osada = RulesetResolver.resolve(RulesetProfileStore.builtIns()[1])
        assertNotEquals(osada.deterministicHash, ogFidelity().deterministicHash)
    }

    @Test
    fun aSavedBattleRestoresAsOpenGeneralFidelityRatherThanAsSomeoneElsesCustomProfile() {
        // §D.3's reason for a source of its own: a save has to be able to say WHICH ruleset it ran
        // under after the local profile library has been edited.
        val restored = deserializeRuleset(serializeRuleset(ogFidelity()))
        assertEquals(RulesetSource.OG_FIDELITY, restored?.source)
        assertEquals(ogFidelity().deterministicHash, restored?.deterministicHash)
    }
}
