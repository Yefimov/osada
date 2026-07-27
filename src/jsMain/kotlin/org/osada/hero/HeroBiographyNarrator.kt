package org.osada.hero

import org.osada.i18n.I18n

/**
 * Composes [HeroBiographyFacts] into two prose sentences (`docs/design/hero-presentation.md` §2.3b),
 * replacing the old renderer's `"Born $year."` / `"Commissioned as $rank."` fragments — the
 * structured facts model's whole point (§16) was to stay localizable and re-renderable, and a
 * two-line fragment dump did neither justice.
 *
 * **Deterministic**: a pure function of [HeroBiographyFacts] and a rank id, no randomness at
 * display time (§29.17) — all the randomness already happened once, at generation
 * ([HeroBiographyPools]), and is baked into the stored facts.
 *
 * **Composable in both locales, not fragment-concatenated.** Each `hero.bio.origin`/
 * `hero.bio.commission` [I18n.select] branch is a WHOLE sentence with named slots, chosen by which
 * facts are present -- never built by splicing separately-translated clauses together in English
 * word order, which is how translated text becomes ungrammatical.
 *
 * **A null field renders as an omitted clause, not as "unknown"** (the model's own contract) — a
 * migrated hero with only `emergenceEventId` set still gets a dignified, if short, commission
 * sentence; a hero missing `birthYear` entirely (no campaign year known at emergence) gets no
 * origin sentence at all rather than a broken one.
 *
 * **Gendered (§4.11).** [seed] is the hero's portrait seed — the same one
 * [PortraitComposerV2.genderFor] rolls a gender from — so the biography's pronouns/inflections
 * always agree with the face the player sees. Every `hero.bio.origin`/`hero.bio.commission`
 * selector has a `_f` sibling branch (identical text where the language has no gendered wording,
 * as in English origin sentences; a rewritten sentence where it does, as in Russian). Fact clauses
 * looked up directly (`hero.bio.education.*`) fall back from a `_f` key to the base key via
 * [I18n.tOrNull] rather than requiring every education/service string to carry a redundant
 * feminine copy in languages where the noun doesn't inflect.
 */
internal object HeroBiographyNarrator {
    fun narrate(
        facts: HeroBiographyFacts,
        rankId: String,
        seed: Int,
    ): List<String> {
        val female = PortraitComposerV2.genderFor(seed) == "female"
        return listOfNotNull(originSentence(facts, female), commissionSentence(facts, rankId, female))
    }

    private fun originSentence(
        facts: HeroBiographyFacts,
        female: Boolean,
    ): String? {
        val year = facts.birthYear ?: return null
        val place = facts.birthplaceId?.let { I18n.t("hero.bio.birthplace.$it") }
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
            mapOf("year" to year.toString(), "place" to place, "social" to social),
        )
    }

    private fun commissionSentence(
        facts: HeroBiographyFacts,
        rankId: String,
        female: Boolean,
    ): String {
        val rank = HeroDisplay.rank(rankId)
        val education = facts.militaryEducationId?.let { genderedFact("hero.bio.education.$it", female) }
        val service = facts.priorServiceId?.let { genderedFact("hero.bio.service.$it", female) }
        val selector =
            when {
                education != null && service != null -> "education_service"
                education != null -> "education_only"
                service != null -> "service_only"
                else -> "none"
            }
        return I18n.select(
            "hero.bio.commission",
            genderedSelector(selector, female),
            mapOf("rank" to rank, "education" to education, "service" to service),
        )
    }

    /** A `_f` branch is required for every selector this file uses — see the class doc. */
    private fun genderedSelector(
        selector: String,
        female: Boolean,
    ): String = if (female) "${selector}_f" else selector

    /** A `_f` key is optional here: languages where the noun doesn't inflect just reuse the base. */
    private fun genderedFact(
        baseKey: String,
        female: Boolean,
    ): String = (if (female) I18n.tOrNull("${baseKey}_f") else null) ?: I18n.t(baseKey)
}
