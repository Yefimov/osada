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
 */
internal object HeroBiographyNarrator {
    fun narrate(
        facts: HeroBiographyFacts,
        rankId: String,
    ): List<String> = listOfNotNull(originSentence(facts), commissionSentence(facts, rankId))

    private fun originSentence(facts: HeroBiographyFacts): String? {
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
            selector,
            mapOf("year" to year.toString(), "place" to place, "social" to social),
        )
    }

    private fun commissionSentence(
        facts: HeroBiographyFacts,
        rankId: String,
    ): String {
        val rank = HeroDisplay.rank(rankId)
        val education = facts.militaryEducationId?.let { I18n.t("hero.bio.education.$it") }
        val service = facts.priorServiceId?.let { I18n.t("hero.bio.service.$it") }
        val selector =
            when {
                education != null && service != null -> "education_service"
                education != null -> "education_only"
                service != null -> "service_only"
                else -> "none"
            }
        return I18n.select(
            "hero.bio.commission",
            selector,
            mapOf("rank" to rank, "education" to education, "service" to service),
        )
    }
}
