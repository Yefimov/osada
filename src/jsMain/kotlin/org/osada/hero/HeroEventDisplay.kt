package org.osada.hero

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

    @Suppress("ComplexCondition")
    private fun formatIsoDate(date: String?): String? {
        val parts = date?.split('-')
        val monthIndex = parts?.getOrNull(1)?.toIntOrNull()?.minus(1)
        return if (parts != null && parts.size == ISO_PARTS && monthIndex != null && monthIndex in MONTH_RANGE) {
            "${parts[2].toIntOrNull() ?: parts[2]} ${GameText.monthShort(monthIndex)} ${parts[0]}"
        } else {
            date
        }
    }
}
