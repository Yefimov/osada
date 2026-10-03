package org.osada.multiplayer.coop

import org.osada.hero.HeroPortraitArt
import org.osada.hero.PortraitComposerV2.Pool

/** Local cosmetic choices; the completed layer recipe, not these picker settings, goes on the wire. */
internal object CoopPortraitGeneration {
    fun generate(
        seed: Int,
        era: String?,
        female: Boolean,
    ): CoopPortrait {
        val chosenEra =
            HeroPortraitArt.Era.entries.firstOrNull { it.name == era }
                ?: HeroPortraitArt.Era.entries[seed.mod(HeroPortraitArt.Era.entries.size)]
        val pool = poolFor(chosenEra)
        val portrait = CoopPortrait.generated(seed, pool ?: Pool.USSR_1942, female)
        return if (pool == null) neutralClothing(portrait, female) else portrait
    }

    private fun poolFor(era: HeroPortraitArt.Era): Pool? =
        when (era) {
            HeroPortraitArt.Era.ANTIQUITY -> Pool.ANCIENT_REBEL
            HeroPortraitArt.Era.FORTY_EIGHTER -> null
            HeroPortraitArt.Era.RUSSIAN_CIVIL_WAR,
            HeroPortraitArt.Era.CZECHOSLOVAK_LEGION,
            HeroPortraitArt.Era.REVOLUTIONARY_EUROPE,
            -> Pool.REVOLUTION_1919
            HeroPortraitArt.Era.CHINESE_REVOLUTION, HeroPortraitArt.Era.VIETNAM_WAR -> Pool.EAST_ASIAN_REVOLUTIONARY
            HeroPortraitArt.Era.SPANISH_CIVIL_WAR -> Pool.SPANISH_REPUBLIC_1936
            HeroPortraitArt.Era.SOVIET_INTERWAR -> Pool.SOVIET_INTERWAR
            HeroPortraitArt.Era.SOVIET_WW2 -> Pool.USSR_1942
            HeroPortraitArt.Era.GREEK_WARTIME -> Pool.GREEK_1940
            HeroPortraitArt.Era.YUGOSLAV_PARTISANS -> Pool.YUGOSLAV_PARTISAN_1941
            HeroPortraitArt.Era.KOREAN_WAR -> Pool.EAST_ASIAN_REVOLUTIONARY
        }

    /** No 1848–1865 uniform pack exists: use plain shoulders and full hair, without modern insignia. */
    private fun neutralClothing(
        portrait: CoopPortrait,
        female: Boolean,
    ): CoopPortrait {
        val layers =
            portrait.layerIds
                .filter {
                    it.startsWith("bg_") ||
                        it.startsWith("back_") ||
                        it.startsWith("face_") ||
                        it.startsWith("age_face_") ||
                        it.startsWith("facial_")
                }.toMutableList()
        layers.add(
            layers.indexOfFirst {
                it.startsWith("face_")
            },
            if (female) "hair_back_female_bun" else "hair_back_short_medium",
        )
        layers.add(if (female) "hair_front_female_bun" else "hair_front_side")
        return portrait.copy(layerIds = layers, poolId = null)
    }
}
