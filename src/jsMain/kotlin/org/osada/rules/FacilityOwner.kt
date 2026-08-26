package org.osada.rules

/**
 * Who a newly built facility belongs to, for [Engineering].
 *
 * A pair rather than a `Player` reference so the rules layer stays free of the model's player
 * object, and so a completion that happens with no identifiable builder has an explicit,
 * inert value ([NONE]) instead of a null nobody checks.
 *
 * **`flag` is the field that actually decides whether a facility works.**
 * `MovementRules.hasAirfield` compares `hex.flag` against the unit's `country`, so an airfield
 * with no flag is scenery. `owner` is set alongside it because that is the pair the rest of the
 * engine treats as ownership — `CombatApplication.applyHexCapture` writes both — which is also
 * what lets the opponent take a built facility the ordinary way rather than needing a rule of
 * its own.
 */
internal class FacilityOwner(
    val playerId: Int,
    val country: Int,
) {
    companion object {
        /** No identifiable builder: the facility is completed but claimed by nobody. */
        val NONE = FacilityOwner(-1, -1)
    }
}
