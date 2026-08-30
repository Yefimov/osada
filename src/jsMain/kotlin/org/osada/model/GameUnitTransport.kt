package org.osada.model

import org.osada.UnitClass
import org.osada.rules.TransportWeights

/** Transport/mount/embark actions for [GameUnit], split out to keep its function count in bounds. */
fun GameUnit.setTransport(eqid: Int) {
    transport = Transport(eqid)
}

fun GameUnit.mount() {
    isMounted = true
}

fun GameUnit.unmount() {
    isMounted = false
}

/**
 * Put this formation aboard a non-organic transport of [carrierClass].
 *
 * **The transport is chosen by WEIGHT COMPATIBILITY, not by roster order** (2026-08-30). This used
 * to take `firstOrNull()` — the first record of the right class the country happened to define —
 * which ignored OG's weight masks entirely because nothing had established which field the
 * transport side reads. `rules/TransportWeights` now answers that: both sides read the same
 * per-type field, and a zero on either side means "unrestricted".
 *
 * The first COMPATIBLE record is taken, and the roster's own order still decides among equals —
 * OG does not publish which of several eligible transports it picks, so the tie-break is left
 * exactly where it was rather than replaced with a second invented rule.
 *
 * Falls back to the first record of the class when nothing is compatible. That is deliberate and
 * permissive: refusing the embarkation outright would strand a formation on a rule this project
 * has only just learned, and the weights are dense enough that a false refusal is the likelier
 * error. See `docs/og-open-questions.md` Q1.3.
 */
fun GameUnit.embark(carrierClass: UnitClass): Boolean {
    val candidates = Equipment.getCountryEquipmentByClass(carrierClass, (player?.country ?: 0) + 1)
    val cargo = unitData(true)
    val eqid =
        candidates.firstOrNull { candidate ->
            val transport = Equipment.equipment[candidate]
            transport != null && TransportWeights.compatible(cargo, transport, carrierClass.value)
        } ?: candidates.firstOrNull() ?: return false
    carrier = eqid
    return true
}

fun GameUnit.toggleEmbark() {
    carrier = -carrier
}
