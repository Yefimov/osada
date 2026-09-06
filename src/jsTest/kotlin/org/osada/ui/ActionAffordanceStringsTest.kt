package org.osada.ui

import org.osada.i18n.I18n
import org.osada.i18n.installEnglishUiBundleForTests
import org.osada.rules.ActionBlockReason
import org.osada.rules.ActionEffectKind
import org.osada.rules.UnitActionId
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every string [UnitActionPresenter] composes from an ENUM NAME must exist in the bundle.
 *
 * These keys are built at runtime (`"unit_info.action.effect.${kind.name.lowercase()}"`), so
 * adding an enum constant and forgetting its copy compiles, passes every other test, and then
 * prints the raw key at the player: `unit_info.action.effect.open_barrage_targeting` sat in the
 * Barrage chip above "Uses this formation's action for the turn" until the 2026-09-05 report.
 * `scripts/check_translations.py` cannot catch this — it compares the two BUNDLES with each other,
 * and the key was missing from both.
 */
class ActionAffordanceStringsTest {
    @BeforeTest
    fun setup() = installEnglishUiBundleForTests()

    private fun missing(keys: List<String>): List<String> = keys.filter { I18n.tOrNull(it).isNullOrBlank() }

    @Test
    fun everyActionEffectKindHasCopy() {
        assertEquals(
            emptyList(),
            missing(ActionEffectKind.entries.map { "unit_info.action.effect.${it.name.lowercase()}" }),
        )
    }

    @Test
    fun everyActionBlockReasonHasCopy() {
        assertEquals(
            emptyList(),
            missing(ActionBlockReason.entries.map { "unit_info.action.reason.${it.name.lowercase()}" }),
        )
    }

    @Test
    fun everyActionChipHasALabelAndAnExplanation() {
        // The three state-dependent variants UnitActionPresenter.variantKey can substitute for an
        // action's own id are checked alongside the ids themselves.
        val variants = UnitActionId.entries.map { it.id } + listOf("dismount", "disembark", "wake")
        assertEquals(emptyList(), missing(variants.map { "unit_info.action.$it.label" }))
        assertEquals(emptyList(), missing(variants.map { "unit_info.action.$it.help" }))
    }
}
