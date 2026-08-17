package org.osada.ui.keyboard

import kotlinx.browser.document
import org.osada.i18n.I18n
import org.osada.i18n.installEnglishUiBundleForTests
import org.osada.ui.byId
import org.osada.ui.delTag
import org.w3c.dom.HTMLElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The F1 card and the chip bridge (`docs/design/keyboard-shortcuts-and-help.md` §§4, 5, 6).
 *
 * The card is generated from [CommandCatalog], so the test that matters most is the one proving
 * every catalog entry actually has canonical English copy: a missing key would otherwise ship as a
 * raw `controls.command.*.label` string on screen.
 */
class ControlsCardTest {
    @BeforeTest
    fun setup() {
        installEnglishUiBundleForTests()
        // A hidden ancestor makes every descendant unfocusable, and a sibling suite may have left
        // it hidden.
        ensure("mainbody").style.display = ""
        ControlsCard.close()
    }

    @AfterTest
    fun tearDown() {
        ControlsCard.close()
        delTag(byId("unit-context"))
    }

    private fun ensure(id: String): HTMLElement =
        byId(id) ?: (document.createElement("div") as HTMLElement).also {
            it.id = id
            document.body?.appendChild(it)
        }

    // ---- localization ---------------------------------------------------------------------

    @Test
    fun everyCatalogRowHasCanonicalEnglishCopy() {
        CommandGroup.entries.forEach { group ->
            assertNotNull(
                I18n.tOrNull("controls.group.${group.name.lowercase()}.title"),
                "missing group title for $group",
            )
            CommandCatalog.cardRows(group).forEach { row ->
                assertNotNull(I18n.tOrNull(row.labelKey), "missing ${row.labelKey}")
                assertNotNull(I18n.tOrNull(row.helpKey), "missing ${row.helpKey}")
            }
        }
    }

    // ---- card ------------------------------------------------------------------------------

    @Test
    fun theCardRendersOneRowPerCatalogRowInFourGroups() {
        ControlsCard.open()
        val card = byId(ControlsCard.CARD_ID)

        assertNotNull(card)
        assertEquals(4, card.querySelectorAll(".osada-controls-group").length)
        val expected = CommandGroup.entries.sumOf { CommandCatalog.cardRows(it).size }
        assertEquals(expected, card.querySelectorAll(".osada-controls-row").length)
    }

    @Test
    fun everyRenderedRowShowsItsKeyCapAndLocalizedLabel() {
        ControlsCard.open()
        val row = document.querySelector(".osada-controls-row[data-command=\"mount\"]") as? HTMLElement

        assertNotNull(row)
        assertEquals("M", (row.querySelector(".osada-controls-row__cap") as? HTMLElement)?.textContent)
        assertEquals(
            "Mount / Dismount",
            (row.querySelector(".osada-controls-row__label") as? HTMLElement)?.textContent,
        )
    }

    @Test
    fun undoShowsBothOfItsBindings() {
        ControlsCard.open()
        val row = document.querySelector(".osada-controls-row[data-command=\"undo\"]") as? HTMLElement

        assertEquals("U / Ctrl+Z", (row?.querySelector(".osada-controls-row__cap") as? HTMLElement)?.textContent)
    }

    @Test
    fun toggleOpensThenClosesAndLeavesNoNodeBehind() {
        assertFalse(ControlsCard.isOpen())

        ControlsCard.toggle()
        assertTrue(ControlsCard.isOpen())

        ControlsCard.toggle()
        assertFalse(ControlsCard.isOpen())
        assertNull(byId(ControlsCard.CARD_ID))
    }

    @Test
    fun openingTwiceDoesNotStackTwoCards() {
        ControlsCard.open()
        ControlsCard.open()

        assertEquals(1, document.querySelectorAll(".osada-controls-card").length)
    }

    @Test
    fun theCardIsAModalDialogWithFocusOnItsCloseButton() {
        ControlsCard.open()
        val card = byId(ControlsCard.CARD_ID)

        assertNotNull(card)
        assertEquals("dialog", card.getAttribute("role"))
        assertEquals("true", card.getAttribute("aria-modal"))
        assertEquals(
            document.activeElement,
            card.querySelector(".osada-controls-card__close"),
        )
    }

    // ---- chip bridge -------------------------------------------------------------------------

    @Test
    fun anAbsentChipMeansTheCommandDoesNotApplyAndIsNotConsumed() {
        buildStrip(emptyList())

        assertEquals(UnitCommandBridge.Outcome.ABSENT, UnitCommandBridge.activate("mount"))
    }

    @Test
    fun anEnabledChipIsClickedThroughItsOwnHandler() {
        var clicks = 0
        val chip = buildStrip(listOf("mount" to true)).first()
        chip.onclick = { _ ->
            clicks++
            Unit
        }

        assertEquals(UnitCommandBridge.Outcome.RAN, UnitCommandBridge.activate("mount"))
        assertEquals(1, clicks)
    }

    @Test
    fun aBlockedChipIsFocusedRatherThanClicked() {
        var clicks = 0
        val chip = buildStrip(listOf("resupply" to false)).first()
        chip.onclick = { _ ->
            clicks++
            Unit
        }

        assertEquals(UnitCommandBridge.Outcome.BLOCKED, UnitCommandBridge.activate("resupply"))
        assertEquals(0, clicks, "a blocked command must not change state")
        assertEquals(document.activeElement, chip, "focus opens the chip's own reason panel")
    }

    /** A minimal stand-in for `UnitContextButtons`' strip: the bridge only reads `data-action` and
     *  `aria-disabled`, which is exactly the contract the real strip publishes. */
    private fun buildStrip(chips: List<Pair<String, Boolean>>): List<HTMLElement> {
        delTag(byId("unit-context"))
        val host = ensure("unit-context")
        val row = document.createElement("div") as HTMLElement
        row.className = "osada-actions"
        host.appendChild(row)
        return chips.map { (action, enabled) ->
            val chip = document.createElement("div") as HTMLElement
            chip.className = "osada-action"
            chip.setAttribute("data-action", action)
            chip.setAttribute("aria-disabled", (!enabled).toString())
            chip.setAttribute("tabindex", if (enabled) "0" else "-1")
            row.appendChild(chip)
            chip
        }
    }
}
