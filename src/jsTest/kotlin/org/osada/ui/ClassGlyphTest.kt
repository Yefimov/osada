package org.osada.ui

import kotlinx.browser.document
import org.osada.UnitClass
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClassGlyphTest {
    @Test
    fun aMergedClassWearsTheIconOfTheTabItIsListedUnder() {
        assertEquals("9", ClassGlyph.tabFor(UnitClass.FLAK.value), "Flak lives under Air defence")
        assertEquals("15", ClassGlyph.tabFor(UnitClass.SUBMARINE.value), "every naval class under Naval")
        assertEquals("1", ClassGlyph.tabFor(UnitClass.INFANTRY.value))
        assertNull(ClassGlyph.tabFor(UnitClass.GROUND_TRANSPORT.value), "transports have no tab")
    }

    @Test
    fun fortificationGetsAnSvgTowerAndTheOthersTheirFontGlyph() {
        val fort = document.createElement("span") as HTMLElement
        assertTrue(ClassGlyph.render(fort, UnitClass.FORTIFICATION.value.toString()))
        assertTrue(fort.querySelector("svg") != null)

        val tank = document.createElement("span") as HTMLElement
        assertTrue(ClassGlyph.render(tank, UnitClass.TANK.value.toString()))
        assertEquals("]", tank.textContent)

        val all = document.createElement("span") as HTMLElement
        assertFalse(ClassGlyph.render(all, UnitClass.NONE.value.toString()), "the All tab has no icon")
    }
}
