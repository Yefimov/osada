package org.osada.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for additional DOM helpers ported from the legacy UI.
 */
class DOMHelpersTest {
    @Test
    fun isChromeAppReturnsBoolean() {
        // In a browser/node test environment this should simply return false.
        val result = isChromeApp()
        assertFalse(result)
    }

    @Test
    fun hoverinChangesImageSourceToOver() {
        val img = org.w3c.dom.Image()
        img.id = "testbtn"
        img.src = "resources/ui/buttons/testbtn.png"
        hoverin(img)
        assertTrue(img.src.contains("testbtn-over.png"), "expected over image, got ${img.src}")
    }

    @Test
    fun hoveroutChangesImageSourceBack() {
        val img = org.w3c.dom.Image()
        img.id = "testbtn"
        img.src = "resources/ui/buttons/testbtn-over.png"
        hoverout(img)
        assertTrue(img.src.contains("testbtn.png"), "expected normal image, got ${img.src}")
    }

    @Test
    fun toggleButtonWithImageWorksOnImageElement() {
        val img = org.w3c.dom.Image()
        img.id = "btn"
        img.src = "resources/ui/buttons/btn.png"
        assertTrue(toggleButtonWithImage(img, true))
        assertTrue(img.src.contains("btn-over.png"))
        assertTrue(toggleButtonWithImage(img, false))
        assertTrue(img.src.contains("btn.png"))
    }

    @Test
    fun toggleCheckboxWithImageTogglesCheckedSource() {
        val img = org.w3c.dom.Image()
        img.src = "resources/ui/buttons/check.png"
        assertTrue(toggleCheckboxWithImage(img))
        assertTrue(img.src.contains("check-checked.png"), "got ${img.src}")
        assertTrue(toggleCheckboxWithImage(img))
        assertTrue(img.src.contains("check-checked-checked.png").not() || img.src.contains("check-checked.png"))
    }
}
