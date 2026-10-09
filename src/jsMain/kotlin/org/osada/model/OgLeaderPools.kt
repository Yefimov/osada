package org.osada.model

import org.osada.LeaderType
import org.osada.UnitClass

/**
 * Open General's leader tables for the LEGACY path — every unit outside the campaign hero system:
 * standalone-scenario units, the AI's forces, and a scenario's own pre-placed leaders.
 *
 * OG gives every leader two attributes (`Manual_OG-en.pdf` §6.12): *"one of the abilities is
 * determined by class, while the other is random"*. The random one is drawn from OG's own
 * `* Leaders` list entries 9..34 — `equip.cfg` states the range when it documents
 * `noldr_randtype` (*"random leader type numbers to disable (>=9 and <=34)"*) — and *"some doesn't
 * apply to some classes"*.
 *
 * **What this replaces.** Until 2026-09-28 the legacy path rolled from Panzer Marshal's pools
 * (`Leaders.unitClassLeaders`): eight classes, six to eight traits each, and thirteen classes with
 * no pool at all. Two consequences: a scenario unit could never roll Camouflage Expert, Overwatch,
 * Resilience, Combat Support and ten more OG traits; and a leader an OG author placed on a Flak
 * battery, a ship, a level bomber or a transport **vanished on load**, because
 * `generateLeader` returned -1 for an empty pool. That was every such leader in the corpus whose
 * individual attribute was left to OG — the overwhelming majority.
 *
 * `Leaders.unitClassLeaders` still exists and still feeds the campaign hero generator
 * (`hero/ProceduralHeroGenerator`), which is a separate, settled design
 * (`docs/heroes/README.md` §2); this object does not touch it.
 */
object OgLeaderPools {
    /**
     * OG's random list, entries 9..34 in OG's own order (`OPENTXT_SAMPLE/strings-en-template.txt`,
     * `* Leaders`), keyed by the OG id so `noldr_randtype` can name them. Entry 22 is OG's second
     * *Aggressive Maneuver* listing — the first (2) is the Tank class attribute — and maps to the
     * same [LeaderType.AGGRESSIVE_MANEUVER] that `tools/og-import/add_leader_traits.py` uses.
     */
    val RANDOM_LIST: List<Pair<Int, LeaderType>> =
        listOf(
            9 to LeaderType.AGGRESSIVE_ATTACK,
            10 to LeaderType.DETERMINED_DEFENSE,
            11 to LeaderType.BATTLEFIELD_INTELLIGENCE,
            12 to LeaderType.FIRST_STRIKE,
            13 to LeaderType.RESILIENCE,
            14 to LeaderType.INFLUENCE,
            15 to LeaderType.FIRE_DISCIPLINE,
            16 to LeaderType.LIBERATOR,
            17 to LeaderType.ALPINE_TRAINING,
            18 to LeaderType.ALL_WEATHER_COMBAT,
            19 to LeaderType.FOREST_CAMOUFLAGE,
            20 to LeaderType.COMBAT_SUPPORT,
            21 to LeaderType.INFILTRATION_TACTICS,
            22 to LeaderType.AGGRESSIVE_MANEUVER,
            23 to LeaderType.SKILLED_RECONNAISSANCE,
            24 to LeaderType.RECON_MOVEMENT,
            25 to LeaderType.SKILLED_GROUND_ATTACK,
            26 to LeaderType.STREET_FIGHTER,
            27 to LeaderType.OVERWHELMING_ATTACK,
            28 to LeaderType.SUPERIOR_MANEUVER,
            29 to LeaderType.EXTENDED_FRONT,
            30 to LeaderType.FEROCIOUS_DEFENSE,
            31 to LeaderType.OVERWATCH,
            32 to LeaderType.DEVASTATING_FIRE,
            33 to LeaderType.BRIDGING,
            34 to LeaderType.SHOCK_TACTICS,
        )

    /**
     * Traits whose rule can only ever fire for a LAND formation: they read terrain movement cost,
     * entrenchment, city fighting, flag capture, land concealment, the ground zone of control or
     * ground support fire. Offering one to a ship or an aircraft would put a promise in the unit
     * card that no rule keeps (`docs/heroes/legacy-leaders.md` §8).
     */
    private val LAND_ONLY =
        setOf(
            LeaderType.LIBERATOR,
            LeaderType.ALPINE_TRAINING,
            LeaderType.FOREST_CAMOUFLAGE,
            LeaderType.INFILTRATION_TACTICS,
            LeaderType.STREET_FIGHTER,
            LeaderType.SUPERIOR_MANEUVER,
            LeaderType.EXTENDED_FRONT,
            LeaderType.FEROCIOUS_DEFENSE,
            LeaderType.BRIDGING,
        )

    /** Traits whose rule reads only an AIRCRAFT: flying in bad weather, and OG's air-to-ground
     *  bonus (*"if A is air-unit with SkilledGroundAttack leader and P is land-unit"* — the author's
     *  combat page). */
    private val AIR_ONLY = setOf(LeaderType.ALL_WEATHER_COMBAT, LeaderType.SKILLED_GROUND_ATTACK)

    /** Opportunity fire on a formation MOVING within range: a surface unit's reaction. None of the
     *  corpus's 139 authored Overwatch leaders sits on an aircraft. */
    private val SURFACE_ONLY = setOf(LeaderType.OVERWATCH)

    /** Movement traits, which a formation that never moves cannot use. */
    private val MOVEMENT =
        setOf(LeaderType.AGGRESSIVE_MANEUVER, LeaderType.RECON_MOVEMENT, LeaderType.SUPERIOR_MANEUVER)

    /**
     * The class attribute — OG's manual §6.12 list, which names ten classes. Tank keeps
     * [LeaderType.AGGRESSIVE_TANK_MANEUVER], OSADA's name for OG's *Aggressive Maneuver : +1
     * Movement* class entry (identical effect, and the value every existing save already derives).
     *
     * The classes the manual does not name, settled from the corpus of 32,145 led OG formations
     * (`.xscn` `@37`, the class selector):
     *  * **Fortification** → Tenacious Defense: it is a defensive work, the corpus splits 6/5
     *    between Marksman and Tenacious, and Marksman's range would reach further than a bunker's
     *    gun was ever authored to;
     *  * **Level bomber** → Skilled Assault, the corpus's only value and the other bomber's;
     *  * the warships → OG's current leader table, which supersedes the manual's single *"Ships"*
     *    line (2026-10-09): submarine Expert Sea Wolf, destroyer Deep Sea Hunter, carrier Expert Sea
     *    Base, light cruiser Screening Vessel, the heavier ships Blockade Vessel (Marksman's range);
     *  * **the three transport classes** → none. OG only allowed a leader on a transport at design
     *    time from 24.12.10.0 (changelog: *"Enabled to assign a leader at design time for Bunkers
     *    and any Transport"*) and names no class attribute for one; an author who wants one writes
     *    `ldrclasstrait`, which [Leaders.getUnitClassLeader] honours.
     */
    private val CLASS_TRAIT: Map<Int, LeaderType> =
        mapOf(
            UnitClass.INFANTRY.value to LeaderType.TENACIOUS_DEFENSE,
            UnitClass.TANK.value to LeaderType.AGGRESSIVE_TANK_MANEUVER,
            UnitClass.RECON.value to LeaderType.ELITE_RECON_VETERAN,
            UnitClass.ANTI_TANK.value to LeaderType.TANK_KILLER,
            UnitClass.FLAK.value to LeaderType.FLAK_SUPPORT,
            UnitClass.FORTIFICATION.value to LeaderType.TENACIOUS_DEFENSE,
            UnitClass.ARTILLERY.value to LeaderType.MARKSMAN,
            UnitClass.AIR_DEFENCE.value to LeaderType.MECHANIZED_VETERAN,
            UnitClass.FIGHTER.value to LeaderType.SKILLED_INTERCEPTOR,
            UnitClass.TACTICAL_BOMBER.value to LeaderType.SKILLED_ASSAULT,
            UnitClass.LEVEL_BOMBER.value to LeaderType.SKILLED_ASSAULT,
            UnitClass.SUBMARINE.value to LeaderType.EXPERT_SEA_WOLF,
            UnitClass.DESTROYER.value to LeaderType.DEEP_SEA_HUNTER,
            UnitClass.BATTLESHIP.value to LeaderType.BLOCKADE_VESSEL,
            UnitClass.CARRIER.value to LeaderType.EXPERT_SEA_BASE,
            UnitClass.BATTLE_CRUISER.value to LeaderType.BLOCKADE_VESSEL,
            UnitClass.CRUISER.value to LeaderType.BLOCKADE_VESSEL,
            UnitClass.LIGHT_CRUISER.value to LeaderType.SCREENING_VESSEL,
        )

    /**
     * What each naval signature means in existing traits, per OG's table: Sea Wolf *"ignores ZOC"*
     * (Superior Maneuver; its +1 naval defense is read directly), Deep Sea Hunter *"ignores Evasion"*
     * (read directly, with its +1 naval attack), Sea Base *"harder to destroy (resilience)"*,
     * Blockade Vessel *"range +1 (marksman)"*, Screening Vessel *"cannot be surprised, +1 initiative
     * (Battlefield Intelligence)"*. Resolved in `HeroTraitResolver`, so every rule that asks for the
     * underlying trait sees it without knowing ships exist.
     */
    private val SIGNATURE_GRANTS: Map<LeaderType, LeaderType> =
        mapOf(
            LeaderType.EXPERT_SEA_WOLF to LeaderType.SUPERIOR_MANEUVER,
            LeaderType.EXPERT_SEA_BASE to LeaderType.RESILIENCE,
            LeaderType.BLOCKADE_VESSEL to LeaderType.MARKSMAN,
            LeaderType.SCREENING_VESSEL to LeaderType.BATTLEFIELD_INTELLIGENCE,
        )

    /** The existing trait a naval signature with id [held] stands for, or null. */
    fun signatureGrants(held: Int): LeaderType? = SIGNATURE_GRANTS.entries.firstOrNull { it.key.value == held }?.value

    /**
     * Classes that may be AUTHORED with a leader but never earn one in combat: bunkers and every
     * transport, the exact list OG's 24.12.10.0 changelog opened to design-time leaders only.
     */
    private val DESIGN_TIME_ONLY =
        setOf(
            UnitClass.FORTIFICATION.value,
            UnitClass.GROUND_TRANSPORT.value,
            UnitClass.AIR_TRANSPORT.value,
            UnitClass.NAVAL_TRANSPORT.value,
        )

    /** Classes that do not move under their own power, and so get no movement trait. */
    private val IMMOBILE = setOf(UnitClass.FORTIFICATION.value)

    /** OG's efile class numbering (0..23) onto OSADA's, for `noldr_classunits`, which names OG
     *  classes. The same table as `tools/og-import/csv_to_eqp.py` `OG2PM_CLASS`. */
    private val OG_CLASS_TO_OSADA =
        mapOf(
            1 to 1,
            2 to 2,
            3 to 3,
            4 to 4,
            5 to 5,
            6 to 6,
            7 to 7,
            8 to 7,
            9 to 8,
            10 to 9,
            11 to 10,
            12 to 11,
            13 to 12,
            14 to 13,
            15 to 11,
            16 to 14,
            17 to 15,
            18 to 16,
            19 to 17,
            20 to 18,
            21 to 19,
            22 to 20,
            23 to 21,
        )

    /** The class attribute OG derives for [uclass], or null when OG names none. */
    fun classTrait(uclass: Int): LeaderType? = CLASS_TRAIT[uclass]

    /** Whether [uclass] can earn a leader in combat (as opposed to only being authored with one). */
    fun earnsInCombat(uclass: Int): Boolean = uclass != UnitClass.NONE.value && uclass !in DESIGN_TIME_ONLY

    /**
     * OG's `noldr_classunits` — *"the list (delimited by commas) of unit's classes you want to
     * disable getting a leader"* — for the active efile. No shipped efile sets it.
     */
    fun classDisabledByEfile(uclass: Int): Boolean =
        EfileConfig.listKey("noldr_classunits").any { OG_CLASS_TO_OSADA[it] == uclass }

    /**
     * The random attributes a formation of [uclass] can draw: OG's list 9..34, less what does not
     * apply to the class, less the class's own attribute (which it already has), less anything the
     * active efile's `noldr_randtype` disables.
     */
    fun randomPool(uclass: Int): List<LeaderType> {
        if (uclass == UnitClass.NONE.value) return emptyList()
        val disabled = EfileConfig.listKey("noldr_randtype").toSet()
        val own = classTrait(uclass)
        val ownGrant = own?.let { signatureGrants(it.value) }
        return RANDOM_LIST
            .filter { (ogId, trait) ->
                ogId !in disabled && trait != own && trait != ownGrant && appliesTo(trait, uclass)
            }.map { it.second }
            .distinct()
    }

    /** Whether [trait]'s rule can ever fire for a formation of [uclass]. */
    fun appliesTo(
        trait: LeaderType,
        uclass: Int,
    ): Boolean {
        val land = uclass in UnitClass.INFANTRY.value..UnitClass.AIR_DEFENCE.value
        val air = uclass in UnitClass.FIGHTER.value..UnitClass.AIR_TRANSPORT.value
        return when {
            trait in LAND_ONLY && !land -> false
            trait in AIR_ONLY && !air -> false
            trait in SURFACE_ONLY && air -> false
            trait in MOVEMENT && uclass in IMMOBILE -> false
            // Artillery already supports its neighbours by class; Extended Front would be inert.
            trait == LeaderType.EXTENDED_FRONT && uclass == UnitClass.ARTILLERY.value -> false
            else -> true
        }
    }
}
