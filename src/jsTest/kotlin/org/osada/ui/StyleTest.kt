package org.osada.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for canvas rendering styles ported from style.js.
 */
class StyleTest {
    @Test
    fun hexStylesHaveExpectedColors() {
        assertEquals("rgba(128,128,128,0.5)", HexStyles.move.fillColor)
        assertEquals("rgba(239,0,0,0.8)", HexStyles.attack.lineColor)
        assertEquals(3.0, HexStyles.current.lineWidth)
        assertEquals("round", HexStyles.current.lineJoin)
        assertEquals(null, HexStyles.generic.fillColor)
    }

    @Test
    fun hexStyleLookupWorks() {
        assertNotNull(HexStyles.byName("move"))
        assertNotNull(HexStyles.byName("attack"))
        assertNotNull(HexStyles.byName("current"))
        assertNotNull(HexStyles.byName("generic"))
        assertNotNull(HexStyles.byName("deploy"))
        assertNotNull(HexStyles.byName("ownunit"))
        assertNull(HexStyles.byName("unknown"))
    }

    @Test
    fun unitStyleHasExpectedColors() {
        assertEquals("#383838", unitStyle.axisBox)
        assertEquals("#808000", unitStyle.alliedBox)
        assertEquals("white", unitStyle.playerText)
        assertEquals("#696969", unitStyle.alliedPlayerText)
        assertEquals("#A9A9A9", unitStyle.movedUnitText)
    }
}
