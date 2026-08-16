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

// The old free-standing hasAnyUnits()/hasUnitsOnMap()/etc. helpers that used to live here were
// replaced by SavePhaseValidation (see GameStatePersistence.kt's private hasAnyUnits, which now
// delegates to it) when the single-shared-key save became the per-campaign-run repository.
