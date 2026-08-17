package org.osada.ui.keyboard

import kotlinx.browser.document
import org.osada.i18n.installEnglishUiBundleForTests
import org.osada.ui.byId
import org.osada.ui.delTag
import org.osada.ui.makeHidden
import org.osada.ui.makeVisible
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Dispatch rules (`docs/design/keyboard-shortcuts-and-help.md` §§5, 8).
 *
 * These are the failures that are invisible to a rules test and only show up as "typing in the
 * rename box air-dropped my paratroopers": a key press swallowed from a text field, an IME
 * composition treated as a command, or a gameplay command leaking through a modal that owes the
 * player a decision.
 */
class CommandRouterTest {
    @BeforeTest
    fun setup() {
        installEnglishUiBundleForTests()
        val mainBody =
            byId("mainbody") ?: (document.createElement("div") as HTMLElement).also {
                it.id = "mainbody"
                document.body?.appendChild(it)
            }
        // Never hidden: other DOM tests focus elements inside it, and a hidden ancestor makes
        // every descendant unfocusable.
        mainBody.style.display = ""
        listOf("ui-message", "startmenu", "equipment").forEach { id ->
            val node =
                byId(id) ?: (document.createElement("div") as HTMLElement).also {
                    it.id = id
                    document.body?.appendChild(it)
                }
            node.style.display = "none"
        }
        ControlsCard.close()
    }

    @AfterTest
    fun tearDown() {
        ControlsCard.close()
        listOf("ui-message", "startmenu", "equipment").forEach { makeHidden(it) }
        delTag(byId("osadaRouterInput"))
    }

    private fun press(
        key: String,
        code: String = "",
        target: HTMLElement? = null,
        ctrl: Boolean = false,
        repeat: Boolean = false,
    ): KeyboardEvent {
        val event =
            KeyboardEvent(
                "keydown",
                KeyboardEventInit(
                    key = key,
                    code = code,
                    ctrlKey = ctrl,
                    repeat = repeat,
                    bubbles = true,
                    cancelable = true,
                ),
            )
        (target ?: byId("mainbody"))?.dispatchEvent(event)
        return event
    }

    // ---- suppression -------------------------------------------------------------------------

    @Test
    fun aKeyTypedIntoATextFieldIsNotACommand() {
        val input = document.createElement("input") as HTMLElement
        input.id = "osadaRouterInput"
        byId("mainbody")?.appendChild(input)

        assertNull(CommandRouter.resolve(press("m", code = "KeyM", target = input)))
    }

    @Test
    fun aKeyTypedIntoATextareaOrSelectIsNotACommand() {
        listOf("textarea", "select").forEach { tag ->
            val element = document.createElement(tag) as HTMLElement
            byId("mainbody")?.appendChild(element)
            assertNull(
                CommandRouter.resolve(press("s", code = "KeyS", target = element)),
                "$tag must keep its own keys",
            )
            delTag(element)
        }
    }

    @Test
    fun anImeCompositionIsNotACommand() {
        // Browsers report keyCode 229 while composing; some also set isComposing.
        val event =
            KeyboardEvent(
                "keydown",
                KeyboardEventInit(key = "Process", code = "KeyM", bubbles = true, cancelable = true),
            )
        // `keyCode` is a read-only prototype getter; shadow it on the instance the way the browser
        // itself reports a mid-composition press.
        js("Object.defineProperty")(event, "keyCode", js("({ value: 229 })"))

        assertNull(CommandRouter.resolve(event))
    }

    @Test
    fun anAutoRepeatIsIgnoredForToggleCommandsButNotForPanning() {
        assertNull(CommandRouter.resolve(press("a", code = "KeyA", repeat = true)))
        assertEquals(
            CommandCatalog.PAN_LEFT,
            CommandRouter.resolve(press("ArrowLeft", repeat = true))?.id,
        )
    }

    @Test
    fun anOrdinaryPressResolvesNormally() {
        assertEquals(CommandCatalog.MOUNT, CommandRouter.resolve(press("m", code = "KeyM"))?.id)
        assertEquals(
            CommandCatalog.UNDO,
            CommandRouter.resolve(press("z", code = "KeyZ", ctrl = true))?.id,
        )
    }

    // ---- scope precedence ---------------------------------------------------------------------

    @Test
    fun nothingIsOpenMeansTheWholeCatalogIsLive() {
        assertEquals(CommandRouter.Scope.GAMEPLAY, CommandRouter.scope())
    }

    @Test
    fun anOpenMessageBoxSuspendsEverythingButEscape() {
        makeVisible("ui-message")

        val scope = CommandRouter.scope()

        assertEquals(CommandRouter.Scope.CLOSE_ONLY, scope)
        assertFalse(CommandRouter.allowed(CommandCatalog.byId(CommandCatalog.MOUNT)!!, scope))
        assertFalse(CommandRouter.allowed(CommandCatalog.byId(CommandCatalog.HELP)!!, scope))
        assertTrue(CommandRouter.allowed(CommandCatalog.byId(CommandCatalog.CLOSE)!!, scope))
    }

    @Test
    fun theControlsCardItselfTakesPrecedenceOverAnOpenPanel() {
        makeVisible("equipment")
        ControlsCard.open()

        assertEquals(CommandRouter.Scope.CLOSE_ONLY, CommandRouter.scope())
    }

    @Test
    fun theEquipmentWindowLeavesPanelTogglesLiveAndSuspendsUnitCommands() {
        makeVisible("equipment")

        val scope = CommandRouter.scope()

        assertEquals(CommandRouter.Scope.PANEL, scope)
        assertTrue(CommandRouter.allowed(CommandCatalog.byId(CommandCatalog.EQUIPMENT)!!, scope))
        assertTrue(CommandRouter.allowed(CommandCatalog.byId(CommandCatalog.CLOSE)!!, scope))
        assertFalse(CommandRouter.allowed(CommandCatalog.byId(CommandCatalog.MOUNT)!!, scope))
        assertFalse(CommandRouter.allowed(CommandCatalog.byId(CommandCatalog.AIR_MODE)!!, scope))
    }

    @Test
    fun thePauseMenuSuspendsGameplayCommands() {
        makeVisible("startmenu")

        val scope = CommandRouter.scope()

        assertEquals(CommandRouter.Scope.CLOSE_ONLY, scope)
        assertFalse(CommandRouter.allowed(CommandCatalog.byId(CommandCatalog.STRATEGIC_MAP)!!, scope))
    }

    @Test
    fun aBlockingModalOutranksAPanelUnderneathIt() {
        // The §4.13 regression in shortcut form: a modal over the equipment window must not let a
        // panel toggle reach the window behind it.
        makeVisible("equipment")
        makeVisible("ui-message")

        val scope = CommandRouter.scope()

        assertEquals(CommandRouter.Scope.CLOSE_ONLY, scope)
        assertFalse(CommandRouter.allowed(CommandCatalog.byId(CommandCatalog.EQUIPMENT)!!, scope))
    }
}
