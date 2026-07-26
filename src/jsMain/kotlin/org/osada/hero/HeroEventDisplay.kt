package org.osada.hero

/** Human-readable service/formation history text, kept separate from the dossier assembler. */
internal object HeroEventDisplay {
    private const val ISO_PARTS = 3
    private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    fun title(eventId: String): String =
        when {
            eventId == "destroyed_enemy" -> "Destroyed an enemy formation"
            eventId == "destroyed_stronger_enemy" -> "Destroyed a stronger enemy formation"
            eventId == "armored_kill" -> "Destroyed enemy armour"
            eventId == "held_under_attack" -> "Held the position under heavy attack"
            eventId == "survived_critical_damage" -> "Kept the formation fighting after severe losses"
            eventId == "river_assault" -> "Distinguished in river combat"
            eventId == "urban_assault" -> "Distinguished in urban combat"
            eventId == "forest_assault" -> "Distinguished in forest combat"
            eventId == "mountain_assault" -> "Distinguished in mountain combat"
            eventId == "distinguished_service" -> "Recognised for distinguished service"
            eventId.startsWith("promoted_to_") ->
                "Promoted to ${HeroDisplay.rank(eventId.removePrefix("promoted_to_"))}"
            eventId.startsWith("commander_promoted_to_") ->
                "Commander promoted to ${HeroDisplay.rank(eventId.removePrefix("commander_promoted_to_"))}"
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
