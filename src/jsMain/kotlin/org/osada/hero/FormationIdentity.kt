package org.osada.hero

import org.osada.hero.FormationIdentity.nextFor
import org.osada.model.GameUnit

/**
 * Mints and reads the stable [FormationId] carried by campaign core units.
 *
 * ## Why ids are minted rather than derived
 *
 * There is no existing field on a core unit that is both stable and unique. `GameUnit.id` is
 * reassigned when a scenario loads, `eqid` changes on upgrade, and two identical units bought the
 * same turn are indistinguishable by their stats. So the id has to be assigned once, at the moment
 * a unit joins the core roster, and then carried in the save forever.
 *
 * ## Format
 *
 * `F-<owner>-<counter>` — readable in a hand-edited save and cheap to scan. The counter is
 * per-roster rather than global so ids stay short and stable; [nextFor] seeds it past every id
 * already present, which is what makes minting safe after a load (a restored roster holding
 * `F-0-7` will not hand out `F-0-3` again).
 */
internal object FormationIdentity {
    private const val PREFIX = "F-"

    /** The formation id already on [unit], or null for a unit that has never been core. */
    fun of(unit: GameUnit): FormationId? = unit.formationId?.takeIf { it.isNotEmpty() }?.let { FormationId(it) }

    /**
     * Assigns a formation id to [unit] if it has none, and returns the id either way.
     *
     * Idempotent by design: this is called from `Player.addCoreUnit`, which runs again on every
     * scenario load when the core roster is rebuilt. Re-minting there would break the one property
     * the whole system depends on — that the id survives the transition.
     */
    fun ensure(
        unit: GameUnit,
        existing: Collection<String>,
    ): FormationId {
        of(unit)?.let { return it }
        val minted = nextFor(unit.owner, existing)
        unit.formationId = minted.value
        return minted
    }

    /** Lowest unused `F-<owner>-<n>` given the ids already in use. */
    fun nextFor(
        owner: Int,
        existing: Collection<String>,
    ): FormationId {
        val prefix = "$PREFIX$owner-"
        val highest =
            existing
                .filter { it.startsWith(prefix) }
                .mapNotNull { it.removePrefix(prefix).toIntOrNull() }
                .maxOrNull() ?: 0
        return FormationId("$prefix${highest + 1}")
    }
}
