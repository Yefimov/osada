package org.osada.rules

import org.osada.multiplayer.sync.SeededGameRandom

/**
 * The one random stream every GAMEPLAY outcome draws from, and the reason combat may be stochastic
 * at all (`docs/design/ruleset-profiles.md` §2, `docs/og-fidelity-plan.md` §H.6).
 *
 * ### The problem this exists to solve
 *
 * Multiplayer **replays** commands rather than transmitting their results: an `AttackUnit` command
 * is applied by calling `GameMap.attackUnit` on every peer
 * (`multiplayer/command/OsadaGameCommandHandlers`). Two peers therefore have to derive the same
 * outcome from the same state. Every call to `kotlin.random.Random` on that path is a divergence —
 * the two players end an identical battle holding different units.
 *
 * A single global seeded stream fixes that, provided three rules hold. They are the whole contract:
 *
 *  1. **Only a COMMITTED action may draw.** A preview, a hover, an AI evaluation and a repaint must
 *     never advance the cursor, because they do not happen the same number of times on both peers.
 *     Combat carries this as `CombatResolver.calculateAttackResults(committed = ...)`, which is
 *     `false` everywhere except the three sites that actually apply damage.
 *  2. **Draws happen in command order.** Commands are already applied in one agreed order, so the
 *     stream stays in step as long as rule 1 holds.
 *  3. **The seed and the cursor travel with the game state.** [seed] and [cursor] are written into
 *     the save envelope beside the ruleset block, which is also what a joining or resyncing client
 *     restores (`OsadaMultiplayer.applySnapshot` restores the host's whole state), so a client
 *     adopts the host's stream position for free.
 *
 * ### Why the cursor is replayed rather than stored as raw state
 *
 * [restore] re-seeds and re-draws [cursor] times instead of writing the generator's internal word
 * back. That keeps the persisted form two plain numbers with an obvious meaning — "this stream, this
 * far in" — which survives a save-format reader that has never heard of xorshift, and it cannot be
 * corrupted into a state the generator could not have reached. The cost is one xorshift step per
 * draw already taken; gameplay draws are per-battle events numbering in the thousands at most, so
 * this is microseconds even late in a long campaign.
 *
 * ### What still does NOT draw from here, deliberately
 *
 * Anything outside the replayed command path keeps `kotlin.random.Random`, because routing it here
 * would advance the cursor on one peer only — the exact failure this object prevents:
 * scenario-setup choices (`ScenarioEconomy.getRandomPrototype`, `ScenarioUnitParser`'s authored
 * leaders), AI deliberation (`AIScoring`, `AIPurchasing`), transport fault injection, and id
 * generation. The hero system is already deterministic by a different and better route — it seeds
 * `hero/SeededRandom` from the hero's own context — and is left alone.
 */
object GameRandomSource {
    private const val FALLBACK_SEED = 0x5DEECE66DL

    private var currentSeed: Long = FALLBACK_SEED
    private var draws: Long = 0
    private var stream: SeededGameRandom = SeededGameRandom(FALLBACK_SEED)

    /** The stream's seed. Persisted; identical on every peer in a room. */
    fun seed(): Long = currentSeed

    /** How many draws have been taken. Persisted, and the half that makes a mid-game save resume
     *  the same stream rather than restarting it. */
    fun cursor(): Long = draws

    /**
     * Begins a fresh stream for a new battle.
     *
     * Called only from the NEW-scenario path (`Game.onScenarioLoadFinished` with `fromRestore`
     * false). A restore must go through [restore] instead, or reloading a save would re-roll every
     * outcome still ahead of it.
     */
    fun start(seed: Long) {
        currentSeed = if (seed == 0L) FALLBACK_SEED else seed
        draws = 0
        stream = SeededGameRandom(currentSeed)
    }

    /** Restores a stream to the exact position a save, a checkpoint or a host snapshot recorded. */
    fun restore(
        seed: Long,
        cursor: Long,
    ) {
        start(seed)
        var remaining = cursor
        while (remaining > 0) {
            stream.nextDouble()
            remaining--
        }
        draws = cursor
    }

    /** Uniform integer in `[0, bound)`. */
    fun nextInt(bound: Int): Int {
        draws++
        return stream.nextInt(bound)
    }

    /** Uniform double in `[0, 1)`. */
    fun nextDouble(): Double {
        draws++
        return stream.nextDouble()
    }

    /** Uniform integer in `[min, max]`, the shape the legacy `rollDice` helper used. */
    fun nextInRange(
        min: Int,
        max: Int,
    ): Int = if (max <= min) min else min + nextInt(max - min + 1)

    internal fun resetForTest() {
        start(FALLBACK_SEED)
    }
}
