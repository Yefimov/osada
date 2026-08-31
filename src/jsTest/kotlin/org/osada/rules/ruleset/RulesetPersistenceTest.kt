package org.osada.rules.ruleset

import org.osada.model.EfileConfig
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The profile library and the save codec
 * (`docs/design/ruleset-profiles.md` §§3, 6, verified per §9).
 *
 * The contract that is easy to break by accident: a save must reproduce the battle it recorded even
 * after the profile it names has been renamed, edited or deleted, and a legacy save must not be
 * quietly re-run under OSADA Default.
 */
class RulesetPersistenceTest {
    @BeforeTest
    fun setup() {
        RulesetProfileStore.clearForTest()
        EfileConfig.resetForTest()
        ActiveRuleset.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        RulesetProfileStore.clearForTest()
        EfileConfig.resetForTest()
        ActiveRuleset.resetForTest()
    }

    // ---- library ------------------------------------------------------------------------------

    @Test
    fun theBuiltInsAlwaysExistAndComeFirst() {
        val all = RulesetProfileStore.all()

        assertEquals(RulesetProfile.AUTHORS_VISION_ID, all[0].id)
        assertEquals(RulesetProfile.OSADA_DEFAULT_ID, all[1].id)
        assertEquals(RulesetSource.AUTHORS_VISION, all[0].source)
    }

    @Test
    fun aSavedProfileRoundTripsAndIsSortedByName() {
        RulesetProfileStore.save(RulesetProfile("custom-2", "Zulu", overrides = mapOf(RuleKey.FLAK_RANGE to 2)))
        RulesetProfileStore.save(RulesetProfile("custom-1", "Alpha", overrides = mapOf(RuleKey.ATTACHMENTS to 1)))

        val custom = RulesetProfileStore.custom()

        assertEquals(listOf("Alpha", "Zulu"), custom.map { it.name })
        assertEquals(mapOf(RuleKey.FLAK_RANGE to 2), custom.first { it.id == "custom-2" }.overrides)
    }

    @Test
    fun aRenameKeepsTheIdSoExistingReferencesStillResolve() {
        RulesetProfileStore.save(RulesetProfile("custom-1", "Before", overrides = mapOf(RuleKey.FLAK_RANGE to 3)))

        val renamed = RulesetProfileStore.rename("custom-1", "After")

        assertEquals("custom-1", renamed?.id)
        assertEquals("After", RulesetProfileStore.byId("custom-1")?.name)
        assertEquals(mapOf(RuleKey.FLAK_RANGE to 3), RulesetProfileStore.byId("custom-1")?.overrides)
    }

    @Test
    fun aNameMustBeNonBlankAndUnique() {
        RulesetProfileStore.save(RulesetProfile("custom-1", "Taken"))

        assertFalse(RulesetProfileStore.isNameAvailable("   "))
        assertFalse(RulesetProfileStore.isNameAvailable("taken"), "duplicate names are compared case-insensitively")
        assertTrue(RulesetProfileStore.isNameAvailable("Taken", exceptId = "custom-1"), "renaming to itself is fine")
        assertTrue(RulesetProfileStore.isNameAvailable("Free"))
    }

    @Test
    fun idsAreHandedOutWithoutReusingADeletedOne() {
        RulesetProfileStore.save(RulesetProfile("custom-1", "One"))
        RulesetProfileStore.save(RulesetProfile("custom-2", "Two"))
        RulesetProfileStore.delete("custom-2")

        assertEquals("custom-2", RulesetProfileStore.nextId())
        RulesetProfileStore.save(RulesetProfile("custom-2", "Two again"))
        assertEquals("custom-3", RulesetProfileStore.nextId())
    }

    /** §3: a key this build cannot execute is preserved, and the profile is refused rather than
     *  silently reinterpreted as though both sides agreed. */
    @Test
    fun anUnknownKeyMakesTheProfileUnsupportedInsteadOfBeingDropped() {
        val stored = """[{"id":"custom-1","name":"Future","schemaVersion":1,"overrides":{"warp_drive":2}}]"""

        val profile = RulesetProfileStore.parse(stored).single()

        assertEquals(setOf("warp_drive"), profile.unknownKeys)
        assertFalse(profile.supported)
    }

    /** Schema 13/14 profiles may name the retired trigger key; it is migrated away, not rejected. */
    @Test
    fun theRetiredTriggerKeyIsDroppedFromAnOldProfile() {
        val stored =
            """[{"id":"custom-1","name":"Old","schemaVersion":14,"overrides":{"trigger_hexes":0}}]"""

        val profile = RulesetProfileStore.parse(stored).single()

        assertTrue(profile.supported)
        assertTrue(profile.overrides.isEmpty())
        assertTrue(profile.unknownKeys.isEmpty())
    }

    @Test
    fun aNewerSchemaIsVisibleButUnsupported() {
        val stored = """[{"id":"custom-1","name":"Future","schemaVersion":99,"overrides":{}}]"""

        val profile = RulesetProfileStore.parse(stored).single()

        assertEquals(99, profile.schemaVersion)
        assertFalse(profile.supported)
    }

    @Test
    fun aMalformedLibraryIsIgnoredRatherThanFatal() {
        assertEquals(emptyList(), RulesetProfileStore.parse("not json at all"))
        assertEquals(emptyList(), RulesetProfileStore.parse(null))
        assertEquals(emptyList(), RulesetProfileStore.parse("""[{"name":"no id"}]"""))
    }

    // ---- save codec ---------------------------------------------------------------------------

    @Test
    fun theEffectiveValuesRoundTripThroughTheSaveBlock() {
        EfileConfig.setForTest(mapOf("g2a_intercept_mode" to 2, "flak_range" to 4))
        val resolved = RulesetResolver.resolve(RulesetProfileStore.builtIns().first())

        val restored = deserializeRuleset(serializeRuleset(resolved))

        assertEquals(resolved.deterministicHash, restored?.deterministicHash)
        RuleKey.entries.forEach { rule ->
            assertEquals(resolved.effective(rule), restored?.effective(rule), rule.key)
        }
    }

    /** §6: restoring never looks the profile up, so editing or deleting it changes nothing. */
    @Test
    fun editingOrDeletingTheNamedProfileDoesNotChangeAStoredRuleset() {
        EfileConfig.setForTest(emptyMap())
        RulesetProfileStore.save(RulesetProfile("custom-1", "Mine", overrides = mapOf(RuleKey.FLAK_RANGE to 3)))
        val block = serializeRuleset(RulesetResolver.resolve(RulesetProfileStore.byId("custom-1")!!))

        RulesetProfileStore.save(RulesetProfile("custom-1", "Mine", overrides = mapOf(RuleKey.FLAK_RANGE to 1)))
        RulesetProfileStore.delete("custom-1")

        assertEquals(3, deserializeRuleset(block)?.effective(RuleKey.FLAK_RANGE))
    }

    /** §4/§9: a save written before rulesets shipped ran with no overlay, and must restore that
     *  way rather than being re-run under OSADA Default. */
    @Test
    fun aLegacySaveRestoresWithNoOverlay() {
        assertNull(deserializeRuleset(null))
        assertNull(deserializeRuleset(undefined))
    }

    @Test
    fun aStoredValueOutsideTheEditorRangeIsPreservedOnRestore() {
        val block = js("{}")
        block.id = "authors-vision"
        block.name = ""
        block.source = "AUTHORS_VISION"
        block.schemaVersion = 1
        val effective = js("{}")
        effective["flak_range"] = 4
        block.effective = effective

        assertEquals(4, deserializeRuleset(block)?.effective(RuleKey.FLAK_RANGE))
    }

    @Test
    fun aCorruptStoredHashIsRecomputedRatherThanTrusted() {
        EfileConfig.setForTest(emptyMap())
        val resolved = RulesetResolver.resolve(RulesetProfileStore.builtIns()[1])
        val block = serializeRuleset(resolved)
        block.hash = "0000000000000000000000000000000000000000000000000000000000000000"

        assertEquals(resolved.deterministicHash, deserializeRuleset(block)?.deterministicHash)
    }

    @Test
    fun unknownKeysInAStoredBlockAreReported() {
        val block = js("{}")
        block.id = "custom-1"
        block.schemaVersion = 1
        val effective = js("{}")
        effective["flak_range"] = 2
        effective["warp_drive"] = 1
        block.effective = effective

        assertEquals(setOf("warp_drive"), unknownRulesetKeys(block))
        assertEquals(1, readRulesetSchemaVersion(block))
    }

    /** Old battle saves also carry the retired key in their effective block. */
    @Test
    fun theRetiredTriggerKeyIsKnownButIgnoredInAnOldSave() {
        val block = js("({})")
        block.id = "osada-default"
        block.schemaVersion = 14
        block.effective = js("({trigger_hexes: 0})")

        assertTrue(unknownRulesetKeys(block).isEmpty())
        assertTrue(deserializeRuleset(block) != null)
    }
}
