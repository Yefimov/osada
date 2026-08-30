package org.osada.ui

import org.osada.i18n.I18n
import org.osada.model.Equipment
import org.osada.model.getCountryName

/**
 * How a country id from `Equipment.countryNames` is NAMED and GROUPED on the campaign/scenario
 * registers. Split out of [StartMenuListToolbar] purely to keep that object inside the project's
 * class-size budget -- it is the register's naming table and nothing else.
 *
 * Three questions, deliberately answered separately (2026-08-16 user request):
 *
 *  - [displayLabel] -- what this faction is CALLED (side cards, flag tooltips, "Start as ..."):
 *    the specific force, e.g. "Milicias Comunistas". Renamed only where the raw `countryNames`
 *    entry reads unlike the rest of the list ("Communist Yugoslavia" -> "Yugoslavia — Communists",
 *    "Russian Empire" -> "Russia — Empire") or is plainly a duplicate of another id.
 *  - [groupLabel] -- which BUCKET it filters under in the register's country dropdown. Several
 *    ids share one bucket so the dropdown reads as a list of nations rather than of every militia
 *    that ever took the field: all six Republican Spanish forces answer to "Spain — Republicans".
 *    Defaults to [displayLabel] when a faction is its own bucket.
 *  - [shortLabel] -- the nation half alone, for the fixed-width "Start as ..." plate, which cannot
 *    fit "Start as Yugoslavia — Communists" on one line.
 *
 * The dominant/"default" id for a nation (plain Germany id 7, plain USA id 9, ...) is deliberately
 * left out of the table: only the rarer, colliding or oddly-named ids need an entry, and anything
 * absent falls back to `Equipment.getCountryName` so no country can silently vanish.
 */
internal object StartMenuCountryLabels {
    /** [display] is the faction's own name; [group] is the country-filter bucket it answers to. */
    private data class Label(
        val display: String,
        val group: String = display,
    )

    private const val FACTION_SEPARATOR = " — "

    /**
     * Country ids that name no nation at all and must not reach the register: OG efile slots that
     * carry equipment but no flag. Id 361 ("Unassigned Flag (lxf)") appears in seven scenarios --
     * always as a `support` entry, never as anyone's own country -- and there is no
     * `equipment-country-362.json` behind it, so it contributes nothing but a nonsense dropdown row.
     */
    private val suppressedCountries = setOf(361)

    @Suppress("MaxLineLength")
    private val labels: Map<Int, Label> =
        mapOf(
            // ---- Germany -------------------------------------------------------------------
            // Id 7 stays plain "Germany" (146 scenarios, every era); id 86 is the same regime
            // under a different eqp-lxf code (RD Road To/Siege Of Berlin, 1945) -- merge it.
            86 to Label("Germany"),
            117 to Label("Germany${FACTION_SEPARATOR}Empire"), // German Empire (Kaiserreich)
            196 to Label("Germany${FACTION_SEPARATOR}Revolutionaries"), // 1918-19 Räterepublik
            188 to Label("Germany${FACTION_SEPARATOR}Communists"), // Red Germany
            303 to Label("Germany${FACTION_SEPARATOR}Waffen SS"),
            // "German Freikorps": the volunteer corps the SPD government raised against the
            // councils in 1919 -- named for the politics that fielded it, not the units.
            161 to Label("Germany${FACTION_SEPARATOR}Social-Democrats"),
            // ---- Russia / USSR -------------------------------------------------------------
            // 19/61/89 are three efiles' spelling of the same Soviet Union and stay merged; the
            // other Russia-named factions are civil-war-era and distinct both from each other and
            // from the USSR, but read better clustered under "Russia — X".
            19 to Label("Soviet Union"),
            61 to Label("Soviet Union"),
            89 to Label("Soviet Union"),
            103 to Label("Russia${FACTION_SEPARATOR}Communists"), // Red Russia
            100 to Label("Russia${FACTION_SEPARATOR}Whites"), // White Russia
            189 to Label("Russia${FACTION_SEPARATOR}Greens"), // Russian Green Armies
            191 to Label("Russia${FACTION_SEPARATOR}Cossacks"), // Cossack Hosts
            153 to Label("Russia${FACTION_SEPARATOR}Empire"), // Russian Empire
            // "Rebels/Revolutionaries" is a generic OG slot used in exactly one scenario -- Omsk
            // (Czech Legion), where it is the Bolshevik December rising. Keep the raw name on the
            // side card (it is what the scenario itself calls them) but file it with the Reds.
            148 to Label("Rebels/Revolutionaries", "Russia${FACTION_SEPARATOR}Communists"),
            // ---- Ukraine -------------------------------------------------------------------
            // "Black Army" and "Anarchist Ukraine" are two ids for one force: Makhno's
            // Revolutionary Insurgent Army. Same name, same bucket.
            136 to Label("Black Army", "Ukraine${FACTION_SEPARATOR}Anarchists"),
            171 to Label("Black Army", "Ukraine${FACTION_SEPARATOR}Anarchists"),
            // ---- Spain ---------------------------------------------------------------------
            // bn9s00 "Battle of Sesena" (eqp-lxf, id 28) is a 1936 Nationalist offensive on
            // Madrid, so id 28 is the Nationalist side, same as id 225.
            28 to Label("Spain${FACTION_SEPARATOR}Nationalists"),
            225 to Label("Spain${FACTION_SEPARATOR}Nationalists"), // Ejército Nacional
            234 to Label("Spain${FACTION_SEPARATOR}Nationalists"), // National Spain
            207 to Label("Tercios Requetés", "Spain${FACTION_SEPARATOR}Nationalists"),
            208 to Label("Milicias Falange", "Spain${FACTION_SEPARATOR}Nationalists"),
            26 to Label("Spanish Republic", "Spain${FACTION_SEPARATOR}Republicans"),
            91 to Label("Spain${FACTION_SEPARATOR}Republicans"), // Republican Spain
            226 to Label("Ejército Popular", "Spain${FACTION_SEPARATOR}Republicans"),
            203 to Label("CNT-FAI", "Spain${FACTION_SEPARATOR}Republicans"),
            205 to Label("Milicias Socialistas", "Spain${FACTION_SEPARATOR}Republicans"),
            206 to Label("Brigadas Internacionales", "Spain${FACTION_SEPARATOR}Republicans"),
            210 to Label("Milicias Comunistas", "Spain${FACTION_SEPARATOR}Republicans"),
            // ---- Cuba ----------------------------------------------------------------------
            // Id 83 "Cuba" appears only in Sierra Maestra 1958, where it IS the Batista regime;
            // id 245 "Socialist Cuba" is the post-revolution state and takes the plain name.
            83 to Label("Cuba${FACTION_SEPARATOR}Capitalists"),
            245 to Label("Cuba"),
            107 to Label("26th of July Movement", "Cuba"),
            // ---- France / Italy / Serbia ---------------------------------------------------
            33 to Label("Free French Forces", "France"),
            34 to Label("Italian Social Republic", "Italy"), // Mussolini's 1943-45 rump state
            67 to Label("Serbia"), // "Serbian State" (Nedić's occupied Serbia)
            // ---- Yugoslavia / China / Greece -----------------------------------------------
            239 to Label("Yugoslavia${FACTION_SEPARATOR}Communists"),
            15 to Label("China${FACTION_SEPARATOR}Nationalists"), // Nationalist China
            21 to Label("China${FACTION_SEPARATOR}Communists"), // Communist China
            233 to Label("China${FACTION_SEPARATOR}Communists"), // P.R. of China
            82 to Label("Greece${FACTION_SEPARATOR}Communists"), // Communist Greece
            // ---- Vietnam / Japan / USA / Lithuania -----------------------------------------
            77 to Label("Vietnam"), // Viet Minh
            246 to Label("Vietnam"), // D.R. of Vietnam
            152 to Label("Japan"), // Japanese Empire
            145 to Label("USA"), // "United States" (Wladiwostok - The End)
            193 to Label("Lithuania"), // Central Lithuania
            // ---- Hungary / USA civil war ---------------------------------------------------
            187 to Label("Hungary${FACTION_SEPARATOR}Communists"), // Red Hungary (1919)
            150 to Label("USA${FACTION_SEPARATOR}Confederacy"), // Confederate States
            162 to Label("USA${FACTION_SEPARATOR}Union"), // Union States
        )

    /** The faction's own name, or null for an invalid/blank/suppressed code. */
    fun displayLabel(id: Int): String? = resolve(id, "display") { it.display }

    /** The country-filter bucket this faction answers to (never matches a filter when null). */
    fun groupLabel(id: Int): String? = resolve(id, "group") { it.group }

    /** Curated entry via [pick], else the raw `countryNames` name, else null. */
    private fun resolve(
        id: Int,
        variant: String,
        pick: (Label) -> String,
    ): String? =
        when {
            id in suppressedCountries -> null
            labels[id] != null ->
                labels[id]?.let { label ->
                    I18n.tOrNull("game.country.curated.$id.$variant")
                        ?: if (variant == "group" && label.group == label.display) {
                            I18n.tOrNull("game.country.curated.$id.display") ?: pick(label)
                        } else {
                            pick(label)
                        }
                }
            else -> Equipment.getCountryName(id).takeIf { it.isNotBlank() && it != "Unknown" }
        }

    /**
     * The nation half alone -- "Yugoslavia" for "Yugoslavia — Communists". Used by the fixed-width
     * "Start as ..." plate on the scenario screen, which has room for one short line: the full
     * label wrapped onto a second row and spilled out of the button (2026-08-16 user report).
     * A name with no faction suffix is already as short as it gets and is returned unchanged.
     */
    fun shortLabel(id: Int): String? = displayLabel(id)?.substringBefore(FACTION_SEPARATOR)?.trim()
}
