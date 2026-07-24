package org.osada.model

import org.osada.UnitClass
import org.osada.outcomeNames
import kotlin.js.json

/** Dossier bookkeeping for [Player], split out to keep its function count in bounds. */
fun Player.initDossier() {
    val lostaux = json()
    val lostcore = json()
    val killed = json()
    UnitClass.entries.forEach { uc ->
        val key = uc.value.toString()
        lostaux[key] = 0
        lostcore[key] = 0
        killed[key] = 0
    }
    val units =
        json(
            Pair("lostaux", lostaux),
            Pair("lostcore", lostcore),
            Pair("killed", killed),
        )
    val outcomes = json()
    outcomeNames.keys.forEach { outcomes[it] = js("[]") }
    dossier = json(Pair("units", units), Pair("outcomes", outcomes))
}

fun Player.copyDossier(other: Player) {
    initDossier()
    val otherDossier = other.dossier ?: return
    UnitClass.entries.forEach { uc ->
        val key = uc.value.toString()
        dossier.units.lostaux[key] = otherDossier.units.lostaux[key]
        dossier.units.lostcore[key] = otherDossier.units.lostcore[key]
        dossier.units.killed[key] = otherDossier.units.killed[key]
    }
    outcomeNames.keys.forEach { outcome ->
        val src = otherDossier.outcomes[outcome]
        if (src != null) {
            val dst = dossier.outcomes[outcome]
            for (i in 0 until src.length) {
                dst.push(src[i])
            }
        }
    }
}

fun Player.addDestroyedUnitToDossier(unit: GameUnit) {
    if (dossier == null || dossier.units == undefined) initDossier()
    val uclass = unit.unitData().uclass.toString()
    if (id == unit.player?.id) {
        if (unit.isCore) {
            dossier.units.lostcore[uclass] = (dossier.units.lostcore[uclass] as? Int ?: 0) + 1
        } else {
            dossier.units.lostaux[uclass] = (dossier.units.lostaux[uclass] as? Int ?: 0) + 1
        }
    } else {
        dossier.units.killed[uclass] = (dossier.units.killed[uclass] as? Int ?: 0) + 1
    }
}

fun Player.addOutcomeToDossier(
    outcome: String,
    scenarioName: String,
) {
    if (dossier == null || dossier.outcomes == undefined) initDossier()
    dossier.outcomes[outcome].push(scenarioName)
}
