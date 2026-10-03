package org.osada.multiplayer.coop

/**
 * A voluntary change of commander for one unit (§6.2). BOTH sides must agree: the initiator's
 * consent is implied by asking, the other party's arrives with [accepted]. An accepted transfer is
 * only applied at the next friendly-turn boundary, so a movement bonus lent mid-turn cannot be
 * paired with an attack bonus supplied by someone else on the same unit.
 */
data class CoopTransfer(
    val unitId: Int,
    val fromId: String,
    val toId: String,
    val initiatorId: String,
    val accepted: Boolean = false,
) {
    /** The party whose answer is still missing. */
    val awaitingId: String
        get() = if (initiatorId == fromId) toId else fromId
}

enum class CoopRefusal {
    UNKNOWN_COMMANDER,
    UNIT_UNASSIGNED,
    NOT_THE_OWNER,
    SAME_COMMANDER,
    ALREADY_PENDING,
    NO_PENDING_TRANSFER,
    NOT_THE_ADDRESSEE,
    UNIT_ALREADY_CLAIMED,
    LOADOUT_LOCKED,
    LOADOUT_INVALID,
    NOT_PRE_BATTLE,
    PURCHASE_NOT_AVAILABLE,
    PRICE_CHANGED,
    PROPOSAL_LIMIT,
    PROPOSAL_NOT_FOUND,
}

/** What one finished mission left in the record: the team totals and each commander's counters. */
class CoopMissionSummary(
    val index: Int,
    val file: String,
    val teamDestroyed: Int,
    val teamCaptured: Int,
    val teamOwnLosses: Int,
    val perCommander: Map<String, CoopCounters>,
)

/**
 * Everything the cooperative mode adds to a running match: who the commanders are, which unit
 * belongs to whom, who is done with the turn, pending transfers and the contribution ledger.
 *
 * Pure data and rules -- no DOM, no game model -- so the load-bearing guarantees are unit-testable:
 * first claim wins, a transfer needs two yeses, readiness is cleared by real orders, and the ledger
 * cannot be farmed. The authority owns the only mutable copy; peers receive it inside every
 * snapshot (`MultiplayerRuntimeState.coopJson`) and are never asked to merge it.
 */
@Suppress("TooManyFunctions") // assignment, transfers and readiness are one small state machine
class CoopState(
    commanders: List<CoopCommander>,
    /** The one `Player` all commanders share. Every other player keeps its authored AI role. */
    val humanPlayerId: Int,
    /** Common campaign unlocks earned so far (0 in a standalone battle). */
    var unlocksEarned: Int = 0,
) {
    private val roster: MutableList<CoopCommander> = commanders.toMutableList()

    /** Commanders in stable seat order. A loadout edit replaces an entry; the seat and id never change. */
    val commanders: List<CoopCommander>
        get() = roster

    /** Former commanders retain their names and past credit, never a live connection or unit rights. */
    val retiredCommanders: MutableList<CoopCommander> = mutableListOf()

    val assignments: MutableMap<Int, String> = linkedMapOf()
    val transfers: MutableList<CoopTransfer> = mutableListOf()
    val done: MutableSet<String> = linkedSetOf()
    val excused: MutableSet<String> = linkedSetOf()
    var briefing: CoopBriefing? = null
    var economy: CoopEconomy = CoopEconomy()

    /** The CURRENT mission's ledger; an earlier mission's lives on in [summaries]. */
    var ledger: CoopLedger = CoopLedger()
        private set

    /** Zero-based count of missions begun in this run, and the scenario file of the current one. */
    var missionIndex: Int = 0
    var missionFile: String = ""

    /** Accepted gameplay orders and Done marks so far in this mission; while it is zero the mission has not begun. */
    var ordersThisMission: Int = 0

    /** Scenario count of the campaign being played; 0 for a standalone battle, which has no unlocks. */
    var campaignTotal: Int = 0
    val completedMissions: MutableSet<String> = linkedSetOf()
    val summaries: MutableList<CoopMissionSummary> = mutableListOf()

    /** Formation id -> commander, remembered so the next mission can OFFER last mission's allocation back. */
    val formationSuggestions: MutableMap<String, String> = linkedMapOf()

    /** Identity of the turn the [done] set belongs to; a change of turn clears it. */
    var turnKey: String = ""

    fun commanderById(id: String?): CoopCommander? = commanders.firstOrNull { it.id == id }

    fun historicalCommanderById(id: String?): CoopCommander? =
        commanderById(id) ?: retiredCommanders.firstOrNull { it.id == id }

    /** A new identity at an intermission; the successor chooses their own abilities and claims. */
    @Suppress("ReturnCount")
    fun replaceAtIntermission(
        id: String,
        replacement: CoopCommander,
    ): Boolean {
        val old = commanderById(id) ?: return false
        val eligible = campaignTotal > 0 && missionIndex > 0 && preBattle() && retiredCommanders.size < MAX_RETIRED
        val validIdentity = replacement.seat == old.seat && historicalCommanderById(replacement.id) == null
        if (!eligible || !validIdentity || CoopLoadout.validate(replacement.abilityIds, slotCap()).isNotEmpty()) {
            return false
        }
        retiredCommanders += old.copy(participantId = null)
        roster[roster.indexOf(old)] = replacement
        assignments.entries.removeAll { it.value == old.id }
        formationSuggestions.entries.removeAll { it.value == old.id }
        transfers.removeAll { it.fromId == old.id || it.toId == old.id }
        economy.proposals.removeAll { it.commanderId == old.id }
        done.clear()
        excused -= old.id
        briefing?.let { shared ->
            shared.pending()?.let { shared.votes[it.id]?.remove(old.id) }
            shared.ready.clear()
            shared.open = true
        }
        return true
    }

    fun commanderOfParticipant(participantId: String?): CoopCommander? =
        participantId?.let { pid -> commanders.firstOrNull { it.participantId == pid } }

    fun ownerOf(unitId: Int): String? = assignments[unitId]

    fun slotCap(): Int = CoopLoadout.slotCap(unlocksEarned)

    /**
     * True until the mission's first accepted order: the window in which abilities may be changed and
     * the assignment board used. Abilities lock when the mission starts and never change mid-battle.
     */
    fun preBattle(): Boolean = ordersThisMission == 0

    // -- Loadouts ------------------------------------------------------------------------------

    /** Replaces [commanderId]'s abilities if the window is open and the choice is legal for [slotCap]. */
    fun setLoadout(
        commanderId: String,
        abilityIds: List<String>,
    ): CoopRefusal? {
        val index = roster.indexOfFirst { it.id == commanderId }
        val refusal =
            when {
                index < 0 -> CoopRefusal.UNKNOWN_COMMANDER
                !preBattle() -> CoopRefusal.LOADOUT_LOCKED
                CoopLoadout.validate(abilityIds, slotCap()).isNotEmpty() -> CoopRefusal.LOADOUT_INVALID
                else -> null
            }
        if (refusal == null) roster[index] = roster[index].copy(abilityIds = abilityIds)
        return refusal
    }

    // -- Assignment ----------------------------------------------------------------------------

    /** First committed action wins; an already-assigned unit is never re-claimed by another. */
    fun claim(
        unitId: Int,
        commanderId: String,
    ): Boolean {
        if (commanderById(commanderId) == null) return false
        return assignments.getOrPut(unitId) { commanderId } == commanderId
    }

    /** Gives an owned unit back to the pool (pre-battle board only; the caller checks the window). */
    fun release(
        unitId: Int,
        commanderId: String,
    ): Boolean {
        if (assignments[unitId] != commanderId) return false
        assignments.remove(unitId)
        return true
    }

    /** Remembers that [formationId]'s core formation is [commanderId]'s, for the next mission's suggestion. */
    fun rememberFormation(
        formationId: String,
        commanderId: String,
    ) {
        if (formationId.isNotBlank()) formationSuggestions[formationId] = commanderId
    }

    fun unitsOf(commanderId: String): List<Int> = assignments.filterValues { it == commanderId }.keys.sorted()

    /** Drops bookkeeping for units that no longer exist (destroyed, captured, disbanded). */
    fun forget(unitId: Int) {
        assignments.remove(unitId)
        transfers.removeAll { it.unitId == unitId }
    }

    // -- Transfers -----------------------------------------------------------------------------

    /**
     * [byId] asks to move [unitId]. The owner offers it to [toId]; anyone else asks for it (and then
     * is, necessarily, the recipient). Returns null on success or the reason it was refused.
     */
    @Suppress("ReturnCount")
    fun requestTransfer(
        unitId: Int,
        byId: String,
        toId: String?,
    ): CoopRefusal? {
        if (commanderById(byId) == null) return CoopRefusal.UNKNOWN_COMMANDER
        val owner = assignments[unitId] ?: return CoopRefusal.UNIT_UNASSIGNED
        val recipient = if (byId == owner) toId else byId
        if (recipient == null || commanderById(recipient) == null) return CoopRefusal.UNKNOWN_COMMANDER
        if (recipient == owner) return CoopRefusal.SAME_COMMANDER
        if (transfers.any { it.unitId == unitId }) return CoopRefusal.ALREADY_PENDING
        transfers += CoopTransfer(unitId, fromId = owner, toId = recipient, initiatorId = byId)
        return null
    }

    /**
     * The addressee answers. Accepting marks the transfer to take effect at the next boundary;
     * declining (by either party -- the initiator declining is a withdrawal) removes it.
     */
    @Suppress("ReturnCount")
    fun respondTransfer(
        unitId: Int,
        byId: String,
        accept: Boolean,
    ): CoopRefusal? {
        val index = transfers.indexOfFirst { it.unitId == unitId }
        if (index < 0) return CoopRefusal.NO_PENDING_TRANSFER
        val pending = transfers[index]
        val involved = byId == pending.fromId || byId == pending.toId
        if (!involved) return CoopRefusal.NOT_THE_ADDRESSEE
        if (!accept) {
            transfers.removeAt(index)
            return null
        }
        if (byId != pending.awaitingId) return CoopRefusal.NOT_THE_ADDRESSEE
        transfers[index] = pending.copy(accepted = true)
        return null
    }

    /**
     * The friendly-turn boundary: every accepted transfer whose unit is still with the commander who
     * agreed to give it up takes effect. Nothing here restores movement, attacks or supply, and past
     * ledger credit stays with whoever acted. Returns the units that changed hands.
     */
    fun applyAcceptedTransfers(): List<Int> {
        val moved = mutableListOf<Int>()
        val iterator = transfers.iterator()
        while (iterator.hasNext()) {
            val transfer = iterator.next()
            if (!transfer.accepted) continue
            iterator.remove()
            if (assignments[transfer.unitId] == transfer.fromId && commanderById(transfer.toId) != null) {
                assignments[transfer.unitId] = transfer.toId
                moved += transfer.unitId
            }
        }
        return moved
    }

    /**
     * Emergency delegation for an absent seat (§10): treated like a transfer for bonus timing -- the
     * old abilities stay in force through the current turn cycle and the substitute takes over at the
     * next boundary. Returns how many units were queued.
     */
    fun delegateSeat(
        fromId: String,
        toId: String,
    ): Int {
        if (commanderById(fromId) == null || commanderById(toId) == null || fromId == toId) return 0
        var queued = 0
        unitsOf(fromId).forEach { unitId ->
            transfers.removeAll { it.unitId == unitId }
            transfers += CoopTransfer(unitId, fromId, toId, initiatorId = toId, accepted = true)
            queued++
        }
        return queued
    }

    // -- Turn readiness ------------------------------------------------------------------------

    /** Commanders whose "done" the army is waiting for: everyone not excused by the host. */
    fun requiredCommanderIds(): List<String> = commanders.map { it.id }.filter { it !in excused }

    fun setDone(
        commanderId: String,
        ready: Boolean,
    ) {
        if (commanderById(commanderId) == null) return
        if (ready) done += commanderId else done -= commanderId
    }

    /** Shared turn readiness is cleared by any accepted gameplay order, so players review the change. */
    fun clearDone() {
        done.clear()
    }

    fun allDone(): Boolean {
        val required = requiredCommanderIds()
        return required.isNotEmpty() && required.all { it in done }
    }

    fun excuse(
        commanderId: String,
        excused: Boolean,
    ) {
        if (commanderById(commanderId) == null) return
        if (excused) {
            this.excused += commanderId
            done -= commanderId
        } else {
            this.excused -= commanderId
        }
    }

    // -- Missions ------------------------------------------------------------------------------

    /**
     * The campaign has loaded its next mission ([newFile]). Archives the finished one, counts it as
     * completed ONCE (a retry of the same mission, or a branch back to an earlier one, adds
     * nothing), grants whatever common unlocks that earns, and starts the new mission clean:
     * tactical assignments, transfers, readiness and the ledger do not carry over -- unit ids are
     * recycled by every scenario -- while commanders, their portraits and the campaign totals do.
     * Last mission's allocation survives only as [formationSuggestions].
     */
    fun beginNextMission(newFile: String) {
        if (missionFile.isNotEmpty()) {
            summaries += summaryOfCurrent()
            if (summaries.size > MAX_SUMMARIES) summaries.removeAt(0)
            if (newFile != missionFile) completedMissions += missionFile
        }
        unlocksEarned = CoopProgress.unlocksAfter(completedMissions.size, campaignTotal)
        missionIndex = if (missionFile.isEmpty()) 0 else missionIndex + 1
        missionFile = newFile
        ordersThisMission = 0
        briefing = null
        economy.proposals.clear()
        assignments.clear()
        transfers.clear()
        done.clear()
        ledger = CoopLedger()
        turnKey = ""
    }

    private fun summaryOfCurrent(): CoopMissionSummary =
        CoopMissionSummary(
            index = missionIndex,
            file = missionFile,
            teamDestroyed = ledger.teamDestroyed,
            teamCaptured = ledger.teamCaptured,
            teamOwnLosses = ledger.teamOwnLosses,
            perCommander = ledger.perCommander.mapValues { it.value.copy() },
        )

    /**
     * Offers last mission's allocation back: each unit whose core formation was a commander's
     * returns to them. [formationOf] maps a unit id of the NEW mission to its formation id. Nothing
     * here is a lock -- the pre-battle board can release any of it.
     */
    fun applySuggestions(
        unitIds: List<Int>,
        formationOf: (Int) -> String?,
    ) {
        unitIds.forEach { unitId ->
            val commander = formationOf(unitId)?.let(formationSuggestions::get) ?: return@forEach
            if (commanderById(commander) != null && unitId !in assignments) assignments[unitId] = commander
        }
    }

    /**
     * Called whenever the (turn, current player) identity may have changed. Returns true when it did:
     * done flags are dropped and the contribution window rolls. Accepted transfers land only when
     * the FRIENDLY turn begins -- through the enemy turn the old commander's bonuses, defences and
     * support credit stay exactly as they were.
     */
    fun onTurnKey(
        key: String,
        friendlyTurn: Boolean,
    ): Boolean {
        if (key == turnKey) return false
        turnKey = key
        done.clear()
        ledger.rollWindow(key)
        if (friendlyTurn) applyAcceptedTransfers()
        return true
    }

    companion object {
        const val MAX_RETIRED = 64

        /** Mission summaries kept in the snapshot; older ones are dropped so a long campaign stays bounded (§8.4). */
        private const val MAX_SUMMARIES = 40
    }
}
