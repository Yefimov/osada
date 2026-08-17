package org.osada.ui

import kotlinx.browser.document
import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.i18n.installEnglishUiBundleForTests
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.InterceptionEvent
import org.osada.model.Player
import org.osada.model.resetEquipment
import org.w3c.dom.HTMLElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The non-modal AA-interception event (`docs/player-comfort-roadmap.md` P1).
 *
 * The rules that must not regress: the banner reports an interception that ALREADY happened and
 * never anything before it, it never takes focus, and it stays out of a third party's business.
 */
class InterceptionBannerTest {
    private val flakEqid = 1
    private val planeEqid = 2

    private val friendly =
        Player().apply {
            id = 0
            side = 0
        }
    private val enemy =
        Player().apply {
            id = 1
            side = 1
        }

    @BeforeTest
    fun setup() {
        installEnglishUiBundleForTests()
        Equipment.resetEquipment()
        Equipment.putEquipment(
            flakEqid,
            EquipmentData().apply {
                name = "8.8cm Flak 36"
                uclass = UnitClass.AIR_DEFENCE.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.WHEELED.value
            },
        )
        Equipment.putEquipment(
            planeEqid,
            EquipmentData().apply {
                name = "Il-2"
                uclass = UnitClass.TACTICAL_BOMBER.value
                target = UnitType.AIR.value
                movmethod = MovMethod.AIR.value
            },
        )
        ensureHost()
        InterceptionBanner.hide()
    }

    @AfterTest
    fun tearDown() {
        InterceptionBanner.hide()
        Equipment.resetEquipment()
    }

    private fun ensureHost(): HTMLElement =
        byId("mainbody") ?: (document.createElement("div") as HTMLElement).also {
            it.id = "mainbody"
            document.body?.appendChild(it)
        }

    private fun unit(
        eqid: Int,
        owner: Player,
    ) = GameUnit(eqid).apply {
        this.owner = owner.id
        player = owner
        strength = 10
    }

    private fun event(
        gunOwner: Player,
        planeOwner: Player,
        losses: Int = 3,
        destroyed: Boolean = false,
    ) = InterceptionEvent(unit(flakEqid, gunOwner), unit(planeEqid, planeOwner), losses, destroyed)

    private fun banner(): HTMLElement? = byId(InterceptionBanner.BANNER_ID)

    @Test
    fun theBannerNamesTheGunTheAircraftAndTheLosses() {
        val shown = InterceptionBanner.show(uiStub(), listOf(event(enemy, friendly, losses = 3)), observerSide = 0)

        assertTrue(shown)
        val text = banner()?.textContent
        assertNotNull(text)
        assertTrue(text.contains("8.8cm Flak 36"), text)
        assertTrue(text.contains("Il-2"), text)
        assertTrue(text.contains("3"), text)
    }

    @Test
    fun aDestroyedAircraftReadsAsDestroyedRatherThanAsZeroLosses() {
        InterceptionBanner.show(
            uiStub(),
            listOf(event(enemy, friendly, losses = 4, destroyed = true)),
            observerSide = 0,
        )

        val text = banner()?.textContent
        assertNotNull(text)
        assertTrue(text.contains("destroyed"), text)
    }

    @Test
    fun anInterceptionInvolvingNeitherSideOfTheObserverIsNotTheirEvent() {
        val thirdParty =
            Player().apply {
                id = 2
                side = 1
            }

        val shown = InterceptionBanner.show(uiStub(), listOf(event(thirdParty, enemy)), observerSide = 0)

        assertFalse(shown)
        assertEquals(null, banner())
    }

    @Test
    fun theObserversOwnGunScoringIsAlsoReported() {
        val shown = InterceptionBanner.show(uiStub(), listOf(event(friendly, enemy)), observerSide = 0)

        assertTrue(shown, "the player's own AA hitting an enemy plane is still their event")
    }

    @Test
    fun theBannerIsALiveRegionAndNotAModalDialog() {
        InterceptionBanner.show(uiStub(), listOf(event(enemy, friendly)), observerSide = 0)
        val node = banner()

        assertNotNull(node)
        assertEquals("status", node.getAttribute("role"))
        assertEquals("polite", node.getAttribute("aria-live"))
        assertEquals(null, node.getAttribute("aria-modal"), "an event report must not claim to be modal")
        assertFalse(node.hasAttribute("tabindex"), "it must not enter the tab order")
    }

    @Test
    fun anEmptyEventListShowsNothingAtAll() {
        assertFalse(InterceptionBanner.show(uiStub(), emptyList(), observerSide = 0))
        assertEquals(null, banner(), "nothing is published until a gun has actually fired")
    }

    @Test
    fun showingAgainReplacesTheBannerRatherThanStackingTwo() {
        InterceptionBanner.show(uiStub(), listOf(event(enemy, friendly)), observerSide = 0)
        InterceptionBanner.show(uiStub(), listOf(event(enemy, friendly)), observerSide = 0)

        assertEquals(1, document.querySelectorAll(".osada-intercept-banner").length)
    }

    /** The banner only needs [UI] to centre the map on click, which these tests never exercise. */
    private fun uiStub(): UI = null.unsafeCast<UI>()
}
