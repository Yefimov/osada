@file:Suppress("MaxLineLength")

package org.osada.model

import org.osada.LeaderType
import org.osada.UNIT_MAX_EXPERIENCE
import org.osada.UnitClass
import org.osada.hero.HeroTraitResolver

object Leaders {
    const val LEADER_CHANCE_THRESHOLD = 8

    val unitClassLeaders: MutableMap<Int, List<LeaderType>> = mutableMapOf()
    val description: MutableMap<LeaderType, Pair<String, String>> = mutableMapOf()

    init {
        unitClassLeaders[UnitClass.NONE.value] = emptyList()
        unitClassLeaders[UnitClass.INFANTRY.value] =
            listOf(
                LeaderType.TENACIOUS_DEFENSE,
                LeaderType.AGGRESSIVE_ATTACK,
                LeaderType.AGGRESSIVE_MANEUVER,
                LeaderType.BATTLEFIELD_INTELLIGENCE,
                LeaderType.DETERMINED_DEFENSE,
                LeaderType.FEROCIOUS_DEFENSE,
                LeaderType.FIRST_STRIKE,
                LeaderType.INFILTRATION_TACTICS,
                LeaderType.LIBERATOR,
            )
        unitClassLeaders[UnitClass.TANK.value] =
            listOf(
                LeaderType.AGGRESSIVE_TANK_MANEUVER,
                LeaderType.AGGRESSIVE_ATTACK,
                LeaderType.AGGRESSIVE_MANEUVER,
                LeaderType.BATTLEFIELD_INTELLIGENCE,
                LeaderType.DETERMINED_DEFENSE,
                LeaderType.FIRST_STRIKE,
                LeaderType.INFILTRATION_TACTICS,
                LeaderType.LIBERATOR,
            )
        unitClassLeaders[UnitClass.RECON.value] =
            listOf(
                LeaderType.ELITE_RECON_VETERAN,
                LeaderType.AGGRESSIVE_ATTACK,
                LeaderType.AGGRESSIVE_MANEUVER,
                LeaderType.BATTLEFIELD_INTELLIGENCE,
                LeaderType.DETERMINED_DEFENSE,
                LeaderType.FIRST_STRIKE,
                LeaderType.INFILTRATION_TACTICS,
                LeaderType.LIBERATOR,
            )
        unitClassLeaders[UnitClass.ANTI_TANK.value] =
            listOf(
                LeaderType.TANK_KILLER,
                LeaderType.AGGRESSIVE_ATTACK,
                LeaderType.AGGRESSIVE_MANEUVER,
                LeaderType.BATTLEFIELD_INTELLIGENCE,
                LeaderType.DETERMINED_DEFENSE,
                LeaderType.FEROCIOUS_DEFENSE,
                LeaderType.FIRST_STRIKE,
                LeaderType.INFILTRATION_TACTICS,
                LeaderType.LIBERATOR,
            )
        unitClassLeaders[UnitClass.FLAK.value] = emptyList()
        unitClassLeaders[UnitClass.FORTIFICATION.value] = emptyList()
        unitClassLeaders[UnitClass.GROUND_TRANSPORT.value] = emptyList()
        unitClassLeaders[UnitClass.ARTILLERY.value] =
            listOf(
                LeaderType.MARKSMAN,
                LeaderType.AGGRESSIVE_ATTACK,
                LeaderType.AGGRESSIVE_MANEUVER,
                LeaderType.BATTLEFIELD_INTELLIGENCE,
                LeaderType.DETERMINED_DEFENSE,
                LeaderType.FIRE_DISCIPLINE,
                LeaderType.INFILTRATION_TACTICS,
            )
        unitClassLeaders[UnitClass.AIR_DEFENCE.value] =
            listOf(
                LeaderType.MECHANIZED_VETERAN,
                LeaderType.AGGRESSIVE_ATTACK,
                LeaderType.AGGRESSIVE_MANEUVER,
                LeaderType.DETERMINED_DEFENSE,
                LeaderType.FIRE_DISCIPLINE,
                LeaderType.INFILTRATION_TACTICS,
            )
        unitClassLeaders[UnitClass.FIGHTER.value] =
            listOf(
                LeaderType.SKILLED_INTERCEPTOR,
                LeaderType.AGGRESSIVE_ATTACK,
                LeaderType.AGGRESSIVE_MANEUVER,
                LeaderType.BATTLEFIELD_INTELLIGENCE,
                LeaderType.DETERMINED_DEFENSE,
                LeaderType.FIRST_STRIKE,
            )
        unitClassLeaders[UnitClass.TACTICAL_BOMBER.value] =
            listOf(
                LeaderType.SKILLED_ASSAULT,
                LeaderType.AGGRESSIVE_ATTACK,
                LeaderType.AGGRESSIVE_MANEUVER,
                LeaderType.DETERMINED_DEFENSE,
                LeaderType.FIRE_DISCIPLINE,
                LeaderType.FIRST_STRIKE,
            )
        unitClassLeaders[UnitClass.LEVEL_BOMBER.value] = emptyList()
        unitClassLeaders[UnitClass.AIR_TRANSPORT.value] = emptyList()
        unitClassLeaders[UnitClass.SUBMARINE.value] = emptyList()
        unitClassLeaders[UnitClass.DESTROYER.value] = emptyList()
        unitClassLeaders[UnitClass.BATTLESHIP.value] = emptyList()
        unitClassLeaders[UnitClass.CARRIER.value] = emptyList()
        unitClassLeaders[UnitClass.NAVAL_TRANSPORT.value] = emptyList()
        unitClassLeaders[UnitClass.BATTLE_CRUISER.value] = emptyList()
        unitClassLeaders[UnitClass.CRUISER.value] = emptyList()
        unitClassLeaders[UnitClass.LIGHT_CRUISER.value] = emptyList()

        description[LeaderType.MECHANIZED_VETERAN] =
            Pair("Mechanized Veteran", "Air Defence unit may move and fire in the same turn.")
        description[LeaderType.TANK_KILLER] =
            Pair("Tank Killer", "Anti-Tank unit will not receive a penalty for movement into combat.")
        description[LeaderType.MARKSMAN] = Pair("Marksman", "The artillery unit attack range is increased by one hex.")
        description[LeaderType.SKILLED_INTERCEPTOR] =
            Pair("Skilled Interceptor", "Fighter unit can intercept multiple enemy fighters in the defensive phase.")
        description[LeaderType.TENACIOUS_DEFENSE] =
            Pair("Tenacious Defense", "The infantry unit ground defense factor is increased by 4.")
        description[LeaderType.ELITE_RECON_VETERAN] =
            Pair("Elite Recon Veteran", "Recon unit spotting range is increased by two hexes.")
        description[LeaderType.SKILLED_ASSAULT] =
            Pair("Skilled Assault", "The tactical bomber cannot be surprised while moving.")
        description[LeaderType.AGGRESSIVE_TANK_MANEUVER] =
            Pair("Aggressive Tank Maneuver", "Tank movement factor is increased by 1.")
        description[LeaderType.AGGRESSIVE_ATTACK] =
            Pair("Aggressive Attack", "Each of the unit attack values is increased by 2.")
        description[LeaderType.AGGRESSIVE_MANEUVER] =
            Pair("Aggressive Maneuver", "The unit movement factor is increased by 1.")
        description[LeaderType.ALL_WEATHER_COMBAT] =
            Pair("All Weather Combat", "The air unit is not affected by weather conditions.")
        description[LeaderType.ALPINE_TRAINING] =
            Pair("Alpine Training", "When moving the unit treats forest and mountain hexes as clear terrain.")
        description[LeaderType.BATTLEFIELD_INTELLIGENCE] =
            Pair("Battlefield Intelligence", "The unit cannot be surprised.")
        description[LeaderType.BRIDGING] =
            Pair("Bridging", "When moving the unit treats passable river hexes as rough terrain.")
        description[LeaderType.COMBAT_SUPPORT] =
            Pair(
                "Combat Support",
                "Lends this unit's experience bars to adjacent friendly units on the same air/ground layer; multiple sources stack.",
            )
        description[LeaderType.DETERMINED_DEFENSE] =
            Pair("Determined Defense", "Each of the unit defense factors is increased by 2.")
        description[LeaderType.DEVASTATING_FIRE] = Pair("Devastating Fire", "The unit may fire twice in a turn.")
        description[LeaderType.FEROCIOUS_DEFENSE] =
            Pair("Ferocious Defense", "The unit entrenchment cannot be ignored by enemy units.")
        description[LeaderType.FIRE_DISCIPLINE] =
            Pair("Fire Discipline", "The unit will expend only one-half of an ammunition point each time it attacks.")
        description[LeaderType.FIRST_STRIKE] = Pair("First Strike", "The unit will fire first if it wins initiative.")
        description[LeaderType.FOREST_CAMOUFLAGE] =
            Pair("Forest Camouflage", "In a forest hex the unit cannot be spotted unless enemy moves adjacent.")
        description[LeaderType.INFILTRATION_TACTICS] =
            Pair("Infiltration Tactics", "The unit ignores enemy unit entrenchment when calculating combat results.")
        description[LeaderType.INFLUENCE] =
            Pair("Influence", "Allows the unit to upgrade to better equipment at reduced prestige cost.")
        description[LeaderType.LIBERATOR] =
            Pair("Liberator", "You receive double prestige for objectives captured by the unit.")
        description[LeaderType.OVERWATCH] =
            Pair("Overwatch", "The unit will fire at any enemy unit that moves within range.")
        description[LeaderType.OVERWHELMING_ATTACK] =
            Pair("Overwhelming Attack", "When attacking suppression points are converted to kills.")
        description[LeaderType.RECON_MOVEMENT] = Pair("Recon Movement", "The unit is permitted phased movement.")
        description[LeaderType.RESILIENCE] =
            Pair("Resilience", "The unit will suffer 1 to 3 fewer casualties when attacked.")
        description[LeaderType.SHOCK_TACTICS] =
            Pair("Shock Tactics", "Suppression inflicted lasts the entire player turn.")
        description[LeaderType.SKILLED_GROUND_ATTACK] =
            Pair("Skilled Ground Attack", "The unit inflicts 1 to 3 more casualties when attacking.")
        description[LeaderType.SKILLED_RECONNAISSANCE] =
            Pair("Skilled Reconnaissance", "The unit spotting range is increased by one hex.")
        description[LeaderType.STREET_FIGHTER] = Pair("Street Fighter", "The unit ignores enemy city entrenchment.")
        description[LeaderType.SUPERIOR_MANEUVER] =
            Pair("Superior Maneuver", "The unit may bypass enemy zones of control.")
    }

    /**
     * Whether [unit] has [leader]'s trait, from any source.
     *
     * The combat rules' single entry point for trait checks. It now delegates to
     * [HeroTraitResolver], which answers from campaign hero state when the unit belongs to a
     * formation with a commander and falls back to the legacy integer otherwise — see that class
     * for why the switch lives there rather than at the ~10 call sites.
     *
     * The signature is unchanged on purpose: no combat code was touched to introduce heroes.
     */
    fun unitHasLeader(
        unit: GameUnit?,
        leader: LeaderType,
    ): Boolean = HeroTraitResolver.hasTrait(unit, leader)

    /**
     * A random non-signature leader for [unit]'s class, or -1 when the class has none.
     *
     * Index 0 is skipped deliberately: it is the class signature trait, which
     * [getUnitClassLeader] grants separately and unconditionally, so rolling it here would be a
     * wasted roll. **The last entry used to be skipped too** — `(random * (size - 2)) + 1` spans
     * `1..size-2` — which was not deliberate, and cost `LeaderType.LIBERATOR` its entire existence:
     * Liberator is the last entry in all four lists that contain it, is honoured in combat code
     * (`CombatApplication` doubles capture prestige for it), and could therefore never be obtained
     * by any means. See `tools/og-import/DEFERRED.md` §7.43.
     */
    fun generateLeader(unit: GameUnit?): Int {
        if (unit == null) return -1
        val leaders = unitClassLeaders[unit.unitData().uclass]
        return if (leaders == null || leaders.size < 2) {
            -1
        } else {
            leaders[(kotlin.random.Random.nextDouble() * (leaders.size - 1)).toInt() + 1].value
        }
    }

    fun generateLeaderWithChance(
        unit: GameUnit?,
        expGained: Int,
    ): Int {
        if (unit == null || unit.leader != -1) return -1
        return when {
            unit.experience >= UNIT_MAX_EXPERIENCE -> generateLeader(unit)
            else -> rollLeaderChance(unit, expGained)
        }
    }

    private fun rollLeaderChance(
        unit: GameUnit,
        expGained: Int,
    ): Int {
        val level = unit.experience / 100
        val levelAfter = (unit.experience - expGained) / 100
        if (levelAfter <= 0) return -1
        val chance = kotlin.random.Random.nextInt(level, 10 - level + 1)
        return if (chance > LEADER_CHANCE_THRESHOLD) generateLeader(unit) else -1
    }

    fun getUnitClassLeader(unit: GameUnit?): Int {
        if (unit == null || unit.leader == -1) return -1
        val leaders = unitClassLeaders[unit.unitData().uclass]
        return leaders?.firstOrNull()?.value ?: -1
    }

    fun getUnitLeaderDescriptions(unit: GameUnit?): List<Pair<String, String>> {
        if (unit == null || unit.leader == -1) return emptyList()
        val result = mutableListOf<Pair<String, String>>()
        val leaderType = LeaderType.entries.find { it.value == unit.leader }
        leaderType?.let { description[it]?.let { desc -> result.add(desc) } }
        val classLeaderType = LeaderType.entries.find { it.value == getUnitClassLeader(unit) }
        classLeaderType?.let { description[it]?.let { desc -> result.add(desc) } }
        return result
    }
}
