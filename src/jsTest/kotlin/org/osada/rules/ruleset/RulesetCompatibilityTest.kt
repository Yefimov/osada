package org.osada.rules.ruleset

import org.osada.model.EfileConfig
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The multiplayer join gate (`docs/design/ruleset-profiles.md` §8, verified per §9).
 *
 * Profile name and id differences never block a join; gameplay effective-value or schema
 * differences always do. Getting that backwards either splits players who are running the same game
 * or, far worse, lets two clients execute different ones.
 */
class RulesetCompatibilityTest {
    @BeforeTest
    fun setup() {
        EfileConfig.resetForTest()
        EfileConfig.setForTest(emptyMap())
        ActiveRuleset.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        EfileConfig.resetForTest()
        ActiveRuleset.resetForTest()
    }

    private fun block(
        id: String = "custom-1",
        name: String = "Room rules",
        schemaVersion: Int = RULESET_SCHEMA_VERSION,
        effective: Map<String, Int> = RuleKey.entries.associate { it.key to RulesetDefaults.OSADA.getValue(it) },
        extraKey: Pair<String, Int>? = null,
    ): dynamic {
        val out = js("{}")
        out.id = id
        out.name = name
        out.source = "CUSTOM"
        out.schemaVersion = schemaVersion
        val values = js("{}")
        effective.forEach { (key, value) -> values[key] = value }
        extraKey?.let { (key, value) -> values[key] = value }
        out.effective = values
        return out
    }

    private fun local(
        effective: Map<RuleKey, Int>,
        name: String = "Mine",
        id: String = "custom-9",
    ) = RulesetResolver.fromEffective(id, name, RulesetSource.CUSTOM, RULESET_SCHEMA_VERSION, effective)

    private val baseline = RuleKey.entries.associateWith { RulesetDefaults.OSADA.getValue(it) }

    @Test
    fun aClientWithNoSelectionAdoptsTheHostRules() {
        val verdict = RulesetCompatibility.verdict(null, block())

        assertTrue(verdict.allowed, "no opinion is not a conflict")
    }

    @Test
    fun equalValuesUnderADifferentNameAndIdAreAccepted() {
        val verdict = RulesetCompatibility.verdict(local(baseline), block(id = "custom-1", name = "Room rules"))

        assertTrue(verdict.allowed)
        assertEquals(emptyList(), verdict.differingRules)
    }

    @Test
    fun aSingleDifferingValueIsRefusedAndNamed() {
        val verdict =
            RulesetCompatibility.verdict(
                local(baseline + (RuleKey.FLAK_RANGE to 3)),
                block(),
            )

        assertFalse(verdict.allowed)
        assertEquals(RulesetCompatibility.Refusal.DIFFERENT_RULES, verdict.refusal)
        assertEquals(listOf(RuleKey.FLAK_RANGE), verdict.differingRules)
        assertTrue(verdict.localHash.isNotBlank() && verdict.remoteHash.isNotBlank(), "both hashes are reported")
    }

    @Test
    fun aNewerSchemaIsRefusedEvenWhenTheValuesLookFamiliar() {
        val verdict = RulesetCompatibility.verdict(local(baseline), block(schemaVersion = RULESET_SCHEMA_VERSION + 1))

        assertFalse(verdict.allowed)
        assertEquals(RulesetCompatibility.Refusal.UNSUPPORTED_SCHEMA, verdict.refusal)
    }

    @Test
    fun anUnknownRuleIsRefusedAndNamedRatherThanIgnored() {
        val verdict = RulesetCompatibility.verdict(local(baseline), block(extraKey = "warp_drive" to 1))

        assertFalse(verdict.allowed)
        assertEquals(RulesetCompatibility.Refusal.UNKNOWN_RULES, verdict.refusal)
        assertEquals(setOf("warp_drive"), verdict.unknownKeys)
    }

    @Test
    fun anUnknownRuleIsRefusedEvenForAClientWithNoSelectionOfItsOwn() {
        // Adopting a room that names rules this build cannot execute would mean executing a
        // different game while believing otherwise.
        val verdict = RulesetCompatibility.verdict(null, block(extraKey = "warp_drive" to 1))

        assertFalse(verdict.allowed)
        assertEquals(RulesetCompatibility.Refusal.UNKNOWN_RULES, verdict.refusal)
    }

    @Test
    fun differenceListsEveryDisagreeingRule() {
        val remote = RulesetResolver.fromEffective("a", "A", RulesetSource.CUSTOM, RULESET_SCHEMA_VERSION, baseline)
        val mine = local(baseline + (RuleKey.FLAK_RANGE to 3) + (RuleKey.ATTACHMENTS to 1))

        assertEquals(
            setOf(RuleKey.FLAK_RANGE, RuleKey.ATTACHMENTS),
            RulesetCompatibility.difference(mine, remote).toSet(),
        )
    }
}
