package org.osada.ui

import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.getAttackableUnit
import org.osada.multiplayer.client.OsadaMultiplayer

/** The local selected attacker is prospective until its first order; defenders stay independent. */
internal fun Hex.previewAttackableUnit(
    attacker: GameUnit,
    airMode: Boolean,
): GameUnit? = OsadaMultiplayer.preview(attacker) { getAttackableUnit(attacker, airMode) }
