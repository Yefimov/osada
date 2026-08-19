package org.osada.rules

import org.osada.model.GameUnit
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey

/**
 * Open General's extra initiative input (OG manual 6.10, `docs/og-fidelity-plan.md` B.6), behind the
 * `initiative_model` key.
 *
 * OG: initiative *"is determined by equipment, terrain and experience of the units; it also it's
 * adjusted by a random value, to simulate combat uncertainty."* OSADA reads equipment initiative,
 * the attachment penalty and `TerrainEx.initiativeCap`, and stops there.
 *
 * **Default `equipment_terrain`, and that is a balance decision, not caution.** Adding experience
 * re-tunes every imported campaign in the veteran's favour, which is the `DEFERRED.md` §5.10 hazard
 * this whole workstream is governed by, and it changes what `First Strike` is worth because that
 * trait re-signs the initiative difference rather than adding to it.
 *
 * ### The random half, and the two things that had to exist before it could ship
 *
 * OG's swing could not simply be rolled at this call site, and the reason is worth keeping:
 *
 *  1. **Multiplayer REPLAYS combat, it does not transmit results.**
 *     `multiplayer/command/OsadaGameCommandHandlers` applies an `AttackUnit` command by calling
 *     `GameMap.attackUnit` on every peer, so both have to derive the same damage from the same
 *     state. A `kotlin.random.Random` draw here would leave them holding different units.
 *  2. **Combat is resolved more than once per attack.** Every hover, every repaint of the combat
 *     forecast and every AI evaluation runs the same pipeline as the real exchange. A draw on those
 *     paths would advance one peer's random stream and not the other's, which desyncs just as hard
 *     as case 1 and is far easier to miss.
 *
 * Both are now solved rather than worked around. [randomAdjustment] draws from
 * [org.osada.rules.GameRandomSource] — one seeded stream whose seed and cursor travel in the save
 * envelope, which is also what a joining or resyncing client restores — and it draws **only** when
 * the caller says the exchange is being committed. `CombatResolver.calculateAttackResults` carries
 * that as `committed`, default `false`, and exactly three call sites pass `true`.
 *
 * **The visible consequence, stated rather than hidden:** with this key on, the combat forecast
 * stops being an exact figure. It shows the deterministic part and the swing lands when the attack
 * is made. With the key off — which is the default and every shipped scenario — nothing draws at
 * all and the forecast is exact, as it always was.
 */
internal object InitiativeModel {
    /** Experience points per initiative point, matching the one-point-per-completed-bar shape the
     *  rest of the engine already uses for experience (`AttackCalculation`'s stat divisor and
     *  `SupplyRules.overstrengthCap`). A partial bar is worth nothing, as in OG 6.7. */
    private const val EXPERIENCE_INITIATIVE_DIVISOR = 100

    /**
     * How far the random adjustment can move one side's initiative in either direction.
     *
     * `INFERENCE`: OG names a random value and never sizes it. Two is chosen against
     * `AttackCalculation.INITIATIVE_ATTACK_BONUS_CAP`, the largest attack bonus an initiative
     * difference can ever buy — a wider swing would let chance decide exchanges outright, a narrower
     * one would not be felt at all.
     */
    private const val RANDOM_SWING = 2

    private const val RANDOM_STEPS = 2 * RANDOM_SWING + 1

    /** Whether OG's model is in force. */
    fun ogFull(): Boolean = ActiveRuleset.flag(RuleKey.INITIATIVE_MODEL, false)

    /**
     * OG's *"random value, to simulate combat uncertainty"*, in initiative points.
     *
     * [committed] is `CombatResolver.calculateAttackResults`'s own flag: true only for the call that
     * actually applies the exchange. Preview, hover, repaint and AI evaluation all pass false and
     * therefore never touch the stream — see this object's header for why that is the whole contract
     * rather than an optimisation.
     */
    fun randomAdjustment(committed: Boolean): Int =
        if (!ogFull() || !committed) 0 else GameRandomSource.nextInt(RANDOM_STEPS) - RANDOM_SWING

    /**
     * Experience's contribution to [unit]'s initiative, or 0 under OSADA's own model.
     *
     * Added BEFORE the terrain cap by the call site, deliberately: `TerrainEx.initiativeCap` exists
     * to clamp the initiative a formation EFFECTIVELY brings into a hex, which is the same reason
     * `Attachments.initiativePenalty` is applied before it. A veteran does not out-manoeuvre a city.
     *
     * `ui/CombatTransparencyPresenter` adds the same term before deciding whether to show the
     * "initiative capped by terrain" chip, so the card cannot tell the player a veteran was uncapped
     * in a town the resolver had just capped it in.
     */
    fun experienceBonus(unit: GameUnit): Int = if (!ogFull()) 0 else unit.experience / EXPERIENCE_INITIATIVE_DIVISOR
}
