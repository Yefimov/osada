package org.osada.hero

import org.osada.i18n.CalendarText
import org.osada.i18n.I18n

/**
 * The pieces both narration paths share: the origin sentence, the year format, and the two
 * gender-agreement helpers.
 *
 * Split out of [HeroBiographyNarrator] purely for the project's functions-per-object budget, which
 * the life-path sentences pushed that object past. The split is along a real seam even so —
 * everything here is wording MECHANICS that any future sentence needs, while the narrator holds
 * the sentences themselves.
 */
internal object HeroBiographySentences {
    /**
     * `Born 1908 in a railway settlement, to a worker's family.` — shared by the legacy and
     * life-path records, because a hero's origin is the one fact both models always had.
     *
     * Null when the birth year is unknown: §13.1's rule is that an absent fact is an omitted
     * clause, and a sentence whose only anchor is missing is omitted whole rather than started.
     */
    fun origin(
        facts: HeroBiographyFacts,
        female: Boolean,
    ): String? {
        val year = facts.birthYear ?: return null
        val place = facts.birthplaceId?.let { birthplace(it, facts.biographyPackId) }
        val social = facts.socialBackgroundId?.let { I18n.t("hero.bio.social.$it") }
        val selector =
            when {
                place != null && social != null -> "year_place_social"
                place != null -> "year_place"
                social != null -> "year_social"
                else -> "year_only"
            }
        return I18n.select(
            "hero.bio.origin",
            genderedSelector(selector, female),
            mapOf("year" to formatYear(year), "place" to place, "social" to social),
        )
    }

    /**
     * A birthplace, with the name the reader knows appended where the pack asks for it.
     *
     * `Петропавловск, ныне Казахской ССР` is BSE's own device, and it is what makes an entry for a
     * man born in 1906 honest: the Uzbek SSR did not exist until 1924, but Turkestan did. The
     * Civil War pack sets the flag false — in 1919 there is no later name to give.
     *
     * A pack that asks for the second half and has no `.now` key for a place falls back to the
     * place alone rather than printing an empty bracket.
     */
    @Suppress("ReturnCount") // no pack, no flag, no "now" key -- three ways to end up with the place alone
    private fun birthplace(
        id: String,
        packId: String?,
    ): String {
        val place = I18n.t("hero.bio.birthplace.$id")
        val pack = packId?.let(BiographyPacks::byId) ?: return place
        if (!pack.birthplaceCarriesModernName) return place
        val now = I18n.tOrNull("hero.bio.birthplace.$id.now") ?: return place
        return I18n.t("hero.bio.birthplace.with_now", mapOf("place" to place, "now" to now))
    }

    /**
     * The template key for [base] in this pack's wording. §9.1's rule expressed as a lookup: the
     * ancient pack cannot say "before the war" or "entered service", so it names its own variant
     * and gets `hero.bio.schooling_ancient` instead of `hero.bio.schooling`.
     */
    fun variantKey(
        base: String,
        variant: String?,
    ): String = if (variant == null) base else "${base}_$variant"

    /**
     * A calendar year as the dossier should print it, shared with the HUD and the service record
     * through [CalendarText] — a hero born in 99 BC and the scenario he fights in must not disagree
     * about how a year before 1 is written.
     */
    fun formatYear(year: Int): String = CalendarText.year(year)

    /** A `_f` branch is required for every selector the narrator uses — see its class doc. */
    fun genderedSelector(
        selector: String,
        female: Boolean,
    ): String = if (female) "${selector}_f" else selector

    /** A `_f` key is optional here: languages where the noun doesn't inflect just reuse the base. */
    fun genderedFact(
        baseKey: String,
        female: Boolean,
    ): String = (if (female) I18n.tOrNull("${baseKey}_f") else null) ?: I18n.t(baseKey)
}
