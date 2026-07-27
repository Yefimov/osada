package org.osada.ui

import kotlinx.browser.document
import org.osada.hero.HeroId
import org.osada.hero.HeroPromotionAnnouncement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The interaction contracts the three `--z-msg` dialogs were rebuilt on (DEFERRED.md §4.13, §4.14,
 * §4.17). All three defects were invisible to the existing suite because they live in DOM wiring
 * rather than in rules: an ARIA role with no key handler, a teardown path that dropped a layer, and
 * a dialog id assigned N times.
 */
class DialogInteractionTest {
    @BeforeTest
    fun setup() {
        listOf("mainbody", "equipment").forEach { id ->
            if (byId(id) == null) {
                val container = document.createElement("div") as HTMLElement
                container.id = id
                document.body?.appendChild(container)
            }
        }
        cleanUpDialogs()
    }

    @AfterTest
    fun tearDown() = cleanUpDialogs()

    private fun cleanUpDialogs() {
        listOf("uiAttachmentBox", "uiHeroPromotionBox", "uiHeroTransferBox", "uiCommanderRoster").forEach {
            delTag(byId(it))
        }
    }

    private fun press(
        element: HTMLElement,
        key: String,
    ) {
        element.dispatchEvent(
            KeyboardEvent("keydown", KeyboardEventInit(key = key, bubbles = true, cancelable = true)),
        )
    }

    // ---- §4.14: role="button" must come with the handler the role promises ----

    @Test
    fun asButtonActivatesOnEnterAndSpaceAndClick() {
        val element = addTag(byId("mainbody"), "div")
        var activations = 0
        element.asButton { activations++ }

        assertEquals("button", element.getAttribute("role"))
        assertEquals("0", element.getAttribute("tabindex"), "must be reachable by keyboard at all")

        press(element, "Enter")
        assertEquals(1, activations, "Enter must activate an element announced as a button")
        press(element, " ")
        assertEquals(2, activations, "Space must activate an element announced as a button")
        element.asDynamic().click()
        assertEquals(3, activations, "the mouse path must keep working")

        delTag(element)
    }

    @Test
    fun asButtonIgnoresOtherKeysAndConsumesTheOnesItHandles() {
        val element = addTag(byId("mainbody"), "div")
        var activations = 0
        element.asButton { activations++ }

        press(element, "a")
        press(element, "Escape")
        assertEquals(0, activations, "only Enter/Space activate; Escape belongs to the global handler")

        // Space must be consumed, or it also scrolls the page underneath the dialog.
        val consumed =
            KeyboardEvent("keydown", KeyboardEventInit(key = " ", bubbles = true, cancelable = true))
                .let {
                    element.dispatchEvent(it)
                    it.defaultPrevented
                }
        assertTrue(consumed, "Space must be preventDefault()ed so it does not also scroll the page")

        delTag(element)
    }

    @Test
    fun asButtonSetsAriaLabelOnlyWhenGiven() {
        val labelled = addTag(byId("mainbody"), "div")
        labelled.asButton(ariaLabel = "Close") { }
        assertEquals("Close", labelled.getAttribute("aria-label"))

        val plain = addTag(byId("mainbody"), "div")
        plain.textContent = "Done"
        plain.asButton { }
        assertNull(plain.getAttribute("aria-label"), "text-labelled controls must not get a redundant label")

        delTag(labelled)
        delTag(plain)
    }

    // ---- §4.13: closing the equipment window must take the picker layered over it with it ----

    @Test
    fun hidingEquipmentAlsoClosesTheAttachmentPicker() {
        byId("equipment")?.style?.display = "grid"
        val picker = addTag(byId("mainbody"), "div")
        picker.id = "uiAttachmentBox"
        assertTrue(AttachmentPickerPresenter.isOpen(), "fixture must start with the picker open")

        hideEquipmentWindow()

        assertFalse(
            AttachmentPickerPresenter.isOpen(),
            "the picker is attached to mainbody at --z-msg, so hiding #equipment alone used to " +
                "orphan a live prestige-spending modal over the bare map (DEFERRED.md §4.13)",
        )
        assertFalse(isVisible("equipment"))
    }

    // ---- §4.17: two promotions from one combat are two dialogs, shown one at a time ----

    private fun announcement(
        hero: String,
        trait: String,
    ) = HeroPromotionAnnouncement(
        heroId = HeroId(hero),
        formationName = "1st Rifle",
        heroName = "Test Officer",
        newRankId = "major",
        choices = listOf(HeroPromotionAnnouncement.Choice(trait, "Title", "Effect", "Justification")),
    )

    @Test
    fun promotionsQueueInsteadOfStackingOnOneElementId() {
        HeroPromotionPresenter.present(listOf(announcement("a", "t1"), announcement("b", "t2")))

        assertEquals(
            1,
            document.querySelectorAll("#uiHeroPromotionBox").length,
            "two announcements used to build two nodes carrying the same id, stacked at the same " +
                "centring transform (DEFERRED.md §4.17)",
        )

        val first = byId("uiHeroPromotionBox")
        assertNotNull(first)
        val choice = first.querySelector(".osada-hpp__choice") as? HTMLElement
        assertNotNull(choice, "the choice row must exist and be activatable")
        choice.asDynamic().click()

        assertEquals(1, document.querySelectorAll("#uiHeroPromotionBox").length, "the queued second promotion follows")
        val second = byId("uiHeroPromotionBox")
        assertNotNull(second)
        (second.querySelector(".osada-hpp__choice") as HTMLElement).asDynamic().click()

        assertFalse(HeroPromotionPresenter.isOpen(), "nothing is left on screen once both are answered")
    }

    @Test
    fun promotionChoicesAreKeyboardActivatable() {
        HeroPromotionPresenter.present(listOf(announcement("c", "t3")))
        val choice = byId("uiHeroPromotionBox")?.querySelector(".osada-hpp__choice") as? HTMLElement
        assertNotNull(choice)
        assertEquals("button", choice.getAttribute("role"))

        press(choice, "Enter")

        assertFalse(HeroPromotionPresenter.isOpen(), "Enter must take the choice, not merely focus it (§4.14)")
    }
}
