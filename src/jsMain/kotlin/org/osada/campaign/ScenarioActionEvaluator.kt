package org.osada.campaign

import org.osada.model.GameMap
import org.osada.model.getPlayer
import org.osada.model.getUnits

/**
 * A declarative optional-objective rule, evaluated ONCE against real end-of-scenario game state.
 *
 * Rules never run during play, never run on a preview, and never run on save restore — they are
 * evaluated at the single point where the engine has definitively finished the scenario
 * (`Game.continueCampaign`). Their inputs are live map facts only; a rule can no more invent a
 * captured airfield than dialogue can invent a victory.
 *
 * [id] is the stable campaign fact recorded on success, e.g. `airfield_held_at_end`. It is stored
 * qualified with its scenario (`n_kiel.xml.airfield_held_at_end`) so two scenarios may reuse a
 * short name without colliding.
 */
internal sealed class ScenarioActionRule {
    abstract val id: String

    /** All listed hexes are owned by the player's side when the scenario ends ("held at end"). */
    data class HexesHeld(
        override val id: String,
        val hexes: List<HexRef>,
        /** Allow partial success: N of M suffices. Defaults to all. */
        val atLeast: Int?,
    ) : ScenarioActionRule()

    /** No listed hex is owned by the player's side at scenario end ("lost" / "never taken"). */
    data class HexesNotHeld(
        override val id: String,
        val hexes: List<HexRef>,
    ) : ScenarioActionRule()

    /** At least [atLeast] of the named units are alive at scenario end (escort / detachment survival). */
    data class UnitsSurvived(
        override val id: String,
        val unitIds: List<Int>,
        val atLeast: Int,
    ) : ScenarioActionRule()

    /** The scenario finished on or before [turn] — "objective taken before turn 8". */
    data class FinishedByTurn(
        override val id: String,
        val turn: Int,
    ) : ScenarioActionRule()

    /** At most [maxLosses] core units were destroyed. */
    data class CoreLossesAtMost(
        override val id: String,
        val maxLosses: Int,
    ) : ScenarioActionRule()

    /**
     * At least one of the named authored `<event>`s in the scenario XML actually fired.
     *
     * This is the one rule that reads a fact established DURING the battle rather than from the
     * end-state map, because some facts are not visible in the end state at all: a rescue that
     * converted a unit leaves nothing behind to count, and "captured at some point but later lost"
     * is exactly the distinction end-state evaluation cannot make. The fact is still real gameplay
     * — an event fires only when its authored trigger was genuinely satisfied — and it is still
     * evaluated once, at scenario completion, like every other rule here.
     */
    data class EventFired(
        override val id: String,
        val events: List<String>,
    ) : ScenarioActionRule()
}

internal data class HexRef(
    val row: Int,
    val col: Int,
)

/** Live facts the rules are evaluated against. Assembled once, at scenario completion. */
internal data class ScenarioEndState(
    val map: GameMap,
    val playerSide: Int,
    val turn: Int,
    val coreLosses: Int,
    /** Ids of the scenario's authored events that fired during the battle. */
    val firedEvents: Set<String> = emptySet(),
)

internal object ScenarioActionEvaluator {
    /**
     * Returns the ids of every rule that holds. A rule that cannot be evaluated (missing hex,
     * unknown unit) is reported as NOT satisfied and warned about — never as satisfied, because a
     * false positive would let the campaign claim an objective the player did not achieve.
     */
    fun evaluate(
        rules: List<ScenarioActionRule>,
        end: ScenarioEndState,
    ): Set<String> = rules.filter { satisfied(it, end) }.mapTo(mutableSetOf()) { it.id }

    @Suppress("TooGenericExceptionCaught")
    private fun satisfied(
        rule: ScenarioActionRule,
        end: ScenarioEndState,
    ): Boolean =
        try {
            when (rule) {
                is ScenarioActionRule.HexesHeld -> {
                    val held = rule.hexes.count { ownedByPlayer(it, end) }
                    held >= (rule.atLeast ?: rule.hexes.size) && rule.hexes.isNotEmpty()
                }

                is ScenarioActionRule.HexesNotHeld ->
                    rule.hexes.isNotEmpty() && rule.hexes.none { ownedByPlayer(it, end) }

                is ScenarioActionRule.UnitsSurvived ->
                    end.map
                        .getUnits()
                        .count { it.id in rule.unitIds && !it.destroyed } >= rule.atLeast

                is ScenarioActionRule.FinishedByTurn -> end.turn <= rule.turn
                is ScenarioActionRule.CoreLossesAtMost -> end.coreLosses <= rule.maxLosses
                is ScenarioActionRule.EventFired ->
                    rule.events.isNotEmpty() && rule.events.any { it in end.firedEvents }
            }
        } catch (e: Throwable) {
            console.warn("[OSADA] scenario action rule '${rule.id}' failed to evaluate, treated as NOT achieved", e)
            false
        }

    private fun ownedByPlayer(
        ref: HexRef,
        end: ScenarioEndState,
    ): Boolean {
        val hex =
            end.map.map
                ?.getOrNull(ref.row)
                ?.getOrNull(ref.col)
        if (hex == null) {
            console.warn("[OSADA] scenario action references missing hex ${ref.row},${ref.col}")
        }
        val owner = hex?.owner ?: -1
        return owner != -1 && end.map.getPlayer(owner).side == end.playerSide
    }
}

/**
 * Parses a scenario's `actions` array from campaign JSON. Malformed rules are dropped with a
 * warning; the scenario still completes and its outcome is still recorded.
 */
internal object ScenarioActionParser {
    fun parseList(value: dynamic): List<ScenarioActionRule> = BriefingDynamic.mapArray(value, ::parseOne)

    @Suppress("TooGenericExceptionCaught")
    private fun parseOne(item: dynamic): ScenarioActionRule? =
        try {
            val id = BriefingDynamic.str(item?.id)?.trim()?.takeIf { it.isNotBlank() }
            val type = BriefingDynamic.str(item?.type)?.trim()
            if (id == null || type == null) {
                console.warn("[OSADA] scenario action dropped: needs both 'id' and 'type'", item)
                null
            } else {
                build(id, type, item)
            }
        } catch (e: Throwable) {
            console.warn("[OSADA] scenario action parse failed, rule dropped", e)
            null
        }

    private fun build(
        id: String,
        type: String,
        item: dynamic,
    ): ScenarioActionRule? =
        when (type) {
            "hexesHeld" ->
                ScenarioActionRule.HexesHeld(id, parseHexes(item.hexes), BriefingDynamic.int(item.atLeast))

            "hexesNotHeld" -> ScenarioActionRule.HexesNotHeld(id, parseHexes(item.hexes))
            "unitsSurvived" ->
                ScenarioActionRule.UnitsSurvived(
                    id,
                    parseIntList(item.unitIds),
                    BriefingDynamic.int(item.atLeast) ?: 1,
                )

            "finishedByTurn" ->
                BriefingDynamic.int(item.turn)?.let { ScenarioActionRule.FinishedByTurn(id, it) }

            "coreLossesAtMost" ->
                BriefingDynamic.int(item.maxLosses)?.let { ScenarioActionRule.CoreLossesAtMost(id, it) }

            "eventFired" -> ScenarioActionRule.EventFired(id, BriefingDynamic.strList(item.events))

            else -> {
                console.warn("[OSADA] unknown scenario action type '$type' on '$id', rule dropped")
                null
            }
        }

    private fun parseHexes(value: dynamic): List<HexRef> =
        BriefingDynamic.mapArray(value) { item ->
            val row = BriefingDynamic.int(item?.row)
            val col = BriefingDynamic.int(item?.col)
            if (row == null || col == null) null else HexRef(row, col)
        }

    private fun parseIntList(value: dynamic): List<Int> = BriefingDynamic.mapArray(value) { BriefingDynamic.int(it) }
}
