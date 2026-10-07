package org.osada

/** A restarted mission has the same file name but a different scenario instance. */
internal fun Game.forCurrentScenario(action: () -> Unit): () -> Unit {
    val origin = scenario
    return {
        if (origin != null && scenario === origin) action()
    }
}
