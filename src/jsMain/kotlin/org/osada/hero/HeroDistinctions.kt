package org.osada.hero

import org.osada.i18n.CalendarText

/**
 * Eligibility, conferral and citation for §12's highest distinction.
 *
 * ## The dates are the feature
 *
 * §12.1 lists them and every one is enforced here rather than left to content: the title was
 * established on **16 April 1934**, so it cannot be conferred in a Civil War scenario at all; the
 * Gold Star medal arrived in **1939**, so an award dated before it must not claim one. A game that
 * shipped a Gold Star to a 1936 Spanish adviser would be wrong in a way no player could unsee, and
 * the check that prevents it is a date comparison, not a content review.
 *
 * ## An exceptional deed, not a threshold
 *
 * §12.3 is explicit: "at least one exceptional recorded deed, not only an accumulated XP
 * threshold". [EXCEPTIONAL_DEEDS] is therefore a set of achievement ids the engine can prove
 * happened, and a conferral records WHICH ones it was granted for. That record is also what makes a
 * repeat conferral safe: §12.3's "a later, independent exceptional deed" is enforced by refusing
 * any deed already cited by an earlier award, so replaying a battle or reloading a save cannot
 * duplicate the title.
 */
internal object HeroDistinctions {
    const val HERO_OF_THE_SOVIET_UNION = "hero_of_the_soviet_union"

    /** 16 April 1934 — the title did not exist before it (§12.1). */
    const val ESTABLISHED_YEAR = 1934
    private const val ESTABLISHED_MONTH = 4
    private const val ESTABLISHED_DAY = 16

    /** The Gold Star medal and the repeat-award procedure both date from 1939 (§12.1). */
    const val GOLD_STAR_YEAR = 1939

    /**
     * Player sides the title can be conferred on.
     *
     * The Soviet country ids only. Country 103 (Red Russia) is deliberately ABSENT: §12.1's last
     * bullet says the title "is impossible in Russian Civil War scenarios because it did not yet
     * exist", and the date check would refuse those campaigns anyway — this is the second lock on
     * the same door, so a hypothetical 1930s scenario authored with country 103 still cannot
     * receive it.
     */
    private val ELIGIBLE_COUNTRIES = setOf(19, 61, 89)

    /**
     * The deeds §12.3 allows, restricted to facts the engine already records.
     *
     * Deliberately narrow. "A decisive action while encircled" and "repeated exceptional victories
     * within one operation" are in the design's list but not here: the combat payload cannot prove
     * encirclement today (`CombatAchievementEvent`'s own KDoc says so), and inventing either would
     * be the fabricated citation §12.5 forbids.
     */
    private val EXCEPTIONAL_DEEDS =
        setOf(
            AchievementType.DESTROYED_STRONGER_ENEMY,
            AchievementType.SURVIVED_CRITICAL_DAMAGE,
        )

    /** Everything a conferral decision needs, so the rule stays a pure function (§17.5). */
    data class Context(
        val hero: HeroState,
        val country: Int?,
        val serviceYear: Int?,
        /** ISO date of the scenario turn, when known — the day-precision half of the 1934 gate. */
        val date: String?,
        val scenarioId: String,
        val turn: Int,
        val location: String? = null,
        val achievements: List<AchievementType> = emptyList(),
        val posthumous: Boolean = false,
    )

    /**
     * The conferral earned by this moment, or null — the single decision point.
     *
     * Returns a fresh [HeroDistinction] rather than mutating: the caller decides whether to apply
     * it, which keeps this testable without a roster and makes double-application impossible to do
     * by accident.
     */
    @Suppress("ReturnCount") // side, date and deed are three separate gates, refused independently
    fun evaluate(context: Context): HeroDistinction? {
        if (!isEligibleSide(context.country)) return null
        if (!isOnOrAfterEstablishment(context.serviceYear, context.date)) return null
        val deeds = qualifyingDeeds(context)
        if (deeds.isEmpty()) return null
        return HeroDistinction(
            distinctionId = HERO_OF_THE_SOVIET_UNION,
            sequence = context.hero.distinctions.size + 1,
            scenarioId = context.scenarioId,
            turn = context.turn,
            date = context.date,
            location = context.location,
            deedEventIds = deeds,
            posthumous = context.posthumous,
        )
    }

    /**
     * Deeds that can justify THIS conferral: exceptional, and not already cited by an earlier one.
     *
     * The second half is §12.3's repeat rule and the duplicate guard at once. Reprocessing the same
     * combat, or reloading and replaying it, offers the same deed ids — and they are refused.
     */
    private fun qualifyingDeeds(context: Context): List<String> {
        val alreadyCited =
            context.hero.distinctions
                .flatMap { it.deedEventIds }
                .toSet()
        return context.achievements
            .filter { it in EXCEPTIONAL_DEEDS }
            .map { deedId(it, context) }
            .distinct()
            .filterNot { it in alreadyCited }
    }

    /**
     * A deed's citation id: the achievement, the scenario and the turn it happened in.
     *
     * Keyed on all three so the SAME kind of deed in a later battle is a genuinely independent one
     * (§12.3's "a later, independent exceptional deed"), while the same deed in the same turn —
     * which is what a reload replays — is recognised as the one already cited.
     */
    private fun deedId(
        achievement: AchievementType,
        context: Context,
    ): String = "${achievement.name.lowercase()}@${context.scenarioId}#${context.turn}"

    private fun isEligibleSide(country: Int?): Boolean = country != null && country in ELIGIBLE_COUNTRIES

    /**
     * Whether the award date is on or after 16 April 1934.
     *
     * Falls back to the year alone when no ISO date is available, and in that case requires the year
     * to be strictly AFTER 1934 — an undated scenario somewhere in 1934 might be March, and §12.5's
     * discipline of never asserting more than the record supports applies to the gate as much as to
     * the citation.
     */
    @Suppress("ReturnCount") // the undated fallback is its own answer, not a nested branch
    private fun isOnOrAfterEstablishment(
        serviceYear: Int?,
        date: String?,
    ): Boolean {
        val parsed = parseIsoDate(date)
        if (parsed == null) {
            val year = serviceYear ?: return false
            return year > ESTABLISHED_YEAR
        }
        val (year, month, day) = parsed
        return when {
            year != ESTABLISHED_YEAR -> year > ESTABLISHED_YEAR
            month != ESTABLISHED_MONTH -> month > ESTABLISHED_MONTH
            else -> day >= ESTABLISHED_DAY
        }
    }

    /** True when [distinction] is late enough to include the Gold Star medal (§12.4). */
    fun includesGoldStar(distinction: HeroDistinction): Boolean {
        val year = parseIsoDate(distinction.date)?.first ?: return false
        return year >= GOLD_STAR_YEAR
    }

    /**
     * Shared with every other reader of a stored date ([CalendarText]), which matters here more
     * than anywhere: this file's own parser split on `-`, so a BC date parsed as four parts and
     * returned null — and a null date makes [isOnOrAfterEstablishment] fall back to the year alone.
     * The gate still refused, but for the wrong reason.
     */
    private fun parseIsoDate(date: String?): Triple<Int, Int, Int>? = CalendarText.parseIso(date)
}
