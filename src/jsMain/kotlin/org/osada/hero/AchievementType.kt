package org.osada.hero

/**
 * The kind of notable thing a unit's part in a resolved combat amounted to (§4.1, §19).
 *
 * This is the vocabulary [HeroAchievements] classifies a [RecognitionService.Contribution] into.
 * One combat can produce several — a defender that holds a river hex under attack is both
 * [HELD_UNDER_ATTACK] and [RIVER_ASSAULT] — so evidence from one action can feed more than one
 * category, exactly as §4.2 asks ("related actions add evidence to a thematic progress track").
 *
 * Terrain-derived types only fire alongside an already-notable action (a kill or a stand), never
 * for routine movement onto the terrain — keeping faith with §7.1's "notable actions, not every
 * routine attack" for progression the same way [RecognitionService] already does for emergence.
 */
enum class AchievementType {
    DESTROYED_ENEMY,
    DESTROYED_STRONGER_ENEMY,
    ARMORED_KILL,
    HELD_UNDER_ATTACK,
    SURVIVED_CRITICAL_DAMAGE,
    RIVER_ASSAULT,
    URBAN_ASSAULT,
    FOREST_ASSAULT,
    MOUNTAIN_ASSAULT,
}
