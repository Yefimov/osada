package org.osada.rules

import org.osada.TerrainType
import org.osada.model.Hex
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey

/**
 * Shell craters — **an OSADA rule, not an Open General one**, behind [RuleKey.CRATERS].
 *
 * A barrage that lands on open ground has nothing to wreck: OG destroys facilities and roads, and
 * clear ground, snow and sand are on nobody's list (`docs/og-fidelity-plan.md` §R.7). Under this
 * rule the shells leave holes instead of leaving nothing — holes that cost a movement point to
 * cross and that a formation can fight from.
 *
 * ### Why it is a floor and not a bonus
 *
 * A crater does not ADD entrenchment; it sets the lowest level the occupant may have, exactly as
 * terrain does ([org.osada.model.TerrainEx.baseEntrenchment], which is 0 on all three of these
 * terrains). So it is cover you did not have to dig for, and never cover better than digging.
 *
 * That shape is the whole anti-abuse design. An additive bonus would make shelling your OWN front
 * line a way to fortify it: rear artillery preparing positions with ammunition nobody else was
 * going to spend, every turn, forever. As a floor it can never beat what a turn of entrenching
 * already gives, so it pays only where there was no time to dig — which is what a shell hole is
 * actually for.
 *
 * ### Why it is off in Open General Fidelity
 *
 * No OG source grants a crater cover of any kind, and OG's own barrage takes entrenchment AWAY
 * (`Barrage.ENTRENCHMENT_DAMAGE`, the one figure OG publishes about it). A profile whose claim is
 * "these are Open General's rules" must not carry one of ours, so [RuleKey.CRATERS] is 0 there and
 * says so in its own documentation.
 */
internal object Craters {
    /**
     * The entrenchment level a crater guarantees its occupant.
     *
     * One, not two: two is what OG's barrage REMOVES, and a hole that gave back more than the
     * shelling took would make being shelled an improvement.
     */
    const val COVER_FLOOR = 1

    /** Ground a shell can crater: open terrain with nothing on it to destroy. Water, high ground
     *  and anything built or wooded are excluded — those either cannot hold a hole or are wrecked
     *  instead, by `Barrage`'s own destruction path (`EngineeringWork.razeableTerrain`). */
    private val CRATERABLE_TERRAIN =
        setOf(
            TerrainType.CLEAR.value,
            TerrainType.SAND.value,
        )

    /** Whether the rule is in force. Off in every built-in profile, including — deliberately —
     *  Open General Fidelity. */
    fun enabled(): Boolean = ActiveRuleset.flag(RuleKey.CRATERS, false)

    /**
     * Whether [hex] is ground a barrage would crater rather than leave untouched.
     *
     * Snow is not a terrain in OSADA's table — it is a GROUND CONDITION over the same terrain
     * (`GroundCondition.FROZEN`), so a frozen clear hex is already covered by clear.
     */
    fun crushable(hex: Hex): Boolean = enabled() && !hex.crater && hex.terrain in CRATERABLE_TERRAIN

    /** Digs the hole. Returns false when the ground would not take one. */
    fun dig(hex: Hex): Boolean {
        if (!crushable(hex)) return false
        hex.crater = true
        return true
    }

    /** The entrenchment floor [hex] gives whoever holds it: [COVER_FLOOR] in a crater, else none.
     *  Read by `GameUnitLifecycle`'s entrenchment tick and by the AI's position evaluation, so the
     *  side that digs in and the side that judges where to stand read one function. */
    fun entrenchmentFloor(hex: Hex?): Int = if (enabled() && hex?.crater == true) COVER_FLOOR else 0
}
