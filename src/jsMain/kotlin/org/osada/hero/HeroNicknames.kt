package org.osada.hero

import org.osada.hero.HeroNicknames.displayText


/**
 * Nicknames (§8.1, §10) — assigned once, deterministically, when a hero first reaches
 * [HeroRenown.HERO] or better. Picked from the pool of the evidence category the hero has
 * accumulated the most in, so the nickname reflects what the officer actually became known for
 * rather than being generic flavor text.
 *
 * An id/text split, same as [HeroBackgrounds] and [HeroNaming]'s ranks: [HeroState.nicknameId]
 * stores the stable id, [displayText] resolves it for the UI, so a later localization pass swaps
 * the pool without touching a save.
 */
internal object HeroNicknames {
    private data class Nickname(
        val id: String,
        val text: String,
    )

    private val pool: Map<EvidenceCategory, List<Nickname>> =
        mapOf(
            EvidenceCategory.OFFENSIVE_OPERATIONS to
                listOf(Nickname("the_hammer", "the Hammer"), Nickname("the_spearhead", "the Spearhead")),
            EvidenceCategory.DEFENSIVE_OPERATIONS to
                listOf(Nickname("the_shield", "the Shield"), Nickname("the_anchor", "the Anchor")),
            EvidenceCategory.RIVER_OPERATIONS to listOf(Nickname("the_river_fox", "the River Fox")),
            EvidenceCategory.URBAN_COMBAT to listOf(Nickname("the_street_fighter", "the Street Fighter")),
            EvidenceCategory.FOREST_OPERATIONS to listOf(Nickname("the_woodsman", "the Woodsman")),
            EvidenceCategory.MOUNTAIN_OPERATIONS to listOf(Nickname("the_mountaineer", "the Mountaineer")),
            EvidenceCategory.ARMORED_COMBAT to listOf(Nickname("the_tank_buster", "the Tank Buster")),
        )

    private val byId: Map<String, String> = pool.values.flatten().associate { it.id to it.text }

    fun displayText(id: String): String? = byId[id]

    fun evaluate(hero: HeroState): HeroState {
        if (hero.nicknameId != null) return hero
        val eligible = hero.renown == HeroRenown.HERO || hero.renown == HeroRenown.LEGEND
        val pick = if (eligible) pickFor(hero) else null
        return if (pick != null) hero.copy(nicknameId = pick.id) else hero
    }

    private fun pickFor(hero: HeroState): Nickname? {
        val dominant =
            hero.specializationEvidence.entries
                .maxByOrNull { it.value }
                ?.key
                ?.let(EvidenceCategory::byName)
        val candidates = dominant?.let(pool::get)
        return candidates?.let { SeededRandom(SeededRandom.seedFrom(hero.heroId.value, "nickname")).pick(it) }
    }
}
