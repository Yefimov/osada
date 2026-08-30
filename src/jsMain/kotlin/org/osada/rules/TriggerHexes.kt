package org.osada.rules

import org.osada.GameHolder
import org.osada.model.Cell
import org.osada.model.EfileConfig
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.Leaders
import org.osada.model.Player
import org.osada.model.acquireUnit
import org.osada.model.reinforce
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey
import org.osada.scenario.getRandomPrototype

/**
 * Open General 9.10's **trigger hexes**.
 *
 * > *"It is possible to set any hex as a trigger: a hex where if a unit ends there its move,
 * > something happens. You can do this in the Map Settings View, in the lower left corner, by
 * > selecting the trigger type in the combo box and then typing the parameter."*
 * > — `Manual_OSuite-Scenario.pdf` §3.4
 *
 * ### Four days blocked on two bytes, and how they were unblocked
 *
 * `docs/og-fidelity-plan.md` §L.6 filed this mechanic as blocked because *"the per-hex trigger flag
 * is unlocated in the `.xscn` binary"*, and §Z.0 later narrowed that correctly: **all nine actions
 * and their parameter conventions were published all along; only the storage was unknown.** The
 * owner took the controlled OpenSuite diff on 2026-08-29 and both offsets fell in one sitting —
 * `@20` type, `@21` parameter, plus `@22` equipment id and `@26` message index nobody had
 * predicted (`SCENARIO_FORMAT_NOTES.md`).
 *
 * **311 trigger hexes across 86 of the 502 deployed scenarios**; 858 across 401 corpus-wide.
 *
 * ### The nine actions, and the one that is imported and not run
 *
 * | code | action | parameter | here |
 * |---|---|---|---|
 * | 1 | Replacements | — | [replenish] |
 * | 2 | Gain prestige | 0 = random 40–160 | [award] |
 * | 3 | Gain experience | 0 = random 40–100 | [award] |
 * | 4 | Raise leader | — | *"the unit receives a leader, if it hasn't one"* |
 * | 5 | Raise prototype | month window, default 9 | a prototype into the player's pool |
 * | 6 | **Change AI stance** | — | **imported, deliberately NOT executed** |
 * | 7 | Extra spot | 0 = random radius 3–10 | [extraSpot] |
 * | 8 | Raise specific unit | equipment id at `@22` | into the player's pool |
 * | 9 | Raise specific core | equipment id at `@22` | into the player's pool, as core |
 *
 * **Action 6 is the only one refused, and §0.2 is why.** OSADA has no AI stance model, and that
 * section blocks any AI-stance work until `docs/design/ai-benchmark-suite.md` exists and forbids
 * ever shipping something labelled "OG AI" without it. It is 13 hexes corpus-wide and **3 in
 * deployed content**, so refusing it costs almost nothing; approximating it would mean inventing
 * the stance model §0.2 exists to prevent. The data is imported so the day the model arrives this
 * is one `when` branch, not another import.
 *
 * A second, smaller refusal: OG's own parameter convention for action 6 says *"0 not expiring, or
 * number of turns to expire"*, and the author's current Features page says that parameter is not
 * meaningful at all (`docs/og-sources.md` §5). Two sources disagree, the newer wins, and neither
 * matters while the action does not run.
 *
 * ### Firing once is an inference, and it is the one that cannot overstate
 *
 * OG says what happens, never how often. [Hex.triggerFired] makes it once-only because every one of
 * the eight executed actions is a **gift** — prestige, experience, a leader, a free formation — and
 * a repeating trigger is a tap the player farms by stepping off the hex and back on. That would
 * silently re-tune all 86 scenarios; once-only can only under-deliver. `docs/og-sources.md` step 3.
 *
 * ### Whose unit, and when — `trigger_ex`, and there is no separate owner field
 *
 * *"if a unit ends there its move"*, and WHICH unit is decided by the efile:
 *
 * > *"0 = default, trigger needs an owner and only units of a **different player** can activate it.
 * > 1 = trigger has no owner and can be activated by any player."* — `EFILE_NOKORP/equip.cfg`
 *
 * **The owner is the hex's own [Hex.owner]**, not a field of its own — this object said otherwise
 * until 2026-08-30, on the assumption that `trigger_ex`'s wording implied a dedicated byte. It does
 * not, and the population says so plainly: of the 858 corpus trigger hexes **734 are owned by
 * player 2**, 19 by player 1, 2 by player 3 and 103 by nobody. A designer puts the reward on
 * enemy ground and the other side walks in to collect it, which is exactly what the default means.
 *
 * The 103 unowned ones are inert under the default and live under `= 1`. That is the literal
 * reading of *"trigger needs an owner"* and it is the one that cannot overstate: treating "no
 * owner" as "anybody" would hand out rewards in a mode whose whole point is that they are
 * contested. `eqp-atomic` and `eqp-basekorp` set `1`; `eqp-nokorp` and `eqp-roi` set `0`; every
 * other efile is silent and gets OG's stated default of 0.
 *
 * ### Gated, and why the default is off
 *
 * [RuleKey.TRIGGER_HEXES], schema 13, defaulted **off**. Every action gives something away, so
 * turning it on makes 86 scenarios materially easier for both sides — a re-tuning the player
 * chooses rather than inherits. Author's Vision resolves it on, because a trigger hex is the
 * author's own content in the same sense a rail pool is.
 */
object TriggerHexes {
    /** OG's nine action codes, in the order `Manual_OSuite-Scenario.pdf` §3.4 lists them. */
    const val REPLACEMENTS = 1
    const val PRESTIGE = 2
    const val EXPERIENCE = 3
    const val LEADER = 4
    const val PROTOTYPE = 5
    const val AI_STANCE = 6
    const val EXTRA_SPOT = 7
    const val SPECIFIC_UNIT = 8
    const val SPECIFIC_CORE = 9

    /** *"Parameter: 0 for random (40-160), or up to 255."* */
    private val PRESTIGE_RANDOM = 40..160

    /** *"Parameter: 0 for random (40-100), or up to 255."* */
    private val EXPERIENCE_RANDOM = 40..100

    /** *"Parameter: 0 for random (3-10), or up to 255 hexes."* */
    private val SPOT_RANDOM = 3..10

    /** *"Parameter: time frame of the prototype, default 9."* */
    private const val PROTOTYPE_DEFAULT_MONTHS = 9

    /** `trigger_ex = 0`, OG's stated default: the trigger is owned and only the other side fires it. */
    private const val OWNED_TRIGGERS = 0

    fun enabled(): Boolean = ActiveRuleset.flag(RuleKey.TRIGGER_HEXES, false)

    /** Whether [hex] carries a trigger that could still fire for somebody. */
    fun isArmed(hex: Hex): Boolean = hex.trigger != 0 && !hex.triggerFired

    /**
     * `trigger_ex`: whether [unit] is allowed to set [hex]'s trigger off at all.
     *
     * `1` — no owner, anybody. `0` or absent — the hex must have an owner and the arriving
     * formation must belong to a DIFFERENT player.
     */
    fun activatableBy(
        unit: GameUnit,
        hex: Hex,
    ): Boolean =
        if (EfileConfig.intKey("trigger_ex", OWNED_TRIGGERS) != OWNED_TRIGGERS) {
            true
        } else {
            hex.owner >= 0 && hex.owner != unit.owner
        }

    /**
     * OG's parameter convention: 0 means "roll in this band", anything else is the literal value.
     *
     * The roll goes through [GameRandomSource] like every other roll in the engine, so a seeded
     * game reproduces its triggers.
     */
    private fun resolveParam(
        param: Int,
        band: IntRange,
    ): Int = if (param > 0) param else band.first + GameRandomSource.nextInt(band.last - band.first + 1)

    /**
     * Fire [hex]'s trigger for [unit], which has just ended its move there.
     *
     * Returns the message to show the player, or null when nothing fired. The message is the
     * author's own [Hex.triggerMessage] where they wrote one; a trigger with no authored text
     * fires silently, exactly as OG's own content does — only 33 of 850 corpus triggers carry one.
     */
    fun fire(
        map: GameMap,
        unit: GameUnit,
        hex: Hex,
    ): String? {
        val allowed = enabled() && isArmed(hex) && !unit.destroyed && activatableBy(unit, hex)
        val player = unit.player?.takeIf { allowed } ?: return null
        // Set BEFORE any effect, exactly as `ScenarioEvent.fired` is: an action that re-enters this
        // path -- a spawned unit, a spotting sweep that moves something -- must not fire it twice.
        hex.triggerFired = true
        val applied = apply(map, unit, player, hex)
        return if (applied && hex.triggerMessage.isNotEmpty()) hex.triggerMessage else null
    }

    /** The action dispatch, split from [fire] to keep that function inside detekt's complexity
     *  budget. Returns whether anything actually happened. */
    private fun apply(
        map: GameMap,
        unit: GameUnit,
        player: Player,
        hex: Hex,
    ): Boolean =
        when (hex.trigger) {
            REPLACEMENTS -> replenish(map, unit)
            // 2 -- "the player receives extra prestige points".
            PRESTIGE -> {
                player.prestige += resolveParam(hex.triggerParam, PRESTIGE_RANDOM)
                true
            }
            EXPERIENCE -> gainExperience(unit, hex.triggerParam)
            LEADER -> raiseLeader(unit)
            PROTOTYPE -> raisePrototype(player)
            EXTRA_SPOT -> extraSpot(map, unit, hex.triggerParam)
            // 8 and 9 -- "Raise specific unit" / "Raise specific core", the equipment id from @22.
            //
            // **The two are executed identically, and that is an approximation with a name.**
            // `Player.acquireUnit` puts the formation in the player's own roster, which OSADA
            // carries into the next scenario when a campaign is running and drops when one is not.
            // OG's pair presumably differs exactly there -- one is a loan for this battle, the
            // other joins the core -- but nothing published says so, and inventing the distinction
            // would mean deciding which of the 9 and 37 corpus hexes takes a unit away again.
            // Treating both as "you now have this formation" can only over-deliver on 9 hexes.
            SPECIFIC_UNIT, SPECIFIC_CORE ->
                hex.triggerEquip > 0 && player.acquireUnit(hex.triggerEquip, 0)
            // AI_STANCE, and any code a future OG adds. Consumed so it does not re-fire, and
            // deliberately without effect -- see this object's KDoc.
            else -> false
        }

    /**
     * 1 — *"Replacements"*: the formation is brought back up to strength.
     *
     * **Free, and deliberately not routed through `Player.reinforceUnit`.** That function is the
     * PURCHASE of replacements: it divides the player's prestige by the per-point cost and delivers
     * only what they can afford. A trigger is a gift the map is handing out, so charging for it
     * would make a broke player walk onto the hex and receive nothing. `GameRules.getReinforceValue`
     * is still what decides HOW MUCH — it is the same eligibility and supply test the Reinforce
     * action uses, so a trigger cannot top up a formation the rules say may not be topped up.
     *
     * `GameUnit.reinforce` applies OG's replacement-experience dilution on the way in, exactly as a
     * bought replacement does.
     */
    private fun replenish(
        map: GameMap,
        unit: GameUnit,
    ): Boolean {
        val amount = GameRules.getReinforceValue(map, unit, false)
        if (amount <= 0) return false
        unit.reinforce(amount, false)
        return true
    }

    /** 3 — *"the unit receives extra experience points"*. */
    private fun gainExperience(
        unit: GameUnit,
        param: Int,
    ): Boolean {
        unit.experience =
            (unit.experience + resolveParam(param, EXPERIENCE_RANDOM)).coerceIn(0, UnitExperience.cap())
        return true
    }

    /** 4 — *"the unit receives a leader, **if it hasn't one**"*. The condition is OG's own. */
    private fun raiseLeader(unit: GameUnit): Boolean {
        if (unit.leader >= 0) return false
        unit.leader = Leaders.generateLeader(unit)
        return unit.leader >= 0
    }

    /**
     * 5 — *"the player receives a prototype"*.
     *
     * The parameter is the prototype's month window and OSADA's own prototype picker
     * (`Scenario.getRandomPrototype`) does not take one, so it is deliberately unread rather than
     * approximated: OG's window widens the pool of candidate equipment, and inventing a widening
     * would hand out records the author's window may exclude. [PROTOTYPE_DEFAULT_MONTHS] is
     * recorded so the value is not lost.
     */
    private fun raisePrototype(player: Player): Boolean {
        val scenario = GameHolder.instance?.scenario?.takeIf { it.prototypesAllowed != false }
        val eqid = scenario?.getRandomPrototype(player.country + 1) ?: 0
        return eqid > 0 && player.acquireUnit(eqid, 0)
    }

    /**
     * 7 — *"the unit spots the surrounding area"*.
     *
     * A one-off reveal around the unit at OG's radius rather than a change to its spot range: the
     * action is an event, not a permanent upgrade, and OSADA's spotting is recomputed from unit
     * positions every turn, so anything permanent would have to live on the unit.
     */
    private fun extraSpot(
        map: GameMap,
        unit: GameUnit,
        param: Int,
    ): Boolean {
        val pos = unit.getPos()
        val side = unit.player?.side
        if (pos == null || side == null) return false
        val radius = resolveParam(param, SPOT_RANDOM)
        val cells = HexGeometry.getRing(pos.row, pos.col, radius, map.rows, map.cols, false)
        cells.add(Cell(pos.row, pos.col))
        cells.forEach { cell ->
            val hex = map.map?.getOrNull(cell.row)?.getOrNull(cell.col) ?: return@forEach
            // Enemies are revealed the same way `MovementRules.setSpotRange` reveals them, so a
            // unit found this way behaves identically to one found by walking into view.
            hex.getUnit(false)?.takeIf { it.player?.side != side }?.tempSpotted = true
            hex.setSpotted(side, true)
        }
        return true
    }
}
