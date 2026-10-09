package org.osada.model

import org.osada.GameHolder
import org.osada.LeaderType
import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.rules.MovementRules
import org.osada.rules.UnitCapabilities
import org.osada.rules.UnitPredicates

/**
 * OG's own per-unit filter for the RANDOM leader attribute (legacy path), from the author's leader
 * page (luis-guzman.com/OpenGen_Leaders.html, added 2026-10-09 at the owner's request): *"Engine
 * sets up a list of possible leaders attributes ... skipping some of them depending unit's
 * specifics."* [OgLeaderPools.randomPool] only knows the class; this adds what the equipment, the
 * movement method and the rest of the army say.
 *
 * | OG rule | here |
 * |---|---|
 * | Partisan special: skip Battlefield Intelligence | [UnitCapabilities.isPartizan] |
 * | All-Weather special: skip All Weather Combat | [UnitCapabilities.hasAllWeather] |
 * | cannot move and has no transport: skip the eight movement-dependent traits ([NEEDS_MOVEMENT]) | [immobile] |
 * | air unit: skip Aggressive Maneuver | |
 * | move method Mountain: skip Mountain Training | [MovMethod.ALL_TERRAIN_LEG] |
 * | recon ability: skip Skilled Reconnaissance and Recon Movement | [UnitCapabilities.hasPhasedMovement] |
 * | Combat-Support special: skip Combat Support | [UnitCapabilities.grantsCombatSupport] |
 * | already inflicts lasting suppression: skip Shock Tactics | [UnitCapabilities.hasLastingSuppression] |
 * | Fire-Support special in Flak class: skip Extended Front | [UnitCapabilities.hasSupportFire] |
 * | Bridge special: skip Bridging | [MovementRules.hasBridgeCapability] |
 * | ship that cannot capture: skip Liberator | [UnitCapabilities.canCaptureHex] |
 * | *"If there are already 2 leaders of any type ... type is skipped"* | [ARMY_CAP], per player |
 *
 * PG2 mode's two skips are not modelled: OSADA has no PG2 mode. The page does not say whether the
 * two-leader cap counts per player, side or scenario; it is read per player (the army), counting
 * the individual attribute of that player's formations on the map.
 */
internal object OgLeaderAward {
    const val ARMY_CAP = 2

    private val NEEDS_MOVEMENT =
        setOf(
            LeaderType.BATTLEFIELD_INTELLIGENCE,
            LeaderType.LIBERATOR,
            LeaderType.ALPINE_TRAINING,
            LeaderType.INFILTRATION_TACTICS,
            LeaderType.AGGRESSIVE_MANEUVER,
            LeaderType.RECON_MOVEMENT,
            LeaderType.SUPERIOR_MANEUVER,
            LeaderType.BRIDGING,
        )

    /** [OgLeaderPools.randomPool] for this formation, less OG's unit-specific and army skips. */
    fun pool(unit: GameUnit): List<LeaderType> =
        OgLeaderPools
            .randomPool(unit.unitData().uclass)
            .filter { trait -> !skippedFor(unit, trait) && armyCount(unit, trait) < ARMY_CAP }

    @Suppress("CyclomaticComplexMethod") // one OG skip rule per branch, as the page lists them
    fun skippedFor(
        unit: GameUnit,
        trait: LeaderType,
    ): Boolean {
        val data = unit.unitData(true)
        return when (trait) {
            LeaderType.BATTLEFIELD_INTELLIGENCE -> UnitCapabilities.isPartizan(data) || immobile(unit)
            LeaderType.ALL_WEATHER_COMBAT -> UnitCapabilities.hasAllWeather(unit)
            LeaderType.AGGRESSIVE_MANEUVER -> UnitPredicates.isAir(unit) || immobile(unit)
            LeaderType.ALPINE_TRAINING -> data.movmethod == MovMethod.ALL_TERRAIN_LEG.value || immobile(unit)
            LeaderType.SKILLED_RECONNAISSANCE -> UnitCapabilities.hasPhasedMovement(data)
            LeaderType.RECON_MOVEMENT -> UnitCapabilities.hasPhasedMovement(data) || immobile(unit)
            LeaderType.COMBAT_SUPPORT -> UnitCapabilities.grantsCombatSupport(data)
            LeaderType.SHOCK_TACTICS -> UnitCapabilities.hasLastingSuppression(unit)
            LeaderType.EXTENDED_FRONT ->
                data.uclass == UnitClass.FLAK.value && UnitCapabilities.hasSupportFire(data)
            LeaderType.BRIDGING -> MovementRules.hasBridgeCapability(unit) || immobile(unit)
            LeaderType.LIBERATOR ->
                immobile(unit) || (UnitPredicates.isSea(unit) && !UnitCapabilities.canCaptureHex(data))
            else -> trait in NEEDS_MOVEMENT && immobile(unit)
        }
    }

    /** OG's *"cannot move and has no gtp"*: no movement points and no ground transport to ride. */
    private fun immobile(unit: GameUnit): Boolean = unit.unitData(true).movpoints <= 0 && unit.transport == null

    /** How many of [unit]'s own army's formations already lead with [trait] as their individual
     *  attribute. */
    private fun armyCount(
        unit: GameUnit,
        trait: LeaderType,
    ): Int {
        val owner = unit.player?.id
        val units =
            GameHolder.instance
                ?.scenario
                ?.map
                ?.units
                .orEmpty()
        return units.count {
            owner != null && it !== unit && !it.destroyed && it.player?.id == owner && it.leader == trait.value
        }
    }
}
