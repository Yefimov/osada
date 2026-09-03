package org.osada

import org.osada.model.Hex
import org.osada.rules.Engineering

/**
 * The per-hex half of a save restore: OG 9.3's engineering state, the authored map flags that
 * travel with it, and the trigger hex.
 *
 * Split out of `GameStateRestore.kt` for detekt's per-file function budget, which that file was
 * already over. The grouping is the one `GameStateSerializer.serializeHexEngineering` and
 * `serializeHexExits` use on the writing side, so the pair that has to agree about key names now
 * sits one file apart rather than buried at opposite ends of two large ones.
 *
 * Every field is an optional save key that defaults to "nothing here", and [restoreEngineering] is
 * `internal` rather than private so `OgOptionalRulesTest` can round-trip a job through
 * `serializeHex` and back: the pair is the thing worth locking, and asserting on the emitted JSON
 * alone would not catch a reader that stopped reading a key the writer still writes.
 */

internal fun restoreEngineering(
    hex: Hex,
    hexData: dynamic,
) {
    // Written as a name since 2026-08-25 (see the serializer). An unknown name -- a job this
    // build does not have -- restores as "nothing in progress" rather than as job zero, which is
    // the whole reason the format is a name.
    hex.construction = Engineering.workOrdinal(hexData.construction as? String)
    hex.constructionTurns = hexData.constructionTurns as? Int ?: 0
    hex.constructionSide = hexData.constructionSide as? Int ?: -1
    // Absent in saves written before 2026-08-26; -1 is "builder unknown", which is what
    // `Engineering.advanceTurn` falls back to `constructionSide` for.
    hex.constructionPlayer = hexData.constructionPlayer as? Int ?: -1
    hex.constructionCountry = hexData.constructionCountry as? Int ?: -1
    hex.razedTerrain = hexData.razedTerrain as? Int ?: -1
    hex.blownRoad = hexData.blownRoad as? Int ?: 0
    hex.sapperBuilt = (hexData.sapperBuilt as? Int ?: 0) != 0
    hex.station = (hexData.station as? Int ?: 0) != 0
    hex.dirt = (hexData.dirt as? Int ?: 0) != 0
    // Absent in saves written before 2026-09-02, where `false` is the state they loaded in with
    // anyway -- the scenario's exits were already gone by the time the save was taken.
    hex.escapeGround = (hexData.escapeGround as? Int ?: 0) != 0
    hex.escapeAir = (hexData.escapeAir as? Int ?: 0) != 0
    restoreHexTrigger(hex, hexData)
    hex.rubble = (hexData.rubble as? Int ?: 0) != 0
    hex.crater = (hexData.crater as? Int ?: 0) != 0
}

/**
 * An OG trigger hex's four authored fields plus its live fired flag, split from
 * [restoreEngineering] to keep that function inside detekt's complexity budget.
 *
 * The authored half travels so a save does not disarm the hex; `triggerFired` travels so a reload
 * does not re-arm one the player already spent (`rules/TriggerHexes`).
 */
private fun restoreHexTrigger(
    hex: Hex,
    hexData: dynamic,
) {
    hex.trigger = hexData.trigger as? Int ?: 0
    hex.triggerParam = hexData.triggerParam as? Int ?: 0
    hex.triggerEquip = hexData.triggerEquip as? Int ?: 0
    hex.triggerMessage = hexData.triggerMessage as? String ?: ""
    hex.triggerFired = (hexData.triggerFired as? Int ?: 0) != 0
}
