@file:Suppress("TooManyFunctions", "UnusedParameter")

package org.osada.multiplayer.coop

import kotlin.js.json

/**
 * Stable-key JSON for the cooperative state and for the commander profile that travels through the
 * lobby. Written by hand with plain keys on purpose: Kotlin/JS IR mangles property names in
 * `JSON.stringify` output, and this text is hashed, relayed by a server that never looks inside it,
 * and read back by a possibly newer build (`AGENTS.md`, porting gotchas).
 *
 * Every identifier that came off the wire is validated on the way in -- portrait ids against the
 * asset allowlists, ability ids against [CoopAbilityCatalogue], versions against this build -- and a
 * snapshot that disagrees is REFUSED rather than silently losing a commander's bonus (§10).
 */
object CoopStateJson {
    const val POLICY_VERSION = 1

    // -- Commander profile (lobby) -------------------------------------------------------------

    fun encodeProfile(commander: CoopCommanderProfile): dynamic =
        json(
            "name" to commander.name,
            "portrait" to encodePortrait(commander.portrait),
            "abilityIds" to commander.abilityIds.toTypedArray(),
            "catalogueVersion" to CoopAbilityCatalogue.VERSION,
        )

    /** Null when the profile is malformed or names something this build does not know. */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    fun decodeProfile(value: dynamic): CoopCommanderProfile? {
        if (value == null || value == undefined) return null
        return try {
            if (intOf(value.catalogueVersion, -1) != CoopAbilityCatalogue.VERSION) return null
            val portrait = decodePortrait(value.portrait) ?: return null
            val abilities = stringList(value.abilityIds)
            CoopCommanderProfile(CoopCommander.cleanName(value.name as? String), portrait, abilities)
        } catch (e: Throwable) {
            console.warn("[coop] unreadable commander profile", e)
            null
        }
    }

    // -- Game state ----------------------------------------------------------------------------

    fun encode(state: CoopState): String =
        JSON.stringify(
            json(
                "policyVersion" to POLICY_VERSION,
                "catalogueVersion" to CoopAbilityCatalogue.VERSION,
                "scoringVersion" to state.ledger.scoringVersion,
                "humanPlayerId" to state.humanPlayerId,
                "unlocksEarned" to state.unlocksEarned,
                "turnKey" to state.turnKey,
                "missionIndex" to state.missionIndex,
                "missionFile" to state.missionFile,
                "ordersThisMission" to state.ordersThisMission,
                "campaignTotal" to state.campaignTotal,
                "completedMissions" to state.completedMissions.toTypedArray(),
                "formationSuggestions" to stringMap(state.formationSuggestions),
                "summaries" to state.summaries.map(::encodeSummary).toTypedArray(),
                "commanders" to state.commanders.map(::encodeCommander).toTypedArray(),
                "assignments" to intKeyed(state.assignments) { it },
                "transfers" to state.transfers.map(::encodeTransfer).toTypedArray(),
                "done" to state.done.sorted().toTypedArray(),
                "excused" to state.excused.sorted().toTypedArray(),
                "ledger" to encodeLedger(state.ledger),
            ).also { value ->
                if (state.retiredCommanders.isNotEmpty()) {
                    value["retiredCommanders"] = state.retiredCommanders.map(::encodeCommander).toTypedArray()
                }
                state.briefing?.let { value["briefing"] = CoopBriefingJson.encode(it) }
                if (!state.economy.isEmpty) value["economy"] = CoopEconomyJson.encode(state.economy)
            },
        )

    /** @throws IllegalStateException when the text is from an incompatible build or is malformed. */
    fun decode(source: String): CoopState {
        val value = JSON.parse<dynamic>(source)
        check(intOf(value.policyVersion, -1) == POLICY_VERSION) { "Cooperative policy version mismatch" }
        check(intOf(value.catalogueVersion, -1) == CoopAbilityCatalogue.VERSION) {
            "Cooperative ability catalogue version mismatch"
        }
        val commanders = list(value.commanders).map(::decodeCommander)
        check(commanders.isNotEmpty()) { "Cooperative state has no commanders" }
        val state = CoopState(commanders, intOf(value.humanPlayerId, 0), intOf(value.unlocksEarned, 0))
        val retired = list(value.retiredCommanders).map(::decodeCommander)
        check(retired.size <= CoopState.MAX_RETIRED && retired.all { it.participantId == null })
        check((commanders + retired).map { it.id }.distinct().size == commanders.size + retired.size)
        state.retiredCommanders += retired
        state.turnKey = value.turnKey as? String ?: ""
        state.missionIndex = intOf(value.missionIndex, 0)
        state.missionFile = value.missionFile as? String ?: ""
        state.ordersThisMission = intOf(value.ordersThisMission, 0)
        state.campaignTotal = intOf(value.campaignTotal, 0)
        stringList(value.completedMissions).forEach(state.completedMissions::add)
        keys(value.formationSuggestions).forEach { key ->
            (value.formationSuggestions[key] as? String)?.let { state.formationSuggestions[key] = it }
        }
        list(value.summaries).forEach { state.summaries += decodeSummary(it) }
        keys(value.assignments).forEach { key ->
            val owner = value.assignments[key] as? String
            val unitId = key.toIntOrNull()
            if (owner != null && unitId != null && state.commanderById(owner) != null) state.assignments[unitId] = owner
        }
        list(value.transfers).forEach { state.transfers += decodeTransfer(it) }
        stringList(value.done).filter { state.commanderById(it) != null }.forEach { state.done += it }
        stringList(value.excused).filter { state.commanderById(it) != null }.forEach { state.excused += it }
        decodeLedger(value.ledger, state.ledger)
        state.briefing = CoopBriefingJson.decodeOptional(value.briefing, (commanders + retired).map { it.id })
        state.economy = CoopEconomyJson.decode(value.economy)
        return state
    }

    // -- Pieces --------------------------------------------------------------------------------

    private fun encodePortrait(portrait: CoopPortrait): dynamic =
        json(
            "artId" to portrait.artId,
            "seed" to portrait.seed,
            "layerIds" to portrait.layerIds.toTypedArray(),
            "poolId" to portrait.poolId,
            "generatorVersion" to portrait.generatorVersion,
        )

    private fun decodePortrait(value: dynamic): CoopPortrait? {
        if (value == null || value == undefined) return null
        val portrait =
            CoopPortrait(
                artId = value.artId as? String,
                seed = intOf(value.seed, 0),
                layerIds = stringList(value.layerIds),
                poolId = value.poolId as? String,
                generatorVersion = intOf(value.generatorVersion, CoopPortrait.GENERATOR_VERSION),
            )
        return portrait.takeIf { it.isValid() }
    }

    private fun encodeCommander(commander: CoopCommander): dynamic =
        json(
            "id" to commander.id,
            "seat" to commander.seat,
            "participantId" to commander.participantId,
            "name" to commander.name,
            "portrait" to encodePortrait(commander.portrait),
            "abilityIds" to commander.abilityIds.toTypedArray(),
        )

    private fun decodeCommander(value: dynamic): CoopCommander {
        val portrait = checkNotNull(decodePortrait(value.portrait)) { "Cooperative portrait refused" }
        val abilities = stringList(value.abilityIds)
        check(abilities.all { CoopAbilityCatalogue.byId(it) != null }) { "Unknown cooperative ability" }
        return CoopCommander(
            id = checkNotNull(value.id as? String) { "Commander without an id" },
            seat = intOf(value.seat, 0),
            participantId = value.participantId as? String,
            name = CoopCommander.cleanName(value.name as? String),
            portrait = portrait,
            abilityIds = abilities,
        )
    }

    private fun encodeSummary(summary: CoopMissionSummary): dynamic {
        val perCommander = js("{}")
        summary.perCommander.forEach { (id, counters) -> perCommander[id] = encodeCounters(counters) }
        return json(
            "index" to summary.index,
            "file" to summary.file,
            "teamDestroyed" to summary.teamDestroyed,
            "teamCaptured" to summary.teamCaptured,
            "teamOwnLosses" to summary.teamOwnLosses,
            "perCommander" to perCommander,
        )
    }

    private fun decodeSummary(value: dynamic): CoopMissionSummary =
        CoopMissionSummary(
            index = intOf(value.index, 0),
            file = value.file as? String ?: "",
            teamDestroyed = intOf(value.teamDestroyed, 0),
            teamCaptured = intOf(value.teamCaptured, 0),
            teamOwnLosses = intOf(value.teamOwnLosses, 0),
            perCommander = keys(value.perCommander).associateWith { decodeCounters(value.perCommander[it]) },
        )

    private fun stringMap(values: Map<String, String>): dynamic {
        val result = js("{}")
        values.forEach { (key, value) -> result[key] = value }
        return result
    }

    private fun encodeTransfer(transfer: CoopTransfer): dynamic =
        json(
            "unitId" to transfer.unitId,
            "fromId" to transfer.fromId,
            "toId" to transfer.toId,
            "initiatorId" to transfer.initiatorId,
            "accepted" to transfer.accepted,
        )

    private fun decodeTransfer(value: dynamic): CoopTransfer =
        CoopTransfer(
            unitId = intOf(value.unitId, -1),
            fromId = value.fromId as? String ?: "",
            toId = value.toId as? String ?: "",
            initiatorId = value.initiatorId as? String ?: "",
            accepted = value.accepted as? Boolean ?: false,
        )

    private fun encodeCounters(c: CoopCounters): dynamic =
        json(
            "points" to c.points,
            "objectives" to c.objectives,
            "destroyed" to c.destroyed,
            "captured" to c.captured,
            "damage" to c.damage,
            "reveals" to c.reveals,
            "support" to c.support,
            "endurance" to c.endurance,
            "forcedRetreats" to c.forcedRetreats,
            "attacksStopped" to c.attacksStopped,
            "strengthLost" to c.strengthLost,
            "strengthCaptured" to c.strengthCaptured,
            "unitsLost" to c.unitsLost,
            "terminalAssists" to c.terminalAssists,
        )

    private fun decodeCounters(v: dynamic): CoopCounters =
        CoopCounters().also {
            it.points = intOf(v.points, 0)
            it.objectives = intOf(v.objectives, 0)
            it.destroyed = intOf(v.destroyed, 0)
            it.captured = intOf(v.captured, 0)
            it.damage = intOf(v.damage, 0)
            it.reveals = intOf(v.reveals, 0)
            it.support = intOf(v.support, 0)
            it.endurance = intOf(v.endurance, 0)
            it.forcedRetreats = intOf(v.forcedRetreats, 0)
            it.attacksStopped = intOf(v.attacksStopped, 0)
            it.strengthLost = intOf(v.strengthLost, 0)
            it.strengthCaptured = intOf(v.strengthCaptured, 0)
            it.unitsLost = intOf(v.unitsLost, 0)
            it.terminalAssists = intOf(v.terminalAssists, 0)
        }

    private fun encodeLedger(ledger: CoopLedger): dynamic {
        val perCommander = js("{}")
        ledger.perCommander.forEach { (id, counters) -> perCommander[id] = encodeCounters(counters) }
        val window = js("{}")
        ledger.windowDamage.forEach { (enemyId, tally) ->
            val inner = js("{}")
            tally.forEach { (commanderId, amount) -> inner[commanderId] = amount }
            window[enemyId.toString()] = inner
        }
        return json(
            "scoringVersion" to ledger.scoringVersion,
            "perCommander" to perCommander,
            "scoredObjectives" to ledger.scoredObjectives.toTypedArray(),
            "revealedEnemies" to ledger.revealedEnemies.toTypedArray(),
            "terminalEnemies" to ledger.terminalEnemies.toTypedArray(),
            "windowDamage" to window,
            "enduranceThisWindow" to intKeyed(ledger.enduranceThisWindow) { it },
            "windowKey" to ledger.windowKey,
            "teamDestroyed" to ledger.teamDestroyed,
            "teamCaptured" to ledger.teamCaptured,
            "teamOwnLosses" to ledger.teamOwnLosses,
            "evidence" to
                ledger.evidence
                    .map {
                        json(
                            "revision" to it.revision.toDouble(),
                            "eventIndex" to it.eventIndex,
                            "commanderId" to it.commanderId,
                            "kind" to it.kind,
                            "points" to it.points,
                            "detail" to it.detail,
                        )
                    }.toTypedArray(),
        )
    }

    private fun decodeLedger(
        value: dynamic,
        ledger: CoopLedger,
    ) {
        if (value == null || value == undefined) return
        ledger.scoringVersion = intOf(value.scoringVersion, CoopLedger.SCORING_VERSION)
        keys(value.perCommander).forEach { ledger.perCommander[it] = decodeCounters(value.perCommander[it]) }
        decodeGuards(value, ledger)
        decodeWindow(value, ledger)
        ledger.teamDestroyed = intOf(value.teamDestroyed, 0)
        ledger.teamCaptured = intOf(value.teamCaptured, 0)
        ledger.teamOwnLosses = intOf(value.teamOwnLosses, 0)
        list(value.evidence).forEach { ledger.evidence += decodeEvidence(it) }
    }

    /** The sets that stop a score being earned twice. */
    private fun decodeGuards(
        value: dynamic,
        ledger: CoopLedger,
    ) {
        list(value.scoredObjectives).forEach { (it as? String)?.let(ledger.scoredObjectives::add) }
        list(value.revealedEnemies).forEach { (it as? Number)?.toInt()?.let(ledger.revealedEnemies::add) }
        list(value.terminalEnemies).forEach { (it as? Number)?.toInt()?.let(ledger.terminalEnemies::add) }
    }

    /** The turn-scoped tallies: damage dealt this window and the endurance already paid out. */
    private fun decodeWindow(
        value: dynamic,
        ledger: CoopLedger,
    ) {
        keys(value.windowDamage).forEach { key ->
            val enemyId = key.toIntOrNull() ?: return@forEach
            val tally = linkedMapOf<String, Int>()
            keys(value.windowDamage[key]).forEach { who -> tally[who] = intOf(value.windowDamage[key][who], 0) }
            ledger.windowDamage[enemyId] = tally
        }
        keys(value.enduranceThisWindow).forEach { key ->
            key.toIntOrNull()?.let { ledger.enduranceThisWindow[it] = intOf(value.enduranceThisWindow[key], 0) }
        }
        ledger.windowKey = value.windowKey as? String ?: ""
    }

    private fun decodeEvidence(it: dynamic): CoopEvidence =
        CoopEvidence(
            revision = (it.revision as? Number)?.toLong() ?: 0L,
            eventIndex = intOf(it.eventIndex, 0),
            commanderId = it.commanderId as? String ?: CoopLedger.UNASSIGNED,
            kind = it.kind as? String ?: "",
            points = intOf(it.points, 0),
            detail = it.detail as? String ?: "",
        )

    // -- Helpers -------------------------------------------------------------------------------

    private fun <V> intKeyed(
        values: Map<Int, V>,
        convert: (V) -> dynamic,
    ): dynamic {
        val result = js("{}")
        values.forEach { (key, value) -> result[key.toString()] = convert(value) }
        return result
    }

    private fun intOf(
        value: dynamic,
        fallback: Int,
    ): Int = (value as? Number)?.toInt() ?: fallback

    private fun keys(value: dynamic): List<String> =
        if (value == null || value == undefined) emptyList() else (js("Object.keys(value)") as Array<String>).toList()

    private fun list(value: dynamic): List<dynamic> {
        if (value == null || value == undefined || !(js("Array.isArray(value)") as Boolean)) return emptyList()
        return (0 until (value.length as Number).toInt()).map { value[it] }
    }

    private fun stringList(value: dynamic): List<String> = list(value).mapNotNull { it as? String }
}

/** What a player puts on their lobby card: a name, a face and chosen abilities. */
data class CoopCommanderProfile(
    val name: String,
    val portrait: CoopPortrait,
    val abilityIds: List<String>,
)
