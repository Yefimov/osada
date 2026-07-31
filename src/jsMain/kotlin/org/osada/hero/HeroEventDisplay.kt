package org.osada.hero

/** Human-readable service/formation history text, kept separate from the dossier assembler. */
internal object HeroEventDisplay {
    private const val ISO_PARTS = 3
    private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /**
     * Fixed event ids, as a table rather than a `when` chain: every [AchievementType] lands here via
     * its lowercased name, so the chain grew one branch per achievement and eventually tripped
     * detekt's complexity limit. A map keeps adding an achievement a one-line change.
     */
    private val titles =
        mapOf(
            "destroyed_enemy" to "Destroyed an enemy formation",
            "destroyed_stronger_enemy" to "Destroyed a stronger enemy formation",
            "armored_kill" to "Destroyed enemy armour",
            "held_under_attack" to "Held the position under heavy attack",
            "survived_critical_damage" to "Kept the formation fighting after severe losses",
            "river_assault" to "Distinguished in river combat",
            "urban_assault" to "Distinguished in urban combat",
            "forest_assault" to "Distinguished in forest combat",
            "mountain_assault" to "Distinguished in mountain combat",
            "ground_attack_kill" to "Destroyed a ground target from the air",
            "maneuver_kill" to "Closed the distance and destroyed the enemy",
            "distinguished_service" to "Recognised for distinguished service",
        )

    fun title(eventId: String): String =
        titles[eventId] ?: when {
            eventId.startsWith("commander_promoted_to_") ->
                "Commander promoted to ${HeroDisplay.rank(eventId.removePrefix("commander_promoted_to_"))}"

            eventId.startsWith("promoted_to_") ->
                "Promoted to ${HeroDisplay.rank(eventId.removePrefix("promoted_to_"))}"

            else -> eventId.replace('_', ' ').replaceFirstChar(Char::uppercaseChar)
        }

    fun context(
        scenarioId: String,
        turn: Int,
        date: String?,
        location: String?,
    ): String {
        val place = location?.takeIf(String::isNotBlank)?.let { " at $it" }.orEmpty()
        val scenario =
            scenarioId.takeIf(String::isNotBlank)?.let {
                if (it.all(Char::isDigit)) "scenario $it" else it
            }
        val timing = listOfNotNull(scenario, formatIsoDate(date), "turn $turn").joinToString(", ")
        return "$place${if (timing.isNotBlank()) " — $timing" else ""}"
    }

    @Suppress("ComplexCondition")
    private fun formatIsoDate(date: String?): String? {
        val parts = date?.split('-')
        val monthIndex = parts?.getOrNull(1)?.toIntOrNull()?.minus(1)
        return if (parts != null && parts.size == ISO_PARTS && monthIndex != null && monthIndex in months.indices) {
            "${parts[2].toIntOrNull() ?: parts[2]} ${months[monthIndex]} ${parts[0]}"
        } else {
            date
        }
    }
}
