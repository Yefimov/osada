package org.osada.ui

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [UIBuilder.message], [UIBuilder.messageDynamic], [UIBuilder.showAIStatus].
 */
class UIBuilderMessagesTest {
    @BeforeTest
    fun setup() {
        listOf("title", "message", "ui-message", "uiokbut", "mainbody", "statusbar-extension").forEach { id ->
            if (byId(id) == null) {
                val container = document.createElement("div") as HTMLElement
                container.id = id
                document.body?.appendChild(container)
            }
        }
    }

    @Test
    fun messageSetsTitleAndBody() {
        UIBuilder.message("Test Title", "Test Body")
        assertEquals("Test Title", byId("title")?.innerHTML)
        assertEquals("Test Body", byId("message")?.innerHTML)
        assertTrue(byId("uiokbut")?.title?.contains("continue") == true)
    }

    @Test
    fun messageMakesUIMessageVisible() {
        UIBuilder.message("Test", "Body")
        val uiMessage = byId("ui-message")
        assertNotNull(uiMessage)
        assertTrue(isVisible("ui-message"))
    }

    @Test
    fun messageDynamicCreatesMessageBox() {
        UIBuilder.messageDynamic("Dynamic Title", "Dynamic Body", dialogClass = "test-layout")
        val box = byId("uiMessageBoxDynamic")
        assertNotNull(box)
        assertEquals("uiMessageBox test-layout", box.className)
        assertTrue(box.querySelector(".uiMessageBoxButton")?.getAttribute("title")?.contains("continue") == true)
    }

    @Test
    fun showAIStatusSetsActiveState() {
        UIBuilder.showAIStatus(true)
        val status = byId("statusbar-extension")
        assertNotNull(status)
        assertEquals("statusbar-extension-animation", status.className)
        assertTrue(status.innerHTML.contains("Computer turn in progress"))
    }

    @Test
    fun showAIStatusSetsFinishedState() {
        UIBuilder.showAIStatus(false)
        val status = byId("statusbar-extension")
        assertNotNull(status)
        assertEquals("statusbar-extension-animation-reverse", status.className)
        assertTrue(status.innerHTML.contains("Computer turn complete."))
    }
}
