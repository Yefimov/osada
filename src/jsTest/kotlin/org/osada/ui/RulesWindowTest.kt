package org.osada.ui

import kotlinx.browser.document
import org.osada.i18n.I18n
import org.osada.i18n.installEnglishUiBundleForTests
import org.osada.model.EfileConfig
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RULESET_SCHEMA_VERSION
import org.osada.rules.ruleset.RuleKey
import org.osada.rules.ruleset.RulesetProfile
import org.osada.rules.ruleset.RulesetProfileStore
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Rules window and its editor
 * (`docs/design/ruleset-profiles.md` §7, verified per §9).
 *
 * The parts a store test cannot see: that every catalogued rule actually has localized copy, that a
 * profile this build cannot execute is offered disabled rather than silently downgraded, and that
 * the editor refuses a blank or duplicate name.
 */
class RulesWindowTest {
    @BeforeTest
    fun setup() {
        installEnglishUiBundleForTests()
        RulesetProfileStore.clearForTest()
        RulesetSelection.resetForTest()
        RulesWindow.resetForTest()
        RulesEditorWindow.resetForTest()
        EfileConfig.resetForTest()
        EfileConfig.setForTest(emptyMap())
        ActiveRuleset.resetForTest()
        ensure("mainbody").style.display = ""
    }

    @AfterTest
    fun tearDown() {
        RulesWindow.resetForTest()
        RulesEditorWindow.resetForTest()
        RulesetProfileStore.clearForTest()
        RulesetSelection.resetForTest()
        EfileConfig.resetForTest()
        ActiveRuleset.resetForTest()
    }

    private fun ensure(id: String): HTMLElement =
        byId(id) ?: (document.createElement("div") as HTMLElement).also {
            it.id = id
            document.body?.appendChild(it)
        }

    private fun window(): HTMLElement? = byId(RulesWindow.WINDOW_ID)

    // ---- localization -------------------------------------------------------------------------

    /** §9: every catalogued key needs a localized summary and help, or the window ships raw ids. */
    @Test
    fun everyRuleHasALocalizedLabelHelpAndValueWording() {
        RuleKey.entries.forEach { rule ->
            assertNotNull(I18n.tOrNull("rules.${rule.key}.label"), "missing label for ${rule.key}")
            assertNotNull(I18n.tOrNull("rules.${rule.key}.help"), "missing help for ${rule.key}")
            for (value in rule.editorMin..rule.editorMax) {
                val text = RulesText.value(rule, value)
                assertTrue(text.isNotBlank(), "${rule.key}=$value produced no wording")
                assertFalse(text.contains(rule.key), "${rule.key}=$value leaked its raw key: $text")
            }
        }
    }

    // ---- window -------------------------------------------------------------------------------

    @Test
    fun theWindowListsOneSummaryRowPerRuleAndTheFingerprint() {
        RulesWindow.open(RulesetSelection.Surface.SCENARIO)
        val node = window()

        assertNotNull(node)
        assertEquals(RuleKey.entries.size, node.querySelectorAll(".osadaRulesSummary__row").length)
        val hash = node.querySelector(".osadaRulesHash__value") as? HTMLElement
        assertTrue((hash?.textContent?.length ?: 0) > 0, "the fingerprint is shown for diagnosis")
    }

    @Test
    fun aContentUnavailableRuleSaysSoRatherThanReadingAsOff() {
        EfileConfig.setForTest(mapOf("attach_on" to 1), attachmentConfigValue = null)
        RulesetProfileStore.save(
            RulesetProfile("custom-1", "Mine", overrides = mapOf(RuleKey.ATTACHMENTS to 1)),
        )
        RulesetSelection.select(RulesetSelection.Surface.SCENARIO, "custom-1")

        RulesWindow.open(RulesetSelection.Surface.SCENARIO)
        val row = document.querySelector(".osadaRulesSummary__row[data-rule=\"attachments\"]") as? HTMLElement

        assertNotNull(row)
        assertTrue(row.className.contains("osadaRulesSummary__row--unavailable"), row.className)
        assertEquals(I18n.t("rules.value.unavailable"), (row.lastElementChild as? HTMLElement)?.textContent)
    }

    @Test
    fun anUnsupportedProfileIsOfferedDisabledAndCannotBeSelected() {
        RulesetProfileStore.replaceAll(
            listOf(RulesetProfile("custom-1", "Future", schemaVersion = RULESET_SCHEMA_VERSION + 1)),
        )

        RulesWindow.open(RulesetSelection.Surface.SCENARIO)
        val select = byId(RulesWindow.SELECT_ID) as? HTMLSelectElement
        val option = select?.querySelector("option[value=\"custom-1\"]") as? HTMLElement

        assertNotNull(option)
        assertEquals(true, option.asDynamic().disabled)
        assertFalse(RulesetSelection.select(RulesetSelection.Surface.SCENARIO, "custom-1"))
        assertEquals(RulesetProfile.AUTHORS_VISION_ID, RulesetSelection.selectedId(RulesetSelection.Surface.SCENARIO))
    }

    @Test
    fun aReadOnlyWindowOffersNoPickerAndNoActions() {
        RulesWindow.open(RulesetSelection.Surface.MULTIPLAYER, readOnlyWindow = true)
        val select = byId(RulesWindow.SELECT_ID) as? HTMLSelectElement

        assertEquals(true, select?.disabled)
        assertEquals(0, window()?.querySelectorAll(".osadaRulesAction")?.length)
    }

    @Test
    fun theWindowIsADialogAndClosesCleanly() {
        RulesWindow.open(RulesetSelection.Surface.SCENARIO)
        val node = window()

        assertEquals("dialog", node?.getAttribute("role"))
        assertEquals("true", node?.getAttribute("aria-modal"))

        RulesWindow.close()
        assertNull(window())
        assertFalse(RulesWindow.isOpen())
    }

    // ---- editor -------------------------------------------------------------------------------

    @Test
    fun editCopyCreatesASeededProfileWithoutSavingUntilTheNameIsValid() {
        RulesWindow.open(RulesetSelection.Surface.SCENARIO)
        RulesEditorWindow.openCopyOf(RulesetSelection.Surface.SCENARIO)

        assertTrue(RulesEditorWindow.isOpen())
        assertEquals(
            RuleKey.entries.size,
            byId(RulesEditorWindow.WINDOW_ID)?.querySelectorAll("[data-rule]")?.length,
            "every rule gets a control",
        )
        assertTrue(RulesetProfileStore.custom().isEmpty(), "nothing is stored until Save")
    }

    @Test
    fun theEditorRefusesABlankOrDuplicateName() {
        RulesetProfileStore.save(RulesetProfile("custom-1", "Taken"))
        RulesWindow.open(RulesetSelection.Surface.SCENARIO)
        RulesEditorWindow.openCopyOf(RulesetSelection.Surface.SCENARIO)
        val name = byId(RulesEditorWindow.NAME_ID) as? HTMLInputElement

        assertNotNull(name)
        name.value = "   "
        name.dispatchEvent(
            org.w3c.dom.events
                .Event("input"),
        )
        assertEquals(true, byId(RulesEditorWindow.SAVE_ID)?.asDynamic()?.disabled)

        name.value = "Taken"
        name.dispatchEvent(
            org.w3c.dom.events
                .Event("input"),
        )
        assertEquals(true, byId(RulesEditorWindow.SAVE_ID)?.asDynamic()?.disabled)

        name.value = "Fresh"
        name.dispatchEvent(
            org.w3c.dom.events
                .Event("input"),
        )
        assertEquals(false, byId(RulesEditorWindow.SAVE_ID)?.asDynamic()?.disabled)
    }

    @Test
    fun renamingOffersNoRuleControlsAtAll() {
        RulesetProfileStore.save(RulesetProfile("custom-1", "Mine", overrides = mapOf(RuleKey.FLAK_RANGE to 3)))
        RulesetSelection.select(RulesetSelection.Surface.SCENARIO, "custom-1")

        RulesEditorWindow.openRename(RulesetSelection.Surface.SCENARIO)

        assertEquals(0, byId(RulesEditorWindow.WINDOW_ID)?.querySelectorAll("[data-rule]")?.length)
        assertNotNull(byId(RulesEditorWindow.NAME_ID))
    }
}
