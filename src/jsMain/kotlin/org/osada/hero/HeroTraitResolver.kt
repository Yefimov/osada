package org.osada.hero

import org.osada.LeaderType
import org.osada.model.GameUnit
import org.osada.model.Leaders

/**
 * The compatibility adapter of design brief §24 — the one place that answers "does this unit have
 * trait X?" during the migration period.
 *
 * ## Why an adapter rather than a rewrite
 *
 * Roughly ten rule call sites ask `Leaders.unitHasLeader(unit, TYPE)`: `AttackCalculation`,
 * `CombatResolver`, `MovementRules`, `AttackEligibility`, `CombatApplication`. Changing all of
 * them to query hero state would mean touching combat code — which is locked by `CombatTest` and
 * is where this project's worst historical bugs came from — in the same change that introduces the
 * hero model. Instead `Leaders.unitHasLeader` now delegates here, every call site keeps its exact
 * signature, and the switch is one function.
 *
 * ## The rule that matters
 *
 * §25 requires that legacy and new effects are **never both granted**. So this is strictly an
 * either/or, decided by whether the unit's formation has a hero:
 *
 * - **hero present** → hero traits only. The unit's legacy `leader` integer is ignored even if
 *   still set (migration leaves it in place so the save stays readable by older builds).
 * - **no hero** → the legacy path, byte-for-byte the old behaviour. This is what scenario-only
 *   units use — they are created by `ScenarioUnitParser` with a rolled leader and never join a
 *   campaign core roster, so they have no formation and never will in Phase 1.
 *
 * A hero's traits come from two sources, matching what the old system granted:
 * its learned traits (the rolled trait, for a migrated hero) and the single trait implied by its
 * professional background (the old hidden class signature, now attributed — see [HeroBackgrounds]).
 */
internal object HeroTraitResolver {
    fun hasTrait(
        unit: GameUnit?,
        leader: LeaderType,
    ): Boolean {
        if (unit == null) return false
        return when (val hero = HeroCampaign.heroFor(unit)) {
            null -> legacyHasTrait(unit, leader)
            else -> heroHasTrait(hero, leader)
        }
    }

    /**
     * A commander still settling into a formation they were transferred into (§1.10) grants none of
     * their traits to it. Suppressing them HERE rather than at each rule is the whole point of this
     * adapter existing: `AttackCalculation`, `CombatResolver`, `MovementRules`, `AttackEligibility`
     * and `CombatApplication` all ask through `Leaders.unitHasLeader`, so one early return disables
     * every bonus at once and no rule can be forgotten. Note this does not fall through to the
     * legacy path — a led formation without its commander's traits has NO traits, not the old ones.
     */
    private fun heroHasTrait(
        hero: HeroState,
        leader: LeaderType,
    ): Boolean {
        if (HeroTransferService.isSettlingIn(hero)) return false
        val learned = LegacyTraitMapping.toTraitId(leader) in hero.learnedTraitIds
        val fromBackground =
            HeroCampaign
                .roster()
                .definition(hero.heroId)
                ?.backgroundId
                ?.let { HeroBackgrounds.grantedTrait(it) == leader }
                ?: false
        return learned || fromBackground
    }

    /**
     * The pre-hero behaviour, preserved exactly: a unit with any leader has both its rolled trait
     * and its class's signature trait (`docs/leaders.md` §1).
     *
     * ## The authored pair resolves here, and only here
     *
     * OG's Leader tab has two selectors and OSADA carries both:
     * [GameUnit.leader] is the INDIVIDUAL attribute (`.xscn` `@36`, the Suite's *"According list of
     * leaders"*) and [GameUnit.leaderClassTrait] is the CLASS attribute override (`@37`,
     * *"According unit's class"*). The second is folded into [Leaders.getUnitClassLeader] rather
     * than tested separately here, so the derived class attribute and the authored override reach
     * the ~10 combat call sites through the same expression and cannot diverge.
     *
     * **The two disjuncts must stay disjuncts.** They were briefly collapsed when the importer
     * deployed `@37` into [GameUnit.leader]: `||` meant the doubled value granted one trait instead
     * of two, so 52 formations silently lost the individual attribute they had been rolling.
     */
    private fun legacyHasTrait(
        unit: GameUnit,
        leader: LeaderType,
    ): Boolean {
        if (unit.leader == -1) return false
        return unit.leader == leader.value || leader.value == Leaders.getUnitClassLeader(unit)
    }
}
