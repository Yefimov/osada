package org.osada.hero

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
        /** Russian Civil War: budenovka, papakha, no shoulder boards. */
        RUSSIAN_CIVIL_WAR(1917..1922),

        /** Great Patriotic War: pilotka/ushanka/peaked cap, 1943-pattern pogony on the later ranks. */
        SOVIET_WW2(1936..1955),
    }

    data class Art(
        val id: String,
        val era: Era,
        val female: Boolean,
    )

    val ALL: List<Art> =
        listOf(
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
