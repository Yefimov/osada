package org.osada.hero

import org.osada.model.countryNames

/**
 * Nation-aware officer names — design brief §16.
 *
 * §16 requires names to be nation-aware, date-aware and moddable; §30 makes a *full* historical
 * database for every nation an explicit non-goal, and §23 accepts fallback procedural content. So
 * this covers the major belligerents the shipped campaigns actually field and routes every other
 * country to a [GENERIC] pool, rather than pretending to know names for all sixty-odd
 * [countryNames] entries.
 *
 * The mapping is by country **id** (an index into [countryNames]), read once at generation time —
 * the point in the lifecycle where nation is finally known, which is exactly why §16's naming work
 * was deferred out of the Phase 1 [HeroNaming] seam to here.
 *
 * Moddability is structural: pools are plain lists keyed by country id, the shape a loaded data
 * file would deserialise into. Nothing downstream depends on the names being hard-coded.
 *
 * Determinism is the contract that must not break (§7.4, §29.17): [nameFor] is a pure function of
 * (seed, country), so the same emergence always yields the same officer across save and reload.
 */
internal object HeroNamePools {
    /**
     * [femininizeSurname] handles the Slavic pools, where a masculine surname is a *different word*
     * from its feminine form (Ivanov / Ivanova), not a separate name to author — a suffix swap on
     * the male-pool entry. Cultures where officer surnames don't inflect by gender (German, English,
     * French, Italian, Hungarian, Romanian, Finnish, the generic pool) leave it `null` and the male
     * surname list is used unchanged for both genders, which is correct for those cultures rather
     * than an oversight.
     */
    data class NamePool(
        val givenNames: List<String>,
        val givenNamesFemale: List<String>,
        val surnames: List<String>,
        val femininizeSurname: ((String) -> String)? = null,
    )

    /** Builds a pool from space-delimited strings, so each list stays on one readable line. */
    private fun pool(
        given: String,
        givenFemale: String,
        surname: String,
        femininizeSurname: ((String) -> String)? = null,
    ) = NamePool(given.split(" "), givenFemale.split(" "), surname.split(" "), femininizeSurname)

    /** Russian/Ukrainian-style masculine surname suffixes, each with its feminine counterpart. */
    private fun slavicOvFeminine(surname: String): String =
        when {
            surname.endsWith("ov") || surname.endsWith("ev") || surname.endsWith("in") -> surname + "a"
            surname.endsWith("sky") -> surname.removeSuffix("sky") + "skaya"
            else -> surname
        }

    private fun polishSkiFeminine(surname: String): String =
        if (surname.endsWith("ski")) surname.removeSuffix("ski") + "ska" else surname

    private val GERMAN =
        pool(
            "Heinz Kurt Erwin Wilhelm Otto Hans Georg Friedrich Karl Ernst",
            "Ingrid Ursula Erika Gisela Hannelore Renate Traudl Waltraud Marlene Elfriede",
            "Brandt Keller Vogel Hartmann Richter Neumann Bauer Wolff Krause Sauer",
        )

    private val SOVIET =
        pool(
            "Sergei Ivan Dmitri Nikolai Pavel Vasily Mikhail Andrei Boris Grigori",
            "Yelena Nadezhda Zinaida Galina Valentina Klavdia Antonina Lyudmila Raisa Maria",
            "Vorontsov Ivanov Popov Sokolov Morozov Volkov Zaitsev Orlov Lebedev",
            femininizeSurname = ::slavicOvFeminine,
        )

    private val HUNGARIAN =
        pool(
            "Andras Bela Ferenc Gabor Istvan Janos Laszlo Miklos Sandor Zoltan",
            "Agnes Erzsebet Ilona Judit Katalin Maria Piroska Rozsa Terez Zsofia",
            "Kovacs Szabo Nagy Toth Varga Farkas Balogh Horvath Molnar Almasy",
        )

    private val AMERICAN =
        pool(
            "James Robert John William George Charles Frank Harold Walter Raymond",
            "Mary Dorothy Helen Ruth Margaret Betty Barbara Virginia Frances Elizabeth",
            "Miller Anderson Harris Bradley Patton Collins Reed Bennett Foster Hayes",
        )

    private val BRITISH =
        pool(
            "Arthur Bernard Edward Henry Charles Reginald Cecil Alfred Percy Leonard",
            "Margaret Joan Dorothy Eileen Vera Winifred Constance Muriel Phyllis Sylvia",
            "Carter Thompson Whitfield Alexander Montgomery Harding Pearce Wingate Blythe",
        )

    private val ITALIAN =
        pool(
            "Giovanni Marco Luigi Paolo Enzo Carlo Aldo Vittorio Cesare Renato",
            "Maria Anna Giulia Franca Lucia Rosa Elena Carla Silvana Adriana",
            "Rossi Bianchi Conti Greco Bruno Gallo Ferrari Marino Rizzo Colombo",
        )

    private val FRENCH =
        pool(
            "Jean Pierre Henri Louis Marcel Andre Robert Georges Paul Charles",
            "Marie Jeanne Suzanne Yvonne Simone Denise Renee Madeleine Odette Genevieve",
            "Moreau Girard Lefevre Laurent Petit Roux Fournier Mercier Bernard Dupont",
        )

    private val POLISH =
        pool(
            "Jan Stefan Kazimierz Tadeusz Wladyslaw Zygmunt Henryk Marian Jerzy",
            "Anna Maria Zofia Halina Krystyna Janina Irena Danuta Wanda Barbara",
            "Kowalski Wojcik Kaminski Zielinski Szymanski Wozniak Nowak Mazur Krol",
            femininizeSurname = ::polishSkiFeminine,
        )

    private val ROMANIAN =
        pool(
            "Ion Gheorghe Nicolae Constantin Mihai Vasile Petre Dumitru Radu Stefan",
            "Maria Elena Ana Ioana Elisabeta Cornelia Victoria Constanta Aurelia Florica",
            "Popescu Ionescu Dumitrescu Stanescu Munteanu Georgescu Marin Barbu Stoica",
        )

    private val FINNISH =
        pool(
            "Aarne Eero Kalle Lauri Matti Onni Toivo Urho Veikko Yrjo",
            "Aino Helmi Hilja Impi Lyyli Saimi Sanni Tyyne Vieno Aune",
            "Virtanen Makinen Nieminen Laine Heikkinen Koskinen Jarvinen Salo Aalto",
        )

    /** Deliberately culture-neutral, so an unmapped nation reads as procedural rather than as wrong history. */
    private val GENERIC =
        pool(
            "Alex Daniel Erik Felix Leon Martin Nikolas Stefan Victor Adrian",
            "Alexa Danielle Erika Felicia Leona Martina Nikola Stefanie Victoria Adriana",
            "Adler Berg Falk Holt Kern Lang Marek Roth Stein Weiss",
        )

    /**
     * Country id → pool. Ids index [countryNames]; only the belligerents the campaigns field are
     * listed and everything else takes [GENERIC]. Kept as id literals with the name in a comment so
     * a shift in [countryNames] is caught by [org.osada.ConstantsConsistencyTest]-style checks.
     */
    private val byCountry: Map<Int, NamePool> =
        mapOf(
            4 to HUNGARIAN, // Hungary
            6 to FRENCH, // France
            7 to GERMAN, // Germany
            9 to AMERICAN, // USA
            12 to ITALIAN, // Italy
            13 to ROMANIAN, // Romania
            19 to SOVIET, // Soviet Union
            20 to POLISH, // Poland
            22 to BRITISH, // United Kingdom
            38 to FINNISH, // Finland
        )

    fun poolFor(country: Int): NamePool = byCountry[country] ?: GENERIC

    /**
     * A stable full `"Given Surname"` designation for (seed, country, gender). [female] must agree
     * with [org.osada.hero.PortraitComposerV2.genderFor] of the same seed (§4.11) — callers derive
     * it from there, not roll it independently, or the officer's name and portrait would disagree.
     */
    fun nameFor(
        seed: Int,
        country: Int,
        female: Boolean,
    ): String {
        val pool = poolFor(country)
        val rng = SeededRandom(seed)
        val givenList = if (female) pool.givenNamesFemale else pool.givenNames
        val given = rng.pick(givenList) ?: givenList.first()
        val surname = rng.pick(pool.surnames) ?: pool.surnames.first()
        val finalSurname = if (female) pool.femininizeSurname?.invoke(surname) ?: surname else surname
        return "$given $finalSurname"
    }
}
