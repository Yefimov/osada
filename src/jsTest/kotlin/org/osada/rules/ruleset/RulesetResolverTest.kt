package org.osada.rules.ruleset

import org.osada.model.EfileConfig
import org.osada.uiSettings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ruleset resolution, provenance and the deterministic hash
 * (`docs/design/ruleset-profiles.md` §§2-5, verified per §9).
 *
 * The failures that would matter most in the field: silently clamping a content value that is
 * legitimately outside the editor's range, and a hash that moves for a cosmetic reason and blocks a
 * join between two players running the identical game.
 */
class RulesetResolverTest {
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

    private fun authorsVision() = RulesetResolver.resolve(RulesetProfileStore.builtIns().first())

    private fun osadaDefault() = RulesetResolver.resolve(RulesetProfileStore.builtIns()[1])

    // ---- Author's Vision ----------------------------------------------------------------------

    @Test
    fun authorsVisionTakesExplicitContentValuesAndSaysSo() {
        EfileConfig.setForTest(mapOf("g2a_intercept_mode" to 2, "flak_range" to 3))

        val resolved = authorsVision()

        assertEquals(2, resolved.effective(RuleKey.AA_INTERCEPT_MODE))
        assertEquals(RuleProvenance.EFILE_EXPLICIT, resolved.rule(RuleKey.AA_INTERCEPT_MODE).provenance)
        assertEquals(3, resolved.effective(RuleKey.FLAK_RANGE))
    }

    @Test
    fun anEfileWithNoConfigLandsOnTheDocumentedDefaultsAndSaysThatToo() {
        // KAISER-shaped: no equip.cfg at all, which is five of the ten shipped efiles.
        EfileConfig.setForTest(emptyMap())

        val resolved = authorsVision()

        assertEquals(
            RulesetDefaults.OSADA.getValue(RuleKey.AA_INTERCEPT_MODE),
            resolved.effective(RuleKey.AA_INTERCEPT_MODE),
        )
        assertEquals(RuleProvenance.EFILE_DEFAULT, resolved.rule(RuleKey.AA_INTERCEPT_MODE).provenance)
        assertEquals(RuleProvenance.EFILE_DEFAULT, resolved.rule(RuleKey.FLAK_RANGE).provenance)
    }

    /** §9: prove LXF Author's Vision keeps `flak_range = 4`. Clamping it to the editor's range
     *  would silently rewrite that campaign's air war. */
    @Test
    fun aContentValueAboveTheEditorRangeIsPreserved() {
        EfileConfig.setForTest(mapOf("flak_range" to 4))

        assertEquals(4, authorsVision().effective(RuleKey.FLAK_RANGE))
    }

    /**
     * Schema 16: the STALIN REGIME checkbox is a setting again and resolution ignores it entirely.
     *
     * The retired key must also stay OUT of the profile catalogue, or the Rules window would keep
     * offering a duplicate of a settings row -- which is what a player asked about ("why is this in
     * the ruleset?") and what the retirement answers.
     */
    @Test
    fun stalinRegimeIsNoLongerARuleAndCannotMoveTheHash() {
        val before = authorsVision().deterministicHash

        uiSettings.stalinRegime = true

        assertNull(RuleKey.byKey("stalin_regime"))
        assertEquals(before, authorsVision().deterministicHash)
    }

    // ---- availability -------------------------------------------------------------------------

    /** §9: "attachments on" must become content-unavailable, never invent slots. */
    @Test
    fun attachmentsRequestedOnContentWithNoSlotsIsReportedUnavailable() {
        EfileConfig.setForTest(mapOf("attach_on" to 1), attachmentConfigValue = null)

        val profile = RulesetProfile("custom-1", "Mine", overrides = mapOf(RuleKey.ATTACHMENTS to 1))
        val resolved = RulesetResolver.resolve(profile)
        val rule = resolved.rule(RuleKey.ATTACHMENTS)

        assertEquals(1, rule.requested, "the request is kept, so the window can explain it")
        assertEquals(0, rule.effective)
        assertTrue(rule.unavailable)
        assertEquals(RuleProvenance.CONTENT_UNAVAILABLE, rule.provenance)
        assertEquals(listOf(RuleKey.ATTACHMENTS), resolved.unavailable())
    }

    @Test
    fun attachmentsOffIsNotAnUnavailableMechanic() {
        EfileConfig.setForTest(emptyMap(), attachmentConfigValue = null)

        assertFalse(authorsVision().rule(RuleKey.ATTACHMENTS).unavailable)
    }

    // ---- overlay ------------------------------------------------------------------------------

    @Test
    fun aCustomProfileOverlaysOnlyTheKeysItNames() {
        EfileConfig.setForTest(mapOf("g2a_intercept_mode" to 2, "flak_range" to 3))
        val profile = RulesetProfile("custom-1", "Mine", overrides = mapOf(RuleKey.AA_INTERCEPT_MODE to 0))

        val resolved = RulesetResolver.resolve(profile)

        assertEquals(0, resolved.effective(RuleKey.AA_INTERCEPT_MODE))
        assertEquals(RuleProvenance.CUSTOM_OVERRIDE, resolved.rule(RuleKey.AA_INTERCEPT_MODE).provenance)
        assertEquals(3, resolved.effective(RuleKey.FLAK_RANGE), "an unnamed key keeps following the content")
        assertEquals(RuleProvenance.EFILE_EXPLICIT, resolved.rule(RuleKey.FLAK_RANGE).provenance)
    }

    @Test
    fun osadaDefaultIsIdenticalWhateverTheContentSays() {
        EfileConfig.setForTest(mapOf("g2a_intercept_mode" to 3, "flak_range" to 4))
        val withContent = osadaDefault()
        EfileConfig.setForTest(emptyMap())
        val withoutContent = osadaDefault()

        assertEquals(withContent.deterministicHash, withoutContent.deterministicHash)
        RuleKey.entries.forEach { rule ->
            assertEquals(RulesetDefaults.OSADA.getValue(rule), withContent.effective(rule), rule.key)
        }
    }

    // ---- hash ---------------------------------------------------------------------------------

    @Test
    fun theHashIsStableAcrossMapInsertionOrder() {
        val forward = RuleKey.entries.associateWith { it.editorMin }
        val reversed = RuleKey.entries.reversed().associateWith { it.editorMin }

        assertEquals(
            RulesetResolver.hash(RULESET_SCHEMA_VERSION, forward),
            RulesetResolver.hash(RULESET_SCHEMA_VERSION, reversed),
        )
    }

    @Test
    fun theHashIgnoresProfileIdentityButFollowsEveryEffectiveValue() {
        EfileConfig.setForTest(mapOf("g2a_intercept_mode" to 2))
        val values = RuleKey.entries.associateWith { rule -> RulesetDefaults.OSADA.getValue(rule) }

        val renamed = RulesetResolver.fromEffective("custom-1", "Before", RulesetSource.CUSTOM, 1, values)
        val sameRules = RulesetResolver.fromEffective("custom-9", "After", RulesetSource.CUSTOM, 1, values)
        val changed =
            RulesetResolver.fromEffective(
                "custom-1",
                "Before",
                RulesetSource.CUSTOM,
                1,
                values + (RuleKey.FLAK_RANGE to 3),
            )

        assertEquals(renamed.deterministicHash, sameRules.deterministicHash, "a rename is not a rule change")
        assertNotEquals(renamed.deterministicHash, changed.deterministicHash)
    }

    @Test
    fun theSchemaIsPartOfTheHash() {
        val values = RuleKey.entries.associateWith { rule -> RulesetDefaults.OSADA.getValue(rule) }

        assertNotEquals(
            RulesetResolver.hash(1, values),
            RulesetResolver.hash(2, values),
            "an older client must not claim compatibility with a schema it cannot run",
        )
    }
}
