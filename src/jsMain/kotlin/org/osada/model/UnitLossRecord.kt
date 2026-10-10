package org.osada.model

import org.osada.GameHolder
import org.osada.getCampaignPlayer
import org.osada.hero.HeroCampaign
import org.osada.rules.PurchaseCap

/**
 * Everything a lost unit is booked as, whether it died on the map or inside a container that was
 * lost ([updateUnitList]). [unit] must already be marked destroyed.
 */
internal fun GameMap.recordLoss(unit: GameUnit) {
    // `nodossier` means exactly what its name says: explicitly omit this unit. The old
    // inverted check recorded only omitted units, leaving normal campaign losses at zero.
    if (recordsInCampaignDossier(GameHolder.instance?.campaign != null, unit.nodossier)) {
        GameHolder.instance?.getCampaignPlayer()?.addDestroyedUnitToDossier(unit)
    }
    // The commander's fate, for every loss this sweep sees -- not only the ones a combat
    // exchange processed while the unit was still alive (`HeroCampaign.recordCombat`). A
    // minefield, an AA gun, counterbattery fire or a forced retreat onto mines used to leave
    // the commander ACTIVE on a formation that no longer existed. Idempotent: a casualty
    // already resolved has detached its commander, so a second call finds nobody.
    // `nodossier` removals are not losses -- a disbanded unit, a scenario event's conversion,
    // a non-campaign unit cleared off a deployment hex -- and never roll a casualty.
    if (!unit.nodossier) HeroCampaign.recordCasualty(unit, turn)
    // OG's purchase cap mints a REPLACEMENT CREDIT here, for the same reason `creditKill`
    // counts here: this is the one sweep every loss passes through, whatever caused it
    // (`rules/PurchaseCap`).
    PurchaseCap.creditReplacement(unit)
    creditKill(unit)
}

/**
 * OG's *"kill N enemy units"* victory condition (`@1021` bit 4, 55 corpus scenarios).
 *
 * Counted in [updateUnitList] because that is the one sweep every death passes through, whatever
 * killed it -- combat, a minefield, surrender, running out of fuel. The credit goes to the OTHER
 * side, so a self-inflicted loss (kamikaze, disband) credits nobody.
 */
private fun creditKill(unit: GameUnit) {
    val victimSide = unit.player?.side ?: return
    val scenario = GameHolder.instance?.scenario ?: return
    scenario.unitsKilled.indices
        .filter { it != victimSide }
        .forEach { scenario.unitsKilled[it] = scenario.unitsKilled[it] + 1 }
}
