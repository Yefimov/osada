package org.osada.hero

import org.osada.hero.HeroPortraitArt.ALL
import org.osada.hero.HeroPortraitArt.pathFor

/**
 * The authored (painted) hero portrait catalogue — the second of the two portrait paths.
 *
 * The first path is [PortraitComposerV2]: a seeded stack of SVG layers that can draw an unlimited
 * number of *procedural* officers, at the cost of drawing them all from one Soviet-1942 layer set.
 * This object is the other half: a fixed set of painted faces for the **authored** heroes in
 * [LegendaryHeroPool], where the officer is a specific person rather than a roll.
 *
 * **Why an id and not a path.** [PortraitComposition.artId] is written into saves. Storing the id
 * keeps the directory layout a rendering detail — assets can move without a migration — and lets an
 * unknown id fail *soft*: [pathFor] returns null, the renderer falls through to the layer stack the
 * composer stored alongside it, and the hero keeps a face. That is the §3.4 compatibility rule of
 * `docs/design/hero-presentation.md` applied to painted art: never a half-rendered portrait.
 *
 * **Era, not nation, is what the art actually encodes.** A budenovka and a shoulder-boardless
 * gymnastyorka read as 1919 whatever the campaign calls its side; pogony and a peaked cap read as
 * 1943. [ALL] is grouped accordingly, and each entry records the era it can honestly appear in so a
 * civil-war face can never be reserved for a 1942 campaign.
 */
internal object HeroPortraitArt {
    private const val DIR = "resources/heroes/"

    /** The historical window a painted face is honest in — uniforms date a portrait, nations do not. */
    enum class Era(
        val years: IntRange,
    ) {
        /** Third Servile War: rough civilian and gladiatorial clothing, no fantasy armour. */
        ANTIQUITY(-73..-71),

        /** German Forty-Eighters and their later service in the American Civil War. */
        FORTY_EIGHTER(1848..1865),

        /** Russian Civil War: budenovka, papakha, no shoulder boards. */
        RUSSIAN_CIVIL_WAR(1917..1922),

        /** Czechoslovak Legion along the Trans-Siberian Railway. */
        CZECHOSLOVAK_LEGION(1917..1920),

        /** Central European revolutions: inherited 1918 field dress and council armbands. */
        REVOLUTIONARY_EUROPE(1918..1919),

        /** Chinese Communist campaigns from the rural bases through the civil war. */
        CHINESE_REVOLUTION(1927..1949),

        /** Spanish Civil War: Popular Army field dress and militia remnants. */
        SPANISH_CIVIL_WAR(1936..1939),

        /** Soviet internationalists before the wartime return of shoulder boards. */
        SOVIET_INTERWAR(1936..1940),

        /** Great Patriotic War: pilotka/ushanka/peaked cap, 1943-pattern pogony on the later ranks. */
        SOVIET_WW2(1936..1955),

        /** Greek campaigns from the Pindus front through the civil war. */
        GREEK_WARTIME(1940..1949),

        /** Yugoslav Partisans: improvised field dress and mountain detachments. */
        YUGOSLAV_PARTISANS(1941..1945),

        /** Korean War field dress. */
        KOREAN_WAR(1950..1953),

        /** Vietnam War guerrilla field dress. */
        VIETNAM_WAR(1964..1975),
    }

    data class Art(
        val id: String,
        val era: Era,
        val female: Boolean,
    )

    val ALL: List<Art> =
        listOf(
            // --- Third Servile War ------------------------------------------------------------
            Art("ancient_castus", Era.ANTIQUITY, female = false),
            // --- Forty-Eighters ---------------------------------------------------------------
            Art("fortyeighter_friedrich_adler", Era.FORTY_EIGHTER, female = false),
            // --- Russian Civil War -------------------------------------------------------------
            Art("rcw_papakha_cavalry", Era.RUSSIAN_CIVIL_WAR, female = false),
            Art("rcw_sailor", Era.RUSSIAN_CIVIL_WAR, female = false),
            Art("rcw_budenovka_officer", Era.RUSSIAN_CIVIL_WAR, female = false),
            Art("rcw_partisan_bearded", Era.RUSSIAN_CIVIL_WAR, female = false),
            Art("rcw_staff_spectacles", Era.RUSSIAN_CIVIL_WAR, female = false),
            Art("rcw_pool_leather_m", Era.RUSSIAN_CIVIL_WAR, female = false),
            Art("rcw_pool_shaven_m", Era.RUSSIAN_CIVIL_WAR, female = false),
            Art("rcw_medic_f", Era.RUSSIAN_CIVIL_WAR, female = true),
            Art("rcw_pool_budenovka_f", Era.RUSSIAN_CIVIL_WAR, female = true),
            Art("rcw_pool_pilotka_f", Era.RUSSIAN_CIVIL_WAR, female = true),
            Art("white_russia_nikolai_orlov", Era.RUSSIAN_CIVIL_WAR, female = false),
            // --- Czechoslovak Legion ----------------------------------------------------------
            Art("czechoslovak_1917_jan_novak", Era.CZECHOSLOVAK_LEGION, female = false),
            // --- Central European revolutions ------------------------------------------------
            Art("revolution_1918_otto_reimers", Era.REVOLUTIONARY_EUROPE, female = false),
            Art("revolution_1919_laszlo_farkas", Era.REVOLUTIONARY_EUROPE, female = false),
            // --- Chinese Revolution -----------------------------------------------------------
            Art("china_wang_ming", Era.CHINESE_REVOLUTION, female = false),
            // --- Spanish Civil War ------------------------------------------------------------
            Art("spain_1936_isabel_navarro", Era.SPANISH_CIVIL_WAR, female = true),
            // --- Soviet internationalists ----------------------------------------------------
            Art("interwar_1936_alexei_serebryakov", Era.SOVIET_INTERWAR, female = false),
            // --- Great Patriotic War -----------------------------------------------------------
            Art("ussr_ww2_voroshin", Era.SOVIET_WW2, female = false),
            Art("ussr_ww2_belov", Era.SOVIET_WW2, female = false),
            Art("ussr_ww2_sokolova", Era.SOVIET_WW2, female = true),
            Art("ussr_ww2_tanker_star", Era.SOVIET_WW2, female = false),
            Art("ussr_ww2_flyer_veteran", Era.SOVIET_WW2, female = false),
            Art("ussr_ww2_tanker_winter", Era.SOVIET_WW2, female = false),
            Art("ussr_ww2_pool_young_m", Era.SOVIET_WW2, female = false),
            Art("ussr_ww2_ushanka_woman", Era.SOVIET_WW2, female = true),
            Art("ussr_ww2_pool_greatcoat_f", Era.SOVIET_WW2, female = true),
            Art("ussr_ww2_pool_pilotka_f1", Era.SOVIET_WW2, female = true),
            Art("ussr_ww2_pool_pilotka_f2", Era.SOVIET_WW2, female = true),
            Art("ussr_ww2_pool_pilotka_f3", Era.SOVIET_WW2, female = true),
            Art("ussr_ww2_pool_braid_f", Era.SOVIET_WW2, female = true),
            // --- Greek campaigns --------------------------------------------------------------
            Art("greece_dimitrios_karalis", Era.GREEK_WARTIME, female = false),
            // --- Yugoslav Partisans -----------------------------------------------------------
            Art("yugoslav_1941_milan_vukovic", Era.YUGOSLAV_PARTISANS, female = false),
            // --- Korean War -------------------------------------------------------------------
            Art("korean_1950_kang_chol", Era.KOREAN_WAR, female = false),
            // --- Vietnam War ------------------------------------------------------------------
            Art("vietnam_tran_minh", Era.VIETNAM_WAR, female = false),
        )

    private val byId: Map<String, Art> = ALL.associateBy { it.id }

    fun byId(id: String): Art? = byId[id]

    /**
     * The asset path for [artId], or null when the id is unknown — the caller then renders the
     * layer stack instead. Deliberately not an exception: a dropped asset must degrade the picture,
     * never the dossier.
     */
    fun pathFor(artId: String?): String? = artId?.takeIf { byId.containsKey(it) }?.let { "$DIR$it.png" }
}
