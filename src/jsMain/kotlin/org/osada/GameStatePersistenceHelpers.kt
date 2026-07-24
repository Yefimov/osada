package org.osada

fun GameStatePersistence.restoreFromFile(
    file: dynamic,
    onSuccess: () -> Unit,
    onError: () -> Unit,
) {
    val reader = js("new FileReader()")
    reader.onloadend = {
        val text = reader.result as String
        if (!restoreFromString(text)) onError() else onSuccess()
    }
    reader.readAsText(file)
}

/** True if the saved state represents a real game in progress: at least one unit on the map,
 *  one pending reinforcement, or one core unit in a player's roster (a deploy-phase game keeps
 *  its core in reserve, so map-only counting would wrongly reject it). A save with none of these
 *  is stale/empty and should not be auto-restored. */
internal fun GameStatePersistence.hasAnyUnits(
    scenarioData: dynamic,
    playersData: dynamic,
): Boolean = hasUnitsOnMap(scenarioData) || hasPendingReinforcements(scenarioData) || hasCoreUnits(playersData)

private fun hasUnitsOnMap(scenarioData: dynamic): Boolean {
    val hexes = scenarioData.map?.hexes
    val rows = (hexes?.length as? Int) ?: 0
    return hexes != null && (0 until rows).any { r -> rowHasUnit(hexes[r]) }
}

private fun rowHasUnit(row: dynamic): Boolean {
    val cols = (row?.length as? Int) ?: 0
    return row != null && (0 until cols).any { c -> cellHasUnit(row[c]) }
}

private fun cellHasUnit(hex: dynamic): Boolean = hex != null && (hex.unit != null || hex.airunit != null)

private fun hasPendingReinforcements(scenarioData: dynamic): Boolean {
    val reinf = scenarioData.reinforcements
    return reinf != null && (js("Object.keys(reinf).length") as? Int ?: 0) > 0
}

private fun hasCoreUnits(playersData: dynamic): Boolean {
    val playerCount = (playersData.length as? Int) ?: 0
    return (0 until playerCount).any { i -> playerHasCore(playersData[i]) }
}

private fun playerHasCore(player: dynamic): Boolean {
    val core = player?.coreUnits
    return core != null && (core.length as? Int ?: 0) > 0
}
