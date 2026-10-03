package org.osada.multiplayer.coop

import org.osada.LeaderType
import org.osada.model.AttackObservers
import org.osada.model.AttackReport
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.OgLeaderPools
import org.osada.model.getPlayer

/**
 * The one place the rest of the game asks "is this match cooperative, and who commands this unit?".
 *
 * ## Exactly one effect path (§5.3)
 *
 * [hasTrait] is consulted FIRST by `HeroTraitResolver`. For a unit of the shared human player it
 * returns a definite answer -- the assigned commander's chosen abilities, or nothing for an
 * unassigned unit -- and the campaign-hero and legacy-leader paths are never reached. For every
 * other unit it returns null and those paths run exactly as before, so enemy and allied-AI leaders,
 * single-player heroes and the other multiplayer modes are untouched.
 *
 * ## The prospective commander
 *
 * The commander's abilities apply to the FIRST action that claims a unit (§6.1) -- including its
 * movement legality -- so before that action commits the unit has no owner yet. [acting] names the
 * issuing commander and the unit for the duration of the validation and application only; it is a
 * lens, not state, and is restored by `finally` however the command ends.
 *
 * The state lives here because the combat rules call through static objects and have no game
 * reference to thread a policy down; it is replaced wholesale from every snapshot.
 */
@Suppress("ReturnCount")
object CoopRuntime {
    var state: CoopState? = null
        private set

    private var actingCommanderId: String? = null
    private var actingUnitId: Int? = null

    val active: Boolean
        get() = state != null

    fun install(coop: CoopState) {
        state = coop
        AttackObservers.onResolved = ::onAttackResolved
    }

    fun clear() {
        state = null
        actingCommanderId = null
        actingUnitId = null
        AttackObservers.onResolved = null
    }

    /** Whether [unit] belongs to the shared human army whose leader layer this mode replaces. */
    fun governs(unit: GameUnit?): Boolean {
        val coop = state ?: return false
        return unit != null && unit.owner == coop.humanPlayerId
    }

    /** The commander whose bonuses apply to [unit] right now, if any. */
    fun commanderIdFor(unit: GameUnit?): String? {
        val coop = state ?: return null
        if (unit == null || !governs(unit)) return null
        return coop.ownerOf(unit.id) ?: actingCommanderId.takeIf { unit.id == actingUnitId }
    }

    /**
     * Null when this mode does not govern [unit] (the existing hero/legacy path then answers);
     * otherwise true only when its commander chose [leader] AND the effect can ever fire for the
     * unit's class -- the same applicability predicate the legacy pools use.
     */
    fun hasTrait(
        unit: GameUnit?,
        leader: LeaderType,
    ): Boolean? {
        val coop = state ?: return null
        if (unit == null || !governs(unit)) return null
        val commander = coop.commanderById(commanderIdFor(unit)) ?: return false
        return leader in commander.leaderEffects && OgLeaderPools.appliesTo(leader, unit.unitData().uclass)
    }

    /** Inspection borrows one prospective issuer without assigning the unit or its supporters. */
    fun <T> preview(
        participantId: String,
        unit: GameUnit,
        block: () -> T,
    ): T = acting(state?.commanderOfParticipant(participantId)?.id, unit.id, block)

    fun <T> acting(
        commanderId: String?,
        unitId: Int?,
        block: () -> T,
    ): T {
        val previousCommander = actingCommanderId
        val previousUnit = actingUnitId
        beginActing(commanderId, unitId)
        try {
            return block()
        } finally {
            beginActing(previousCommander, previousUnit)
        }
    }

    @PublishedApi
    internal fun beginActing(
        commanderId: String?,
        unitId: Int?,
    ) {
        actingCommanderId = commanderId
        actingUnitId = unitId
    }

    // -- Attack accounting ----------------------------------------------------------------------

    private fun onAttackResolved(
        map: GameMap,
        report: AttackReport,
    ) {
        val coop = state ?: return
        val humanSide = map.getPlayer(coop.humanPlayerId).side
        CoopAttackAccounting.record(coop.ledger, factsOf(humanSide, report))
    }

    private fun factsOf(
        humanSide: Int,
        report: AttackReport,
    ): CoopAttackFacts {
        val outcome = report.outcome
        val attacker = report.attacker
        val defender = report.defender
        val captured = outcome.surrender != null
        return CoopAttackFacts(
            attackerId = attacker.id,
            defenderId = defender.id,
            attackerFriendly = attacker.player?.side == humanSide,
            defenderFriendly = defender.player?.side == humanSide,
            attackerCommanderId = commanderIdFor(attacker),
            defenderCommanderId = commanderIdFor(defender),
            attackerLost = (report.attackerStrengthBefore - attacker.strength).coerceAtLeast(0),
            attackerDestroyed = attacker.destroyed,
            defenderLost = outcome.result.kills.coerceIn(0, report.defenderStrengthBefore),
            defenderDestroyed = defender.destroyed && !captured,
            defenderCaptured = captured,
            defenderCapturedStrength =
                if (captured) {
                    (report.defenderStrengthBefore - outcome.result.kills).coerceIn(0, report.defenderStrengthBefore)
                } else {
                    0
                },
            defenderRetreated = outcome.retreat != null,
            attackStopped = outcome.attackStopped,
            supportShots =
                outcome.supportShots.map { shot ->
                    CoopSupportFact(
                        supporterFriendly = shot.supporter.player?.side == humanSide,
                        supporterCommanderId = commanderIdFor(shot.supporter),
                        damage = shot.result.kills.coerceAtLeast(0),
                    )
                },
        )
    }
}
