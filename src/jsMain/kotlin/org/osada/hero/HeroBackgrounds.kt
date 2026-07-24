package org.osada.hero

import org.osada.LeaderType
import org.osada.UnitClass

/**
 * Professional backgrounds (§8.2) — the explicit replacement for the hidden class-signature trait.
 *
 * ## What this fixes
 *
 * In the old system (`docs/leaders.md` §1) a unit that gained ANY leader silently also gained the
 * first trait in its unit-class list. Nothing in the UI attributed that second trait to anything;
 * it was not a reward for anything the unit had done, and `Leaders.unitHasLeader` granted it
 * purely as a side effect of `unit.leader != -1`.
 *
 * §24 of the design brief is explicit that this behaviour must not survive invisibly. It does not
 * disappear either — removing it outright would silently nerf every existing save on load. Instead
 * the same effect is re-attributed to a named, inspectable source: the officer's TRAINING. A tank
 * commander has Aggressive Tank Maneuver because they graduated the armored academy, and the UI
 * can say so.
 *
 * Mechanically identical, narratively explained, and now a normal piece of data that a later phase
 * can vary per hero instead of a hardcoded consequence of unit class.
 *
 * ## Coverage
 *
 * All twenty-one unit classes now have a background. The eight that could always receive a leader
 * keep the background that re-attributes their old hidden signature trait. §12 asks that the
 * arbitrary restriction on the other thirteen (flak, fortification, transports, level bombers and
 * the naval classes) be lifted; Phase 2 does that by giving each a background whose granted trait
 * is a *universal* effect — [LeaderType.AGGRESSIVE_ATTACK] or [LeaderType.DETERMINED_DEFENSE], both
 * of which apply to any unit — rather than a class-gated one those classes never had.
 *
 * The granted trait resolves through [HeroTraitResolver], which honours a hero's traits regardless
 * of the unit's class, so a destroyer or fortification commander fights with a real bonus. This is
 * the mechanical half of lifting the restriction; the acquisition half is in
 * [LeaderAcquisitionService], which no longer filters on class.
 */
object HeroBackgrounds {
    /**
     * One professional background: an id for saves, display text for the UI, and the legacy trait
     * it grants so the compatibility adapter can answer combat queries from it.
     */
    data class Background(
        val id: String,
        val title: String,
        val description: String,
        val grantedTrait: LeaderType,
    )

    private val byUnitClass: Map<Int, Background> =
        mapOf(
            UnitClass.INFANTRY.value to
                Background(
                    id = "infantry_school_instructor",
                    title = "Infantry School Instructor",
                    description = "Trained troops in prepared defense before taking a field command.",
                    grantedTrait = LeaderType.TENACIOUS_DEFENSE,
                ),
            UnitClass.TANK.value to
                Background(
                    id = "armored_academy_graduate",
                    title = "Armored Academy Graduate",
                    description = "Completed an accelerated armored officers' course.",
                    grantedTrait = LeaderType.AGGRESSIVE_TANK_MANEUVER,
                ),
            UnitClass.RECON.value to
                Background(
                    id = "veteran_reconnaissance_officer",
                    title = "Veteran Reconnaissance Officer",
                    description = "Served in long-range scouting before this command.",
                    grantedTrait = LeaderType.ELITE_RECON_VETERAN,
                ),
            UnitClass.ANTI_TANK.value to
                Background(
                    id = "antitank_gunnery_instructor",
                    title = "Anti-Tank Gunnery Instructor",
                    description = "Taught gun crews to engage armor from the halt and on the move.",
                    grantedTrait = LeaderType.TANK_KILLER,
                ),
            UnitClass.ARTILLERY.value to
                Background(
                    id = "regimental_artillery_officer",
                    title = "Regimental Artillery Officer",
                    description = "Career gunner, trained in observed indirect fire.",
                    grantedTrait = LeaderType.MARKSMAN,
                ),
            UnitClass.AIR_DEFENCE.value to
                Background(
                    id = "mechanized_air_defence_officer",
                    title = "Mechanized Air-Defence Officer",
                    description = "Specialist in keeping mobile batteries firing while displacing.",
                    grantedTrait = LeaderType.MECHANIZED_VETERAN,
                ),
            UnitClass.FIGHTER.value to
                Background(
                    id = "fighter_squadron_leader",
                    title = "Fighter Squadron Leader",
                    description = "Led an interceptor flight before receiving this squadron.",
                    grantedTrait = LeaderType.SKILLED_INTERCEPTOR,
                ),
            UnitClass.TACTICAL_BOMBER.value to
                Background(
                    id = "ground_attack_group_leader",
                    title = "Ground-Attack Group Leader",
                    description = "Flew close-support sorties under fighter threat.",
                    grantedTrait = LeaderType.SKILLED_ASSAULT,
                ),
            // §12: the thirteen classes that could never get a leader before. Each takes a
            // universal granted trait, not a class-gated one, because none had a historical
            // signature to convert — see the class doc.
            UnitClass.FLAK.value to
                Background(
                    id = "heavy_flak_battery_officer",
                    title = "Heavy Flak Battery Officer",
                    description = "Directed dug-in heavy anti-aircraft guns against air and ground targets.",
                    grantedTrait = LeaderType.DETERMINED_DEFENSE,
                ),
            UnitClass.FORTIFICATION.value to
                Background(
                    id = "garrison_engineer",
                    title = "Garrison Engineer",
                    description = "Built and held prepared strongpoints and fortified lines.",
                    grantedTrait = LeaderType.DETERMINED_DEFENSE,
                ),
            UnitClass.GROUND_TRANSPORT.value to
                Background(
                    id = "transport_column_officer",
                    title = "Transport Column Officer",
                    description = "Kept supply and troop columns moving under threat.",
                    grantedTrait = LeaderType.DETERMINED_DEFENSE,
                ),
            UnitClass.LEVEL_BOMBER.value to
                Background(
                    id = "bomber_group_navigator",
                    title = "Bomber Group Navigator",
                    description = "Led level-bombing formations to distant targets.",
                    grantedTrait = LeaderType.AGGRESSIVE_ATTACK,
                ),
            UnitClass.AIR_TRANSPORT.value to
                Background(
                    id = "air_transport_squadron_leader",
                    title = "Air Transport Squadron Leader",
                    description = "Flew supply and paradrop sorties into contested airspace.",
                    grantedTrait = LeaderType.DETERMINED_DEFENSE,
                ),
            UnitClass.SUBMARINE.value to
                Background(
                    id = "submarine_commander",
                    title = "Submarine Commander",
                    description = "Hunted shipping on independent war patrols.",
                    grantedTrait = LeaderType.AGGRESSIVE_ATTACK,
                ),
            UnitClass.DESTROYER.value to
                Background(
                    id = "destroyer_captain",
                    title = "Destroyer Captain",
                    description = "Screened the fleet and pressed torpedo attacks.",
                    grantedTrait = LeaderType.AGGRESSIVE_ATTACK,
                ),
            UnitClass.BATTLESHIP.value to
                Background(
                    id = "battleship_gunnery_officer",
                    title = "Battleship Gunnery Officer",
                    description = "Career naval gunner trained in main-battery fire.",
                    grantedTrait = LeaderType.AGGRESSIVE_ATTACK,
                ),
            UnitClass.CARRIER.value to
                Background(
                    id = "carrier_air_group_commander",
                    title = "Carrier Air Group Commander",
                    description = "Coordinated a carrier's strike and defensive air operations.",
                    grantedTrait = LeaderType.AGGRESSIVE_ATTACK,
                ),
            UnitClass.NAVAL_TRANSPORT.value to
                Background(
                    id = "naval_transport_master",
                    title = "Naval Transport Master",
                    description = "Ran troops and materiel across contested sea lanes.",
                    grantedTrait = LeaderType.DETERMINED_DEFENSE,
                ),
            UnitClass.BATTLE_CRUISER.value to
                Background(
                    id = "battle_cruiser_officer",
                    title = "Battle Cruiser Officer",
                    description = "Fought fast capital ships in the van of the fleet.",
                    grantedTrait = LeaderType.AGGRESSIVE_ATTACK,
                ),
            UnitClass.CRUISER.value to
                Background(
                    id = "cruiser_captain",
                    title = "Cruiser Captain",
                    description = "Commanded an independent cruiser on patrol and escort.",
                    grantedTrait = LeaderType.AGGRESSIVE_ATTACK,
                ),
            UnitClass.LIGHT_CRUISER.value to
                Background(
                    id = "light_cruiser_captain",
                    title = "Light Cruiser Captain",
                    description = "Led light forces in screening and shore-bombardment work.",
                    grantedTrait = LeaderType.AGGRESSIVE_ATTACK,
                ),
        )

    private val byId: Map<String, Background> = byUnitClass.values.associateBy { it.id }

    /** The background an officer of [unitClass] receives, or null when the class has none. */
    fun forUnitClass(unitClass: Int): Background? = byUnitClass[unitClass]

    /** Look up a background by its saved id; null when the save names one this build removed. */
    fun byId(id: String): Background? = byId[id]

    /** The legacy trait [backgroundId] grants, for the compatibility adapter. */
    fun grantedTrait(backgroundId: String): LeaderType? = byId[backgroundId]?.grantedTrait
}
