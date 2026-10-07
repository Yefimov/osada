package org.osada.model

/** The passenger whose take-off hex the player is choosing ([GameMap.openHangarLaunchTargeting]). */
internal class HangarLaunch(
    val container: GameUnit,
    val passenger: GameUnit,
)
