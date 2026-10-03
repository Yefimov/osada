package org.osada.multiplayer.coop

/** What one commander (or the unassigned / allied-AI bucket) did. Plain integers, so it serializes exactly. */
class CoopCounters {
    var points: Int = 0
    var objectives: Int = 0
    var destroyed: Int = 0
    var captured: Int = 0
    var damage: Int = 0
    var reveals: Int = 0
    var support: Int = 0
    var endurance: Int = 0
    var forcedRetreats: Int = 0
    var attacksStopped: Int = 0
    var strengthLost: Int = 0
    var strengthCaptured: Int = 0
    var unitsLost: Int = 0
    var terminalAssists: Int = 0

    fun copy(): CoopCounters =
        CoopCounters().also {
            it.points = points
            it.objectives = objectives
            it.destroyed = destroyed
            it.captured = captured
            it.damage = damage
            it.reveals = reveals
            it.support = support
            it.endurance = endurance
            it.forcedRetreats = forcedRetreats
            it.attacksStopped = attacksStopped
            it.strengthLost = strengthLost
            it.strengthCaptured = strengthCaptured
            it.unitsLost = unitsLost
            it.terminalAssists = terminalAssists
        }
}

/** One line of compact evidence behind a number (§8.4), identified by `(revision, eventIndex)`. */
data class CoopEvidence(
    val revision: Long,
    val eventIndex: Int,
    val commanderId: String,
    val kind: String,
    val points: Int,
    val detail: String,
)

/**
 * The team-first contribution ledger (§8). Experimental weights, versioned by [SCORING_VERSION].
 *
 * Every mutator here is idempotent where the doc demands it -- one objective award per objective per
 * mission, one terminal outcome per enemy unit, one scoring reveal per enemy, capped endurance -- so
 * a retried message, a duplicate snapshot or a hide-and-reveal cycle cannot farm points. Personal
 * points never become prestige, XP, ability slots or campaign rewards.
 *
 * ## Reconciliation
 *
 * [teamDestroyed] / [teamCaptured] count each enemy terminal outcome ONCE, and the 40-point pool
 * for each is allocated in full ([allocateTerminal] always sums to [TERMINAL_POOL]), so
 * `sum(points from terminals) == TERMINAL_POOL * (teamDestroyed + teamCaptured)` across the
 * commanders and the [UNASSIGNED] bucket. Assist counts overlap and are labelled as such.
 */
@Suppress("TooManyFunctions") // one small recorder per scored event; splitting them hides the whole rulebook
class CoopLedger {
    val perCommander: MutableMap<String, CoopCounters> = linkedMapOf()
    val scoredObjectives: MutableSet<String> = linkedSetOf()
    val revealedEnemies: MutableSet<Int> = linkedSetOf()
    val terminalEnemies: MutableSet<Int> = linkedSetOf()

    /** Damage dealt to each enemy unit inside the current contribution window (a turn), by commander. */
    val windowDamage: MutableMap<Int, MutableMap<String, Int>> = linkedMapOf()
    val enduranceThisWindow: MutableMap<Int, Int> = linkedMapOf()
    val evidence: MutableList<CoopEvidence> = mutableListOf()

    var windowKey: String = ""
    var teamDestroyed: Int = 0
    var teamCaptured: Int = 0
    var teamOwnLosses: Int = 0
    var scoringVersion: Int = SCORING_VERSION

    private var revision: Long = 0
    private var eventIndex: Int = 0

    fun counters(commanderId: String?): CoopCounters =
        perCommander.getOrPut(commanderId ?: UNASSIGNED) { CoopCounters() }

    /** Stamps events with the revision being committed; the index restarts per revision. */
    fun beginRevision(revision: Long) {
        if (revision != this.revision) {
            this.revision = revision
            eventIndex = 0
        }
    }

    /**
     * Opens a new contribution window when the turn changes. Damage tallies that only matter for
     * "earlier in the same turn" and the per-turn endurance caps are dropped; everything that
     * guards against farming (objectives, reveals, terminal units) lives for the whole mission.
     */
    fun rollWindow(key: String) {
        if (key == windowKey) return
        windowKey = key
        windowDamage.clear()
        enduranceThisWindow.clear()
    }

    fun recordDamage(
        enemyUnitId: Int,
        commanderId: String?,
        amount: Int,
        tally: Boolean = true,
    ) {
        if (amount <= 0) return
        counters(commanderId).damage += amount
        if (tally && commanderId != null) {
            val tally = windowDamage.getOrPut(enemyUnitId) { linkedMapOf() }
            tally[commanderId] = (tally[commanderId] ?: 0) + amount
        }
    }

    fun recordSupport(commanderId: String?) {
        counters(commanderId).support++
    }

    fun recordLoss(
        commanderId: String?,
        strength: Int,
        unitDestroyed: Boolean,
        capturedStrength: Int = 0,
    ) {
        if (strength <= 0 && capturedStrength <= 0 && !unitDestroyed) return
        val c = counters(commanderId)
        c.strengthLost += strength.coerceAtLeast(0) + capturedStrength.coerceAtLeast(0)
        c.strengthCaptured += capturedStrength.coerceAtLeast(0)
        if (unitDestroyed) {
            c.unitsLost++
            teamOwnLosses++
        }
    }

    /** First capture of an authored objective this mission; a recapture earns nothing. */
    fun recordObjective(
        key: String,
        commanderId: String?,
    ): Boolean {
        if (!scoredObjectives.add(key)) return false
        val c = counters(commanderId)
        c.objectives++
        award(commanderId, c, OBJECTIVE_POINTS, "objective", key)
        return true
    }

    /** First attributable reveal of an enemy unit; hiding and revealing again earns nothing. */
    fun recordReveal(
        enemyUnitId: Int,
        commanderId: String?,
    ): Boolean {
        if (!revealedEnemies.add(enemyUnitId)) return false
        val c = counters(commanderId)
        c.reveals++
        award(commanderId, c, REVEAL_POINTS, "reveal", enemyUnitId.toString())
        return true
    }

    /** A survived, resolved enemy attack that did not push the defender off its hex. */
    fun recordEndurance(
        defenderUnitId: Int,
        commanderId: String?,
    ) {
        val c = counters(commanderId)
        c.endurance++
        val earned = enduranceThisWindow[defenderUnitId] ?: 0
        val grant = ENDURANCE_POINTS.coerceAtMost(ENDURANCE_CAP_PER_UNIT - earned).coerceAtLeast(0)
        if (grant > 0) {
            enduranceThisWindow[defenderUnitId] = earned + grant
            award(commanderId, c, grant, "endurance", defenderUnitId.toString())
        }
    }

    fun recordForcedRetreat(commanderId: String?) {
        counters(commanderId).forcedRetreats++
    }

    fun recordAttackStopped(commanderId: String?) {
        counters(commanderId).attacksStopped++
    }

    /**
     * A real terminal outcome for an ENEMY unit: destroyed OR captured, never both and never twice.
     * Returns the allocation (empty when this unit already ended).
     */
    fun recordTerminal(
        enemyUnitId: Int,
        finisherId: String?,
        captured: Boolean,
    ): Map<String, Int> {
        if (!terminalEnemies.add(enemyUnitId)) return emptyMap()
        if (captured) teamCaptured++ else teamDestroyed++
        val finisherCounters = counters(finisherId)
        if (captured) finisherCounters.captured++ else finisherCounters.destroyed++
        val contributors = windowDamage[enemyUnitId].orEmpty()
        val allocation = allocateTerminal(finisherId, contributors)
        allocation.forEach { (id, points) ->
            val target = if (id == UNASSIGNED) null else id
            val c = counters(target)
            if (target != finisherId && points > 0 && id != UNASSIGNED) c.terminalAssists++
            award(target, c, points, if (captured) "capture" else "destroy", enemyUnitId.toString())
        }
        windowDamage.remove(enemyUnitId)
        return allocation
    }

    private fun award(
        commanderId: String?,
        counters: CoopCounters,
        points: Int,
        kind: String,
        detail: String,
    ) {
        if (points <= 0) return
        counters.points += points
        evidence += CoopEvidence(revision, eventIndex++, commanderId ?: UNASSIGNED, kind, points, detail)
        if (evidence.size > MAX_EVIDENCE) evidence.removeAt(0)
    }

    fun totalPoints(): Int = perCommander.values.sumOf { it.points }

    companion object {
        const val SCORING_VERSION = 1
        const val UNASSIGNED = "_"

        const val OBJECTIVE_POINTS = 100
        const val TERMINAL_POOL = 40
        const val FINISHER_SHARE = 20
        const val REVEAL_POINTS = 5
        const val ENDURANCE_POINTS = 5
        const val ENDURANCE_CAP_PER_UNIT = 15
        const val MAX_EVIDENCE = 200

        /**
         * The 40-point terminal pool (§8.3). 20 to the finisher; 20 split among whoever dealt positive
         * actual damage to this enemy earlier in the same window, in proportion to that damage. With
         * no such contributor the finisher takes all 40. Fixed-point with deterministic remainder
         * handling (largest remainder, ties by id), so the shares ALWAYS sum to exactly [TERMINAL_POOL].
         * A null finisher (an unassigned unit made the kill) keeps its half in [UNASSIGNED] --
         * no player actor is ever invented for it.
         */
        fun allocateTerminal(
            finisherId: String?,
            contributors: Map<String, Int>,
        ): Map<String, Int> {
            val finisherKey = finisherId ?: UNASSIGNED
            val positive = contributors.filterValues { it > 0 }
            val total = positive.values.sum()
            if (total <= 0) return mapOf(finisherKey to TERMINAL_POOL)
            val shared = TERMINAL_POOL - FINISHER_SHARE
            val result = linkedMapOf(finisherKey to FINISHER_SHARE)
            val floors = positive.mapValues { (shared * it.value) / total }
            var remainder = shared - floors.values.sum()
            positive.keys
                .sortedWith(
                    compareByDescending<String> { (shared * positive.getValue(it)) % total }.thenBy { it },
                ).forEach { id ->
                    val extra = if (remainder > 0) 1 else 0
                    remainder -= extra
                    result[id] = (result[id] ?: 0) + floors.getValue(id) + extra
                }
            return result
        }
    }
}
