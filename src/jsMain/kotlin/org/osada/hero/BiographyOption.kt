package org.osada.hero

/**
 * One selectable biography fact, with the tags that decide whether it may be selected at all —
 * `docs/design/hero-biography-service-network-and-soviet-distinction.md` §10.
 *
 * The design's own instruction is to "prefer explicit compatibility tags over a growing chain of
 * special-case `if` statements", and that is the whole reason this type exists. A life path is
 * generated in a fixed order (§7.2) and each step narrows the next: a `tekhnikum` graduate can
 * become a mine surveyor, a village clerk cannot. Expressing that as a data constraint keeps the
 * generator a single loop rather than a per-nation ladder of conditions.
 *
 * ## How the tags compose
 *
 * Selection accumulates a **fact set** of opaque tokens. Every option chosen contributes
 * `"<field>:<id>"` (so a later option can require one exact earlier choice) plus whatever it
 * declares in [provides] (so a later option can require a CLASS of earlier choices — "any technical
 * education" — without listing every id that qualifies). [requiresFacts] must be a subset of what
 * has been accumulated; [excludesFacts] must not intersect it.
 *
 * [provides] is not in the design's illustrative `data class`, which lists only `requiresFacts`
 * and `excludesFacts`. It is required by them: a requirement needs something to match, and the
 * alternative — every technical profession naming every technical school id — is the enumeration
 * the tag system exists to avoid.
 *
 * ## The bounds
 *
 * [yearFrom]/[yearTo] bound the **campaign** year the option may appear in; an institution that
 * did not exist yet, or had been renamed away, is simply absent from that campaign's draws.
 * [minimumAge] bounds the hero's age at the campaign year, which is what makes a 24-year-old
 * unable to be a Civil War veteran. [unitClasses], when non-empty, restricts a branch-specific
 * school or prior conflict to the classes it makes sense for.
 *
 * [weight] is a relative draw weight, not a percentage: a pool's weights are summed at selection
 * time, so adding an option never requires rebalancing the others.
 */
internal data class BiographyOption(
    val id: String,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val minimumAge: Int? = null,
    val unitClasses: Set<Int> = emptySet(),
    val requiresFacts: Set<String> = emptySet(),
    val excludesFacts: Set<String> = emptySet(),
    val provides: Set<String> = emptySet(),
    /**
     * Earliest year this ROUTE INTO SERVICE could have been taken — used only by the service-entry
     * step, and distinct from [yearFrom], which bounds the campaign rather than the enlistment.
     *
     * Without it a 1919 Red commander could be recorded as entering service "on a party
     * mobilization in 1909", because the only floor on the enlistment year was the hero's own age.
     * The route existed from 1917; the officer did not.
     */
    val serviceNotBefore: Int? = null,
    val weight: Int = 1,
) {
    /**
     * Whether this option is legal for a hero of [age] in [year] commanding [unitClass], given the
     * facts already chosen.
     *
     * [age] is nullable because a campaign can be loaded without a scenario date, and the design's
     * rule is that a fact whose chronology cannot be checked is omitted rather than guessed: an
     * option carrying a [minimumAge] is therefore rejected outright when the age is unknown, while
     * one without an age requirement is unaffected.
     */
    @Suppress("ReturnCount") // one guard per bound; nesting five checks would read far worse
    fun allowedFor(
        year: Int?,
        age: Int?,
        unitClass: Int,
        facts: Set<String>,
    ): Boolean {
        if (yearFrom != null && (year == null || year < yearFrom)) return false
        if (yearTo != null && (year == null || year > yearTo)) return false
        if (minimumAge != null && (age == null || age < minimumAge)) return false
        if (unitClasses.isNotEmpty() && unitClass !in unitClasses) return false
        if (!facts.containsAll(requiresFacts)) return false
        return excludesFacts.none { it in facts }
    }
}

/**
 * The ordered pools one biography pack draws a life path from — §9's "content family".
 *
 * A pack is chosen once, from the player's country and the campaign year, and then supplies every
 * subsequent draw. That is what keeps a life path internally consistent without any cross-pack
 * compatibility matrix: a Red Army officer cannot pick up a Yugoslav partisan's route into the war
 * because that route is not in the pack that was selected.
 *
 * [militaryEducationJunior] / [militaryEducationSenior] stay split by rank rather than merged into
 * one weighted pool, preserving the behaviour the deleted `HeroBiographyPools` shipped: a lieutenant is
 * commissioned from the ranks, a colonel attended a staff college, and a single pool would have to
 * re-derive that from weights every time a pack is authored.
 *
 * An empty pool is legal and means "this pack does not carry that fact" — the ancient pack has no
 * civilian education, no party status and no service-entry year, and must not be given a
 * twentieth-century stand-in for them (§9.1).
 */
internal data class BiographyPack(
    val id: String,
    val regions: List<BiographyOption> = emptyList(),
    val socialBackgrounds: List<BiographyOption> = emptyList(),
    val civilianEducation: List<BiographyOption> = emptyList(),
    val professions: List<BiographyOption> = emptyList(),
    val militaryEducationJunior: List<BiographyOption> = emptyList(),
    val militaryEducationSenior: List<BiographyOption> = emptyList(),
    val serviceEntries: List<BiographyOption> = emptyList(),
    val warEntries: List<BiographyOption> = emptyList(),
    val politicalStatuses: List<BiographyOption> = emptyList(),
    val priorService: List<BiographyOption> = emptyList(),
    /** False for packs whose era has no dated enlistment to speak of — the ancient life path. */
    val tracksServiceStartYear: Boolean = true,
    /** How often a hero carries any party/political status at all; 0.0 disables the fact entirely. */
    val politicalChance: Double = 0.0,
    /**
     * Optional suffix selecting an era-appropriate wording of the biography's sentences.
     *
     * §9.1 forbids the ancient pack from rendering "before the war" or "entered service" — the
     * phrasing of a conscript army applied to a slave revolt. Rather than branch the narrator on a
     * pack id, a pack names the wording it wants and the narrator appends it to the template key,
     * so a future pack can differ in phrasing without touching any code.
     */
    val narrationVariant: String? = null,
    /**
     * Whether a birthplace is printed with the name the reader knows appended — SIE's
     * `Петропавловск, ныне Казахской ССР`.
     *
     * True for the Soviet pack, whose officers were born under governorates that no longer exist
     * by the campaign; false for the Civil War pack, where there is no later name to give, and for
     * every pack whose places never changed hands.
     */
    val birthplaceCarriesModernName: Boolean = false,
)
