package org.osada

import org.osada.i18n.CalendarText
import org.osada.i18n.installEnglishUiBundleForTests
import kotlin.js.Date
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Years before 1, which the Spartacus campaign actually ships.
 *
 * Three separate things broke on a negative year and each broke differently — the HUD printed
 * `-73`, the stored ISO date split into four parts so every reader fell through to its raw-text
 * fallback, and `Date.parse` could not read the authored attribute at all. Each has a test here.
 */
class CalendarTextTest {
    @BeforeTest
    fun setup() {
        installEnglishUiBundleForTests()
    }

    @Test
    fun aYearBeforeOneReadsAsAnEraRatherThanAMinusSign() {
        assertEquals("1942", CalendarText.year(1942))
        assertEquals("73 BC", CalendarText.year(-73))
        assertEquals("99 BC", CalendarText.year(-99))
    }

    @Test
    fun anIsoDateRoundTripsThroughItsOwnParserInBothEras() {
        listOf(Triple(1942, 8, 24), Triple(-73, 8, 1), Triple(-71, 5, 15)).forEach { (year, month, day) ->
            val text = CalendarText.isoDate(year, month, day)
            assertEquals(Triple(year, month, day), CalendarText.parseIso(text), text)
        }
    }

    @Test
    fun aNegativeYearIsPaddedSoTheStringStillLooksLikeADate() {
        assertEquals("-0073-08-01", CalendarText.isoDate(-73, 8, 1))
        assertEquals("1942-08-24", CalendarText.isoDate(1942, 8, 24))
    }

    @Test
    fun aMalformedDateParsesToNothingRatherThanToAWrongDate() {
        assertNull(CalendarText.parseIso(null))
        assertNull(CalendarText.parseIso(""))
        assertNull(CalendarText.parseIso("August 1, -73"))
        assertNull(CalendarText.parseIso("1942-08"))
    }

    @Test
    fun anAuthoredBcDateIsParsedWhereDateParseCannotReadItAtAll() {
        // The reason `parseAuthoredDate` exists: `Date.parse` cannot read a BC year in any engine,
        // and an invalid Date reports NaN for every field -- which is how the Spartacus campaign
        // came to be dated 2000-2002 by its importer.
        assertEquals(true, Date.parse("August 1, -73").isNaN(), "Date.parse must be assumed to fail here")

        val date = CalendarText.parseAuthoredDate("August 1, -73")

        assertEquals(-73, date.getFullYear())
        assertEquals(7, date.getMonth(), "August")
        assertEquals(1, date.getDate())
    }

    @Test
    fun anOrdinaryAuthoredDateIsUnaffected() {
        val date = CalendarText.parseAuthoredDate("August 24, 1943")

        assertEquals(1943, date.getFullYear())
        assertEquals(7, date.getMonth())
        assertEquals(24, date.getDate())
    }
}
