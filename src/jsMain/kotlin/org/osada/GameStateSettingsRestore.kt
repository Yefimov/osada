package org.osada

private const val DEFAULT_UI_SIZE = 840
private const val DEFAULT_UI_SMALL_SIZE = 470
private const val DEFAULT_SOUND_VOLUME = 0.5
private const val DEFAULT_AMBIENT_VOLUME = 0.4

/** [GameStateRestore.applySettings] sub-steps, split out to keep the file's function count in bounds. */
internal fun applyDisplaySettings(data: dynamic) {
    applyZoomSettings(data)
    applySoundSettings(data)
    uiSettings.hexGrid = data.hexGrid as? Boolean ?: false
    uiSettings.showGridTerrain = data.showGridTerrain as? Boolean ?: false
    uiSettings.deployMode = data.deployMode as? Boolean ?: false
}

private fun applyZoomSettings(data: dynamic) {
    uiSettings.airMode = data.airMode as? Boolean ?: false
    uiSettings.strategicZoom = data.strategicZoom as? Boolean ?: false
    uiSettings.strategicZoomLevel = (data.strategicZoomLevel as? Number)?.toDouble() ?: 1.0
    uiSettings.mapZoom = data.mapZoom as? Boolean ?: false
    uiSettings.zoomLevel = (data.zoomLevel as? Number)?.toDouble() ?: 1.0
    uiSettings.uiScale = (data.uiScale as? Number)?.toDouble() ?: 1.0
    uiSettings.uiSize = (data.uiSize as? Number)?.toInt() ?: DEFAULT_UI_SIZE
    uiSettings.uiSmallSize = (data.uiSmallSize as? Number)?.toInt() ?: DEFAULT_UI_SMALL_SIZE
}

private fun applySoundSettings(data: dynamic) {
    uiSettings.muteUnitSounds = data.muteUnitSounds as? Boolean ?: false
    uiSettings.soundVolume = (data.soundVolume as? Number)?.toDouble() ?: DEFAULT_SOUND_VOLUME
    uiSettings.ambientVolume = (data.ambientVolume as? Number)?.toDouble() ?: DEFAULT_AMBIENT_VOLUME
}

internal fun applyMarkerSettings(data: dynamic) {
    uiSettings.markCombatUnits = data.markCombatUnits as? Boolean ?: true
    uiSettings.markOwnUnits = data.markOwnUnits as? Boolean ?: false
    uiSettings.markEnemyUnits = data.markEnemyUnits as? Boolean ?: false
    uiSettings.markFOW = data.markFOW as? Boolean ?: false
    uiSettings.noFOW = data.noFOW as? Boolean ?: false
    uiSettings.quickAnimation = data.quickAnimation as? Boolean ?: false
    uiSettings.hasTouch = data.hasTouch as? Boolean ?: false
    uiSettings.use3D = data.use3D as? Boolean ?: false
    uiSettings.useRetina = data.useRetina as? Boolean ?: false
    uiSettings.allowZoom = data.allowZoom as? Boolean ?: false
    uiSettings.shownEndTurnTip = data.shownEndTurnTip as? Boolean ?: false
}

internal fun applyMiscSettings(data: dynamic) {
    // Always restore as pinned-on: older builds cleared this flag as a deselect side effect
    // and persisted it, which made the unit card permanently invisible on restored saves.
    // The flag is a session-level Inspect toggle now, not a durable preference.
    uiSettings.unitInfoVisibility = true
    uiSettings.showInfoToolTips = data.showInfoToolTips as? Boolean ?: true
    uiSettings.showDetailInfoToolTips = data.showDetailInfoToolTips as? Boolean ?: false
    // Fallback FALSE, matching UiSettings' own field default: `?: true` meant any settings
    // blob missing this key (older saves) silently re-enabled observer mode on every load.
    uiSettings.showHiddenVictoryHexes = data.showHiddenVictoryHexes as? Boolean ?: false
    uiSettings.confirmEndTurn = data.confirmEndTurn as? Boolean ?: true
    uiSettings.stalinRegime = data.stalinRegime as? Boolean ?: false
}

internal fun applySettingsIsAI(data: dynamic) {
    val isAI = data.isAI
    if (isAI != null) {
        val isAILength = isAI.length as? Int ?: 0
        console.log("[osada] applySettings isAI type:", js("typeof isAI"), "length:", isAILength)
        for (i in 0 until minOf(isAILength, uiSettings.isAI.size)) {
            uiSettings.isAI[i] = isAI[i] as? Int ?: 0
        }
    } else {
        console.log("[osada] applySettings isAI is null")
    }
}
