@file:Suppress("MaxLineLength")

package org.osada.model

import org.osada.LeaderType
import org.osada.UnitClass
import org.osada.hero.HeroTraitResolver
import org.osada.model.Leaders.getUnitClassLeader
import org.osada.multiplayer.coop.CoopRuntime
import org.osada.rules.AuxiliaryLeaders
import org.osada.rules.GameRandomSource
import org.osada.rules.UnitCapabilities
import org.osada.rules.UnitExperience

object Leaders {
    const val LEADER_CHANCE_THRESHOLD = 8

    /** OG's 20%: a roll on `[1, 10]` succeeds above [LEADER_CHANCE_THRESHOLD]. */
    private const val LEADER_CHANCE_CEILING = 10

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
        // ALL_WEATHER_COMBAT is on the two air classes that both roll leaders and initiate attacks.
        // Its rule (`AttackEligibility.airGroundedByWeather`) only ever fires for an air unit, so
        // without an air-class source it would stay inert no matter how many commanders held it --
        // which is exactly what happened while its only holder was a TANK/RECON legendary hero.
        // OG grants it to air units for the same reason.
        unitClassLeaders[UnitClass.FIGHTER.value] =
            listOf(
                LeaderType.SKILLED_INTERCEPTOR,
                LeaderType.AGGRESSIVE_ATTACK,
                LeaderType.AGGRESSIVE_MANEUVER,
                LeaderType.BATTLEFIELD_INTELLIGENCE,
                LeaderType.DETERMINED_DEFENSE,
                LeaderType.FIRST_STRIKE,
                LeaderType.ALL_WEATHER_COMBAT,
            )
        unitClassLeaders[UnitClass.TACTICAL_BOMBER.value] =
            listOf(
                LeaderType.SKILLED_ASSAULT,
                LeaderType.AGGRESSIVE_ATTACK,
                LeaderType.AGGRESSIVE_MANEUVER,
                LeaderType.DETERMINED_DEFENSE,
                LeaderType.FIRE_DISCIPLINE,
                LeaderType.FIRST_STRIKE,
                LeaderType.ALL_WEATHER_COMBAT,
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
            Pair(
                "Mountain Training",
                "When moving the unit treats forest, hill, rough and mountain hexes as clear terrain.",
            )
        description[LeaderType.BATTLEFIELD_INTELLIGENCE] =
            Pair(
                "Battlefield Intelligence",
                "The unit is not stopped by adjacent enemies, cannot be surprised, and gets +1 initiative when attacking.",
            )
        description[LeaderType.BRIDGING] =
            Pair(
                "Bridging",
                "When moving the unit treats river hexes as rough terrain, and while on a river it acts as a bridge.",
            )
        description[LeaderType.COMBAT_SUPPORT] =
            Pair(
                "Combat Support",
                "Lends this unit's experience bars to adjacent friendly units on the same air/ground layer; multiple sources stack.",
            )
        description[LeaderType.DETERMINED_DEFENSE] =
            Pair("Determined Defense", "Each of the unit defense factors is increased by 2.")
        description[LeaderType.DEVASTATING_FIRE] = Pair("Devastating Fire", "The unit may fire twice in a turn.")
        description[LeaderType.FEROCIOUS_DEFENSE] =
            Pair(
                "Ferocious Defense",
                "The unit entrenchment cannot be ignored by enemy units, and suppression does not weaken it.",
            )
        description[LeaderType.FIRE_DISCIPLINE] =
            Pair("Fire Discipline", "The unit will expend only one-half of an ammunition point each time it attacks.")
        description[LeaderType.FIRST_STRIKE] = Pair("First Strike", "The unit will fire first if it wins initiative.")
        description[LeaderType.FOREST_CAMOUFLAGE] =
            Pair(
                "Camouflage Expert",
                "In forest, city, desert, bocage or mountain the unit can only be spotted by adjacent units, " +
                    "and it ambushes the first enemy unit that enters its zone of control.",
            )
        description[LeaderType.INFILTRATION_TACTICS] =
            Pair(
                "Infiltration Tactics",
                "The unit ignores enemy entrenchment, acts as a bridge on a river, and beats First Strike by winning initiative by 2 or more.",
            )
        description[LeaderType.INFLUENCE] =
            Pair("Influence", "Upgrades and replacements for the unit cost half the prestige.")
        description[LeaderType.LIBERATOR] =
            Pair("Liberator", "The unit can capture objectives, and you receive double prestige for those it captures.")
        description[LeaderType.OVERWATCH] =
            Pair("Overwatch", "Each turn the unit ambushes the first enemy unit that moves within its range.")
        description[LeaderType.OVERWHELMING_ATTACK] =
            Pair("Overwhelming Attack", "When attacking suppression points are converted to kills.")
        description[LeaderType.RECON_MOVEMENT] = Pair("Recon Movement", "The unit is permitted phased movement.")
        description[LeaderType.RESILIENCE] =
            Pair("Resilience", "The unit will suffer 1 to 3 fewer casualties when attacked, and cannot be overrun.")
        description[LeaderType.SHOCK_TACTICS] =
            Pair("Shock Tactics", "Suppression inflicted lasts the entire player turn.")
        description[LeaderType.SKILLED_GROUND_ATTACK] =
            Pair("Skilled Ground Attack", "An air unit attacking a ground unit gets +4 attack.")
        description[LeaderType.SKILLED_RECONNAISSANCE] =
            Pair("Skilled Reconnaissance", "The unit spotting range is increased by two hexes.")
        description[LeaderType.STREET_FIGHTER] = Pair("Street Fighter", "The unit ignores enemy city entrenchment.")
        description[LeaderType.SUPERIOR_MANEUVER] =
            Pair("Superior Maneuver", "The unit may bypass enemy zones of control.")
        description[LeaderType.EXTENDED_FRONT] =
            Pair("Extended Front", "The unit gives support fire to adjacent friendly units.")
        description[LeaderType.FLAK_SUPPORT] =
            Pair("Flak Support", "The unit gives support fire, and may intercept aircraft more than once a turn.")
        description[LeaderType.ARMOURED_HUNTER] =
            Pair("Armoured Hunter", "+1 initiative in combat against armoured targets.")
        description[LeaderType.EXPERT_SEA_WOLF] =
            Pair("Expert Sea Wolf", "The submarine ignores enemy zones of control and gets +1 defense against ships.")
        description[LeaderType.DEEP_SEA_HUNTER] =
            Pair(
                "Deep Sea Hunter",
                "Targets cannot evade the destroyer's attacks, and it gets +1 attack against ships.",
            )
        description[LeaderType.EXPERT_SEA_BASE] =
            Pair("Expert Sea Base", "The carrier suffers 1 to 3 fewer casualties when attacked (Resilience).")
        description[LeaderType.BLOCKADE_VESSEL] =
            Pair("Blockade Vessel", "The ship's attack range is increased by one hex (Marksman).")
        description[LeaderType.SCREENING_VESSEL] =
            Pair("Screening Vessel", "The ship cannot be surprised and gets +1 initiative (Battlefield Intelligence).")
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
     * A random individual attribute for [unit], drawn from Open General's list ([OgLeaderPools]), or
     * -1 when the unit may not have one.
     *
     * The pool never contains the class attribute: [getUnitClassLeader] grants that separately and
     * unconditionally, so rolling it here would be a wasted roll.
     *
     * **Was Panzer Marshal's per-class pools until 2026-09-28** ([unitClassLeaders], which now only
     * feeds the campaign hero generator). Those covered eight classes with six to eight traits
     * each and gave the other thirteen nothing — so a Flak battery, a warship or a level bomber that
     * an OG author placed WITH a leader lost it on load, and no scenario unit could ever roll
     * Camouflage Expert, Overwatch, Resilience or a dozen more of OG's traits.
     */
    @Suppress("ReturnCount")
    fun generateLeader(unit: GameUnit?): Int {
        // THREE refusals, all checked here rather than at the callers, because this is the only
        // place a legacy leader is minted -- combat promotion, the full-experience grant, a trigger's
        // Raise Leader and the leaders a scenario's own units are born with all come through it:
        //  * OG's `Cannot get a leader` (`attrEx` bit 0), wired 2026-08-26. The hero system's half
        //    of the same ability is in `HeroCampaign.attemptEmergence`;
        //  * OG's `noldr_auxunits`, wired 2026-08-28 -- an auxiliary formation in a campaign
        //    (`rules/AuxiliaryLeaders`);
        //  * OG's `noldr_classunits` -- a class the efile excludes ([OgLeaderPools.classDisabledByEfile]).
        if (unit == null) return -1
        // A cooperative match gives the shared army commanders, not unit leaders (design §5.3): the
        // legacy roll is suppressed here, the one place a legacy leader is minted.
        if (CoopRuntime.governs(unit)) return -1
        val uclass = unit.unitData().uclass
        val refused =
            !UnitCapabilities.canProduceLeader(unit.unitData(true)) ||
                AuxiliaryLeaders.refuses(unit) ||
                OgLeaderPools.classDisabledByEfile(uclass)
        // OG's per-unit and army skips (`OgLeaderAward`, 2026-10-09) on top of the class pool.
        val pool = if (refused) emptyList() else OgLeaderAward.pool(unit)
        return if (pool.isEmpty()) -1 else pool[GameRandomSource.nextInt(pool.size)].value
    }

    /**
     * The legacy acquisition roll, for a unit outside the campaign hero system — Open General's
     * rule (`Manual_OG-en.pdf` §6.12): *"When any unit gains an experience bar (when it reaches 100
     * experience points or multiple, with the limit in 500), it has the opportunity of 20% of
     * generating a leader."* The author's combat page states the same trigger: *"if Unit increases
     * its bars, engine checks if unit can get a leader."*
     *
     * So only a combat that carried the unit across a bar rolls at all, and each bar crossed is
     * one 20% chance. Bars are counted by [UnitExperience], the engine's one bar scale.
     *
     * **History.** Restored to PM's rule on 2026-09-27 (uniform over `[bars, 10]`, success above 8:
     * 20%, 2/9, 25%, 2/7 for bars 1-4), on the belief that OG published no formula. It does — the
     * sentence above — and PM's only coincides with it at the first bar. Switched to OG's flat 20%
     * on 2026-09-28. The trigger (a bar-up, not a lottery on every combat) was PM's and OG's alike.
     *
     * The maximum-experience guarantee is PM's and is kept: OG's manual says nothing about a unit
     * at the cap, and without it a capped unit that missed its last roll could never get one.
     * Classes OG only opened to DESIGN-TIME leaders (bunkers, transports) never earn one here.
     */
    fun generateLeaderWithChance(
        unit: GameUnit?,
        expGained: Int,
    ): Int {
        if (unit == null || unit.leader != -1) return -1
        return when {
            !OgLeaderPools.earnsInCombat(unit.unitData().uclass) -> -1
            unit.experience >= UnitExperience.cap() -> generateLeader(unit)
            else -> rollLeaderChance(unit, expGained)
        }
    }

    private fun rollLeaderChance(
        unit: GameUnit,
        expGained: Int,
    ): Int {
        val barsNow = UnitExperience.barsFor(unit.experience)
        val barsBefore = UnitExperience.barsFor(unit.experience - expGained)
        repeat(barsNow - barsBefore) {
            if (GameRandomSource.nextInRange(1, LEADER_CHANCE_CEILING) > LEADER_CHANCE_THRESHOLD) {
                return generateLeader(unit)
            }
        }
        return -1
    }

    /**
     * The led formation's **class attribute** -- OG's second leader trait, the one fixed by unit
     * class rather than picked per formation (`docs/heroes/legacy-leaders.md` §1).
     *
     * Derived from OG's class table ([OgLeaderPools.classTrait]) by default, and **overridden by the scenario author
     * when
     * [GameUnit.leaderClassTrait] is set**: that is OG's `.xscn` unit `@37`, the Suite's *"According
     * unit's class"* selector, whose zero means exactly this function's derivation and whose
     * non-zero value is the author choosing a different signature for this one formation. 1,401
     * formations across the corpus override it.
     *
     * The override is honoured even when the named trait is not in the unit's class pool: the
     * author is overriding the class, so filtering by the class would make the field a no-op
     * wherever it actually says something. It is still gated on the formation HAVING a leader,
     * because OG's second attribute belongs to a leader and the derived case always was.
     */
    fun getUnitClassLeader(unit: GameUnit?): Int {
        if (unit == null || unit.leader == -1) return -1
        return unit.leaderClassTrait.takeIf { it > 0 }
            ?: OgLeaderPools.classTrait(unit.unitData().uclass)?.value
            ?: -1
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
