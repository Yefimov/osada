package org.osada.hero

/**
 * Stable identity of a persistent core FORMATION (design brief §9.1, §18).
 *
 * The formation — not the [org.osada.model.GameUnit] instance — is what survives an equipment
 * upgrade and a scenario transition. Before this existed the campaign core roster was matched by
 * nothing stable at all: `GameUnit.id` is reassigned per scenario and `eqid` changes on upgrade,
 * so there was no way to say "this is the same brigade as last battle".
 *
 * The wire form is a plain string on `GameUnit.formationId` so the save file stays readable and
 * hand-editable; this wrapper exists to keep formation ids from being confused with unit ids or
 * hero ids inside the domain, where all three are strings.
 */
data class FormationId(
    val value: String,
)

/** Stable identity of a hero across scenarios and formations (design brief §17). */
data class HeroId(
    val value: String,
)
