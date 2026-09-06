package org.osada.ui

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The queued-popup surface ([MessageDialogs.messageDynamic]) and, specifically, what happens to it
 * when the player leaves the battle for the pause/main menu.
 *
 * These boxes live on `--z-msg` (1000) and `#startmenu` on 300, so a hero-emergence announcement
 * still on screen when the menu opened floated OVER the menu and stayed there — the 2026-09-05
 * report. The menu now suspends the queue instead, which must not LOSE the announcement: the box
 * goes back to the head of the queue and reopens on the way back into the battle.
 */
class MessageDialogQueueTest {
    @BeforeTest
    fun setup() {
        if (byId("mainbody") == null) {
            val body = document.createElement("div") as HTMLElement
            body.id = "mainbody"
            document.body?.appendChild(body)
        }
        MessageDialogs.clearDynamicMessages()
    }

    @AfterTest
    fun teardown() = MessageDialogs.clearDynamicMessages()

    private fun boxTitle(): String? = byId("uiMessageBoxDynamic")?.firstElementChild?.textContent

    @Test
    fun aQueuedBoxIsShownAndTheNextOneWaitsItsTurn() {
        MessageDialogs.messageDynamic("First", "body one")
        MessageDialogs.messageDynamic("Second", "body two")

        assertEquals("First", boxTitle())
        assertTrue(MessageDialogs.isDynamicMessageOpen())

        MessageDialogs.dismissDynamicMessage()
        assertEquals("Second", boxTitle())
    }

    @Test
    fun suspendingTakesTheBoxOffScreenAndResumingBringsTheSameOneBack() {
        MessageDialogs.messageDynamic("Hero", "a commander emerged")
        MessageDialogs.messageDynamic("Casualty", "a commander fell")
        assertEquals("Hero", boxTitle())

        MessageDialogs.suspendDynamicMessages()
        assertNull(byId("uiMessageBoxDynamic"), "the popup must not stay over the menu")
        assertTrue(!MessageDialogs.isDynamicMessageOpen())

        MessageDialogs.resumeDynamicMessages()
        assertEquals("Hero", boxTitle(), "the unread announcement must come back, not be skipped")

        MessageDialogs.dismissDynamicMessage()
        assertEquals("Casualty", boxTitle(), "and the rest of the queue must still follow it")
    }

    @Test
    fun nothingQueuesUpBehindTheMenuWhileTheQueueIsSuspended() {
        MessageDialogs.suspendDynamicMessages()
        MessageDialogs.messageDynamic("Arrived while paused", "body")

        assertNull(byId("uiMessageBoxDynamic"), "a new announcement must wait too, not jump the menu")

        MessageDialogs.resumeDynamicMessages()
        assertEquals("Arrived while paused", boxTitle())
    }

    @Test
    fun scenarioTeardownClearsTheSuspensionAlongWithTheQueue() {
        MessageDialogs.messageDynamic("Previous battle", "body")
        MessageDialogs.suspendDynamicMessages()

        MessageDialogs.clearDynamicMessages()
        MessageDialogs.messageDynamic("Next battle", "body")

        assertNotNull(byId("uiMessageBoxDynamic"), "a stale suspension must not mute the next scenario")
        assertEquals("Next battle", boxTitle())
    }
}
