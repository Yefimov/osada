package org.osada.i18n

import kotlin.js.Date

/**
 * Calendar years and ISO dates, including the ones before year 1.
 *
 * ## Why this exists
 *
 * The Spartacus campaign is a shipped campaign set in 73-71 BC, and until now every part of the
 * game that touched a date quietly assumed a positive four-digit year. Three separate things broke
 * on a negative one and each broke differently:
 *
 * - the HUD printed `-73`;
 * - `"$year-$month-$day"` produced `-73-08-01`, which splits on `-` into FOUR parts, so every
 *   reader of that string fell through to its raw-text fallback;
 * - `Date.toDateString()` rendered an implementation-defined expanded year.
 *
 * One place now owns both directions of the conversion, so a reader and a writer cannot disagree.
 *
 * ## The year numbering
 *
 * A negative year means "that many years BC", so **-73 is 73 BC**. That is the convention already
 * used by the authored content ([org.osada.hero.HeroPortraitArt]'s ancient era is `-73..-71`, and
 * the roster's Spartacus hero spans the same range), and it is deliberately NOT astronomical year
 * numbering, in which 73 BC would be -72. Nothing here needs a year 0 and nothing authors one.
 */
internal object CalendarText {
    private const val ISO_PARTS = 3
    private const val ISO_YEAR_DIGITS = 4

    /** A trailing `-73` in an authored `date` attribute: the year, in years BC. */
    private val AUTHORED_NEGATIVE_YEAR = Regex("""-(\d{1,6})\s*$""")

    /** Any year `Date.parse` handles; it is overwritten immediately and never observed. */
    private const val DATE_PARSE_PLACEHOLDER_YEAR = 2000

    /**
     * A calendar year as text: `1942`, or `73 BC` through a localized template.
     *
     * The era marker goes through the bundle rather than being appended here, because its wording
     * and its position relative to the number are both translation decisions — Russian puts
     * "до н. э." after the number, but a language that prefixes it must be able to say so.
     */
    fun year(year: Int): String =
        if (year < 0) {
            I18n.t("calendar.year.bce", mapOf("year" to (-year).toString()))
        } else {
            year.toString()
        }

    /**
     * `1942-08-24`, or `-0073-08-01` for a year before 1.
     *
     * The sign is kept and the digits are padded to four so the string sorts and reads like an ISO
     * date; [parseIso] is the only thing that has to understand it, and it is written to.
     */
    fun isoDate(
        year: Int,
        month: Int,
        day: Int,
    ): String {
        val sign = if (year < 0) "-" else ""
        val digits = (if (year < 0) -year else year).toString().padStart(ISO_YEAR_DIGITS, '0')
        return "$sign$digits-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
    }

    /**
     * [isoDate]'s inverse, or null when the text is not one.
     *
     * Splitting on `-` is what the previous readers did and is exactly what a negative year breaks,
     * so the sign is taken off the front FIRST and put back on the parsed number.
     */
    @Suppress("ReturnCount") // one guard per malformed part; nesting five checks reads far worse
    fun parseIso(text: String?): Triple<Int, Int, Int>? {
        val raw = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val negative = raw.startsWith("-")
        val parts = raw.removePrefix("-").split("-")
        if (parts.size != ISO_PARTS) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].take(2).toIntOrNull() ?: return null
        return Triple(if (negative) -year else year, month, day)
    }

    /**
     * A scenario's authored `date` attribute, including one before year 1.
     *
     * `Date.parse` cannot read a BC year in any engine — `"August 1, -73"` yields `NaN`, and an
     * invalid Date then reports `NaN` for every field. That is how the Spartacus campaign came to
     * be dated 2000-2002 by its importer in the first place: a placeholder year was the only thing
     * the pipeline could store. So a trailing negative year is taken off, the rest is parsed as an
     * ordinary date, and the year is written back with `setFullYear` — which does accept a negative
     * year and is NOT subject to the 0-99 => 1900-1999 remapping the `Date` CONSTRUCTOR applies.
     *
     * Parsing the remainder in a placeholder year keeps the engine's own local-time semantics, so a
     * BC scenario and a 1942 one are built the same way and cannot differ by a timezone day.
     */
    @Suppress("ReturnCount") // no negative year, and an unreadable one, both mean "parse it normally"
    fun parseAuthoredDate(raw: String): Date {
        val trimmed = raw.trim()
        val negativeYear = AUTHORED_NEGATIVE_YEAR.find(trimmed) ?: return Date(Date.parse(trimmed))
        val year = negativeYear.groupValues[1].toIntOrNull() ?: return Date(Date.parse(trimmed))
        val withoutYear = trimmed.removeRange(negativeYear.range).trimEnd(',', ' ')
        val date = Date(Date.parse("$withoutYear, $DATE_PARSE_PLACEHOLDER_YEAR"))
        date.asDynamic().setFullYear(-year)
        return date
    }
}
