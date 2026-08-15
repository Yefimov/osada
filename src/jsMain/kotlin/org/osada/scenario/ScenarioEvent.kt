package org.osada.scenario

import org.osada.model.GameUnit

/**
 * Declarative, scenario-authored events: "when the player's troops come within N hexes of this
 * place, put these units on the map and say this about it".
 *
 * They exist so a scenario can stage something that is not true at load time — a garrison that
 * only reacts when it is approached, a demolition that only matters once someone reaches the
 * bridge, prisoners who are only in danger once the alarm goes up — **without** a campaign-specific
 * exception buried in the combat or AI rules. Everything an event does is data in the scenario
 * XML; the engine only evaluates and applies it.
 *
 * Design rules the rest of this file depends on:
 *
 * - **An event fires at most once.** [ScenarioEvent.fired] is set before its effects are applied
 *   and is part of the save, so reload, mission restart and a double evaluation in the same tick
 *   are all no-ops. Firing is deliberately NOT undone by [org.osada.model.MoveExecutor]'s undo:
 *   an alarm that has been raised has been raised.
 * - **Events never decide an outcome.** They place, remove and announce. Whether the campaign
 *   *credits* the player for one is a separate, declarative question asked at scenario end by
 *   `ScenarioActionRule.EventFired` reading [ScenarioEvent.fired].
 * - **Unresolvable data fails closed.** A missing hex, an unknown eqid or an unknown referenced
 *   event id means the event does not fire — never that it fires with a broken effect.
 */
internal enum class ScenarioEventTriggerKind {
    /** Eligible from the moment the player is handed control (after the campaign briefing). */
    START,

    /** Eligible once a qualifying unit stands within [ScenarioEventTrigger.radius] of the anchor. */
    PROXIMITY,
}

/**
 * Where an event watches, and whose movement it watches for.
 *
 * [row]/[col] is both the proximity centre and the hex the event's message is pinned to, so an
 * anchored announcement always points at the thing it is about.
 */
internal data class ScenarioEventTrigger(
    val kind: ScenarioEventTriggerKind,
    val row: Int,
    val col: Int,
    /** Hex radius; `0` means the anchor hex itself. Ignored by [ScenarioEventTriggerKind.START]. */
    val radius: Int,
    /** Side whose units trip the trigger, or `-1` for any side. */
    val side: Int,
    /**
     * When true, only units that can actually shoot count. Without it a supply cart driving past
     * the compound would raise the alarm, and — worse — the detainees the event itself spawns
     * would satisfy their own trigger.
     */
    val combatOnly: Boolean,
)

/**
 * Preconditions that must all hold before an eligible event fires.
 *
 * [allFlags]/[noneFlags] read the CAMPAIGN narrative flags, which is how one scenario file carries
 * two variants of the same situation (the player chose to break the prisoners out early, or did
 * not). Outside a campaign no flag is ever set, so a `noneFlags` gate passes and an `allFlags`
 * gate does not — a standalone launch of the same scenario gets the default branch.
 */
internal data class ScenarioEventGate(
    val allFlags: List<String> = emptyList(),
    val noneFlags: List<String> = emptyList(),
    /** At least one of these events must already have fired. Empty = unconstrained. */
    val afterAny: List<String> = emptyList(),
    /**
     * At least one unit spawned by one of these events must still be alive.
     *
     * Deliberately expressed against the *spawning event* rather than a hex: a spawned unit can
     * retreat, and "are the prisoners still there to be freed" must not silently become false
     * because a combat result pushed them one hex sideways.
     */
    val requiresUnitsFrom: List<String> = emptyList(),
)

/** One unit an event places, and where. The hex is a preference — see `deployReinforcement`. */
internal data class ScenarioEventSpawn(
    val row: Int,
    val col: Int,
    val unit: GameUnit,
)

/**
 * One authored event. Mutable state is only [fired] and [spawnedUnitIds]; both are serialized.
 *
 * [removeFrom] names events whose still-living spawned units this event takes off the map. That is
 * how a rescue is expressed: remove the detainees, place the detachment they became. Removal is by
 * source event, not by hex, for the same reason [ScenarioEventGate.requiresUnitsFrom] is.
 */
internal class ScenarioEvent(
    val id: String,
    val trigger: ScenarioEventTrigger,
    val gate: ScenarioEventGate,
    /** Anchored announcement shown when the event fires. Blank authors no message. */
    val message: String,
    val spawns: List<ScenarioEventSpawn>,
    val removeFrom: List<String>,
) {
    var fired: Boolean = false

    /** Map ids of the units this event actually placed, for [ScenarioEventGate.requiresUnitsFrom]. */
    val spawnedUnitIds: MutableList<Int> = mutableListOf()
}
