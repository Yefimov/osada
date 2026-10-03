package org.osada.multiplayer.coop

import org.osada.LeaderType
import org.osada.model.OgLeaderPools

/**
 * How much scrutiny a cooperative ability still needs (`docs/design/cooperative-commanders-multiplayer.md` §5.1).
 *
 * A [RESTRICTED] ability multiplies action economy or reaches many units at once, so the first
 * balance pass lets a commander carry at most [CoopLoadout.MAX_RESTRICTED] of them. Its presence in
 * the single-player roster is not evidence that four freely chosen effects are balanced across half
 * an army.
 */
enum class CoopTier { STANDARD, RESTRICTED }

/** A browsing suggestion only (§5.2): never a class, never required, never a fixed pair. */
enum class CoopProfile { BREAKTHROUGH, ASSAULT_AND_HOLD, FIRE_SUPPORT, MANOEUVRE_AND_AIR }

/**
 * One cooperative ability: a stable id over an EXISTING rule effect.
 *
 * Several legendary characters use the same [LeaderType]; the catalogue is deduplicated by effect so
 * a commander can never stack two copies, and a slot is spent on an ability rather than on a whole
 * legendary character with hidden extra traits. The rule itself is still the one `Leaders` /
 * `AttackCalculation` / `MovementRules` execute -- nothing here invents a numeric bonus.
 */
data class CoopAbility(
    val id: String,
    val leader: LeaderType,
    val tier: CoopTier,
    val suggestedFor: Set<CoopProfile>,
)

/**
 * The versioned catalogue of positive abilities a cooperative commander may choose.
 *
 * Deliberately NOT in the catalogue:
 *  * `LIBERATOR` and `INFLUENCE` -- economy effects. All commanders share one prestige account, so
 *    either would be a per-player income stream on a shared treasury; they need a design of their own.
 *  * `EXTENDED_FRONT` / `FLAK_SUPPORT` -- not part of the legendary roster the owner asked to reuse.
 *  * Anything negative. The owner's 2026-09-30 decision excludes negative cards from this delivery,
 *    so an id that is not listed here is simply unknown and is refused by [CoopLoadout.validate].
 *
 * Bump [VERSION] when an entry is added, removed or changes tier: a snapshot that disagrees is
 * refused rather than silently dropping a commander's bonus (§10).
 */
object CoopAbilityCatalogue {
    const val VERSION = 1

    private const val ID_PREFIX = "coop."

    private val ASSAULT = CoopProfile.ASSAULT_AND_HOLD
    private val BREAK = CoopProfile.BREAKTHROUGH
    private val FIRE = CoopProfile.FIRE_SUPPORT
    private val MOVE = CoopProfile.MANOEUVRE_AND_AIR

    val ALL: List<CoopAbility> =
        listOf(
            entry(LeaderType.SUPERIOR_MANEUVER, CoopTier.STANDARD, BREAK, MOVE),
            entry(LeaderType.RESILIENCE, CoopTier.STANDARD, BREAK, ASSAULT),
            entry(LeaderType.MECHANIZED_VETERAN, CoopTier.STANDARD, BREAK),
            entry(LeaderType.AGGRESSIVE_MANEUVER, CoopTier.STANDARD, BREAK, MOVE),
            entry(LeaderType.OVERWHELMING_ATTACK, CoopTier.RESTRICTED, BREAK),
            entry(LeaderType.FIRST_STRIKE, CoopTier.RESTRICTED, BREAK),
            entry(LeaderType.STREET_FIGHTER, CoopTier.STANDARD, ASSAULT),
            entry(LeaderType.DETERMINED_DEFENSE, CoopTier.STANDARD, ASSAULT),
            entry(LeaderType.TENACIOUS_DEFENSE, CoopTier.STANDARD, ASSAULT),
            entry(LeaderType.FEROCIOUS_DEFENSE, CoopTier.STANDARD, ASSAULT),
            entry(LeaderType.INFILTRATION_TACTICS, CoopTier.STANDARD, ASSAULT),
            entry(LeaderType.FOREST_CAMOUFLAGE, CoopTier.STANDARD, ASSAULT),
            entry(LeaderType.ALPINE_TRAINING, CoopTier.STANDARD, ASSAULT, MOVE),
            entry(LeaderType.DEVASTATING_FIRE, CoopTier.STANDARD, FIRE),
            entry(LeaderType.MARKSMAN, CoopTier.STANDARD, FIRE),
            entry(LeaderType.FIRE_DISCIPLINE, CoopTier.STANDARD, FIRE),
            entry(LeaderType.OVERWATCH, CoopTier.STANDARD, FIRE, ASSAULT),
            entry(LeaderType.SHOCK_TACTICS, CoopTier.STANDARD, FIRE),
            entry(LeaderType.SKILLED_RECONNAISSANCE, CoopTier.STANDARD, FIRE, MOVE),
            entry(LeaderType.BATTLEFIELD_INTELLIGENCE, CoopTier.STANDARD, FIRE, MOVE),
            entry(LeaderType.COMBAT_SUPPORT, CoopTier.RESTRICTED, FIRE),
            entry(LeaderType.RECON_MOVEMENT, CoopTier.RESTRICTED, MOVE),
            entry(LeaderType.SKILLED_GROUND_ATTACK, CoopTier.STANDARD, MOVE),
        )

    private val byId: Map<String, CoopAbility> = ALL.associateBy { it.id }

    init {
        check(byId.size == ALL.size) { "Cooperative ability ids must be unique" }
        check(ALL.map { it.leader }.toSet().size == ALL.size) { "One cooperative ability per rule effect" }
    }

    fun byId(id: String): CoopAbility? = byId[id]

    fun idFor(leader: LeaderType): String = ID_PREFIX + leader.name.lowercase()

    /** The rule effect an id grants, or null for an id this catalogue version does not know. */
    fun leaderFor(id: String): LeaderType? = byId[id]?.leader

    /**
     * Whether [ability]'s rule can ever fire for a formation of [unitClass]. This is the same
     * predicate that keeps the legacy pools honest, so the unit card never promises a bonus that no
     * rule keeps (e.g. Overwatch on an aircraft).
     */
    fun appliesTo(
        ability: CoopAbility,
        unitClass: Int,
    ): Boolean = OgLeaderPools.appliesTo(ability.leader, unitClass)

    private fun entry(
        leader: LeaderType,
        tier: CoopTier,
        vararg profiles: CoopProfile,
    ): CoopAbility = CoopAbility(idFor(leader), leader, tier, profiles.toSet())
}
