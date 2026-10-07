package org.osada.ui.campaignmap

/**
 * Pure readers over an authored campaign intro (`"Title<br><br>2 July 1939<br><br>Comrade…"`): its
 * heading line, its year and a plain-text opening short enough for the map's detail panel.
 */
internal object CampaignIntroText {
    private const val SUMMARY_LIMIT = 360
    private val BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<[^>]*>""")
    private val SPACE = Regex("""\s+""")
    private val BC_YEAR = Regex("""\b(\d{1,4})\s*B\.?\s?C\b""")

    /** 1700-2099: wide enough for every shipped campaign, narrow enough to skip "1500 men". */
    private val AD_YEAR = Regex("""\b(1[7-9]\d\d|20\d\d)\b""")

    /** The intro's paragraphs as plain text, blank ones dropped. */
    fun lines(html: String?): List<String> =
        html
            .orEmpty()
            .split(BREAK)
            .map(::plain)
            .filter { it.isNotEmpty() }

    fun heading(html: String?): String? = lines(html).firstOrNull()

    fun plain(html: String): String =
        html
            .replace(TAG, " ")
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace(SPACE, " ")
            .trim()

    /** The first calendar year the text names; negative for a year BC (`CalendarText`'s convention). */
    fun year(text: String?): Int? {
        val source = text.orEmpty()
        BC_YEAR.find(source)?.let { return -it.groupValues[1].toInt() }
        return AD_YEAR
            .find(source)
            ?.groupValues
            ?.get(1)
            ?.toInt()
    }

    /** The intro without its heading line, one paragraph per line, cut at a word boundary near
     *  [SUMMARY_LIMIT] characters. */
    fun summary(
        html: String?,
        title: String,
    ): String {
        val all = lines(html)
        val body = if (all.firstOrNull()?.let { sameHeading(it, title) } == true) all.drop(1) else all
        val text = body.joinToString("\n")
        if (text.length <= SUMMARY_LIMIT) return text
        val cut = text.lastIndexOf(' ', SUMMARY_LIMIT).takeIf { it > SUMMARY_LIMIT / 2 } ?: SUMMARY_LIMIT
        return text.substring(0, cut).trimEnd(',', ';', ':', ' ') + "…"
    }

    /** A heading repeats the title when it is the title, or the title plus a dated tail ("TITLE - 3 NOV 1940"). */
    private fun sameHeading(
        heading: String,
        title: String,
    ): Boolean = heading.lowercase().startsWith(title.lowercase()) || title.lowercase().startsWith(heading.lowercase())
}
