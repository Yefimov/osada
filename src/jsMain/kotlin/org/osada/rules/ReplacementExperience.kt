package org.osada.rules

import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey
import kotlin.math.roundToInt

/**
 * What ordinary replacements do to a formation's experience
 * (`docs/player-comfort-roadmap.md` P2 item 9, decided by the owner on 2026-08-18:
 * *"experienced unit when gains replacement it should lower their XP, because they invite new
 * soldiers to the unit"*).
 *
 * The veterans keep what they learned and the new intake arrives knowing nothing, so the formation's
 * experience is the strength-weighted average of the two groups. One place defines it, because
 * [ActionAvailability]'s preview and the mutation itself must never be able to disagree -- the whole
 * point of the preview is that it states what will actually happen.
 *
 * Deliberately NOT applied to overstrength. That action is the premium one -- it is priced with
 * `CostCalculator.OVERSTRENGTH_PENALTY`, gated behind `OVERSTRENGTH_MIN_EXPERIENCE` and capped by a
 * ceiling that scales with experience -- so diluting it would let a unit spend its veterancy to buy
 * a point and, in the process, revoke the precondition that allowed the purchase. There is therefore
 * still no way to heal a damaged veteran without diluting it; a separate costlier "elite
 * replacement" action is the obvious answer and is not built here.
 *
 * **That prediction came true on 2026-08-31, and not as a new action.** OG's own name for the
 * ordinary replacement IS *elite*, priced by `elite_cost` and preserving experience; the cheap
 * diluting one is [GreenReplacements]. So the "separate costlier action" was already in the efile
 * and OSADA was simply charging the wrong percentage for it. See [EliteReplacements] for the
 * arithmetic, the defect it closes, and why the dilution below stays for every efile that authors
 * no green alternative.
 */
internal object ReplacementExperience {
    /**
     * Whether the campaign's ruleset dilutes ordinary replacements at all.
     *
     * **Two switches, and the second is the efile's** ([EliteReplacements.preservesExperience]).
     * The player's `replacement_experience` key is the first. The second exists because OG's
     * `green` efiles ship a SECOND, cheaper replacement action whose whole selling point is that it
     * costs veterancy: where that action exists, the ordinary one must be the one that preserves,
     * or the discount comes with no downside and the expensive action has no purpose. 9 of the 10
     * shipped efiles author no green action and are untouched by this clause. Owner's ruling,
     * 2026-08-31; [EliteReplacements] carries it in full.
     */
    fun dilutes(): Boolean =
        ActiveRuleset.flag(RuleKey.REPLACEMENT_EXPERIENCE, efileDefault = true) &&
            !EliteReplacements.preservesExperience()

    /**
     * The experience [restored] fresh strength points leave a formation with.
     *
     * Returns [currentExperience] unchanged when the rule is off, when nothing is actually restored,
     * or when there is no experience to dilute -- so a caller can apply it unconditionally.
     */
    fun afterReplacement(
        currentExperience: Int,
        currentStrength: Int,
        restored: Int,
    ): Int {
        if (!dilutes()) return currentExperience
        return diluted(currentExperience, currentStrength, restored)
    }

    /**
     * The weighted average itself, independent of the ruleset, so the arithmetic can be tested
     * without a resolved ruleset in place.
     *
     * `(veterans × their experience + intake × 0) / total`. A unit at strength 3 with 400 experience
     * rebuilt to 10 keeps `400 × 3 / 10` = 120.
     */
    fun diluted(
        currentExperience: Int,
        currentStrength: Int,
        restored: Int,
    ): Int =
        when {
            // A formation with no strength left is destroyed, not reinforceable; guarding here keeps
            // the function total rather than relying on every caller to have checked.
            currentStrength <= 0 -> 0
            restored <= 0 || currentExperience <= 0 -> currentExperience
            else ->
                (currentExperience.toDouble() * currentStrength / (currentStrength + restored)).roundToInt()
        }
}
