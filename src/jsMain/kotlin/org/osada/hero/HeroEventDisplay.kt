package org.osada.hero

import org.osada.i18n.CalendarText
import org.osada.i18n.GameText
import org.osada.i18n.I18n

/** Human-readable service/formation history text, kept separate from the dossier assembler. */
internal object HeroEventDisplay {
    private const val ISO_PARTS = 3

    /** Valid 0-based month indices, for an ISO date whose month has already been decremented. */
    private val MONTH_RANGE = 0..11

    /**
     * Fixed event ids, as a table rather than a `when` chain: every [AchievementType] lands here via
     * its lowercased name, so the chain grew one branch per achievement and eventually tripped
     * detekt's complexity limit. A map keeps adding an achievement a one-line change.
     */
    private val titleIds =
        setOf(
            "destroyed_enemy",
            "destroyed_stronger_enemy",
            "armored_kill",
            "held_under_attack",
            "survived_critical_damage",
            "river_assault",
            "urban_assault",
            "forest_assault",
            "mountain_assault",
            "ground_attack_kill",
            "maneuver_kill",
            "distinguished_service",
            // Appointment events (biography design §5.3). `emerged` is the originating command and
            // `returned` a posting back to a formation this officer has held before; both carry a
            // formation id, which is what [HeroFamiliarity] reads.
            "emerged",
            "returned",
            "transferred",
        )

    fun title(eventId: String): String =
        if (eventId in titleIds) {
            I18n.t("hero.event.$eventId")
        } else {
            when {
                eventId.startsWith("commander_promoted_to_") ->
                    I18n.t(
                        "hero.event.commander_promoted",
                        mapOf("rank" to HeroDisplay.rank(eventId.removePrefix("commander_promoted_to_"))),
                    )

                eventId.startsWith("promoted_to_") ->
                    I18n.t(
                        "hero.event.promoted",
                        mapOf("rank" to HeroDisplay.rank(eventId.removePrefix("promoted_to_"))),
                    )

                eventId.startsWith("commander_") ->
                    I18n.tOrNull("hero.event.$eventId")
                        ?: I18n.t(
                            "hero.event.commander_status",
                            mapOf("status" to eventId.removePrefix("commander_").replace('_', ' ')),
                        )

                else -> eventId.replace('_', ' ').replaceFirstChar(Char::uppercaseChar)
            }
        }

    fun context(
        scenarioId: String,
        turn: Int,
        date: String?,
        location: String?,
    ): String {
        val place =
            location
                ?.takeIf(String::isNotBlank)
                ?.let { I18n.t("hero.event.context.location", mapOf("location" to it)) }
                .orEmpty()
        val scenario =
            scenarioId.takeIf(String::isNotBlank)?.let {
                if (it.all(Char::isDigit)) I18n.t("hero.event.context.scenario", mapOf("scenario" to it)) else it
            }
        val timing =
            listOfNotNull(
                scenario,
                formatIsoDate(date),
                I18n.t("hero.event.context.turn", mapOf("turn" to turn)),
            ).joinToString(", ")
        return "$place${if (timing.isNotBlank()) " — $timing" else ""}"
    }

    @Suppress("ReturnCount") // an unparseable date and an impossible month both fall back to the raw text
    private fun formatIsoDate(date: String?): String? {
        val parsed = CalendarText.parseIso(date) ?: return date
        val (year, month, day) = parsed
        val monthIndex = month - 1
        if (monthIndex !in MONTH_RANGE) return date
        return "$day ${GameText.monthShort(monthIndex)} ${CalendarText.year(year)}"
    }
}
