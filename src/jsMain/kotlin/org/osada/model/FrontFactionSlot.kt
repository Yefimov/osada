package org.osada.model

/**
 * One of OG's five per-player **Fronts / Factions** slots: a country, and the two 32-bit masks the
 * scenario author ticked for it.
 *
 * ## Why an ordered LIST and not a map keyed by country
 *
 * OG stores the five slots as the player record's country bytes `+7..+11` (slot 0 is the main
 * country, 1..4 the four support countries) with the masks indexed `player * 5 + slot`, and it
 * **permits the same country in more than one support slot** — the engine's own change log records
 * bugs specifically around that case. A map would silently drop one of the two, and would drop
 * whichever the iteration order happened to reach second.
 *
 * So [Player.frontFactionSlots] keeps OG's own order and duplicates, and
 * [org.osada.rules.FrontsAndFactions] resolves a duplicated country by **any compatible slot**.
 *
 * [country] is OG's own 1-BASED code — the same base [EquipmentData.country], the deployed
 * `support` attribute and `GameUnit.flag` use, and one more than [Player.country].
 *
 * A zero mask is OG's WILDCARD, not an empty set: *"Any unit having front=zero is compatible with
 * any other front, and same for faction"*. A slot with both masks zero therefore says "this country,
 * unrestricted", which the importer only ever writes beside a slot that does restrict something.
 */
data class FrontFactionSlot(
    val country: Int,
    val fronts: Int,
    val factions: Int,
) {
    companion object {
        /** `country:fronts:factions` -- the three fields one deployed slot is written as. */
        private const val FIELDS_PER_SLOT = 3

        /**
         * Parses the deployed `ff` attribute — `country:fronts:factions` per slot, comma-separated,
         * in OG slot order. Malformed entries are dropped rather than guessed at; an entry with no
         * country is leftover editor state (67 such slots exist corpus-wide) and is dropped too.
         */
        fun parse(attribute: String?): List<FrontFactionSlot> =
            attribute
                ?.split(",")
                ?.mapNotNull { entry ->
                    val parts = entry.trim().split(":")
                    if (parts.size != FIELDS_PER_SLOT) return@mapNotNull null
                    val country = parts[0].toIntOrNull() ?: return@mapNotNull null
                    val fronts = parts[1].toIntOrNull() ?: return@mapNotNull null
                    val factions = parts[2].toIntOrNull() ?: return@mapNotNull null
                    if (country <= 0) null else FrontFactionSlot(country, fronts, factions)
                }.orEmpty()

        /** The inverse of [parse], for the save round-trip. */
        fun format(slots: List<FrontFactionSlot>): String =
            slots.joinToString(",") { "${it.country}:${it.fronts}:${it.factions}" }
    }
}
