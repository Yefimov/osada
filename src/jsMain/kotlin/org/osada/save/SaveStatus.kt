package org.osada.save

/** Quiet save-state surfaced by the pause menu / campaign register row, per
 *  `docs/design/save-recovery.md` section 6: `Saving...` / `Saved HH:MM` / `Save failed`. */
sealed class SaveStatus {
    object Idle : SaveStatus()

    object Saving : SaveStatus()

    data class Saved(
        val atMillis: Double,
    ) : SaveStatus()

    data class Failed(
        val message: String,
    ) : SaveStatus()
}

/** Tiny observable so `GameStatePersistence` (which has no UI dependency) can report status and
 *  a UI presenter (added in a later change) can subscribe without either side importing the other. */
object SaveStatusBus {
    var current: SaveStatus = SaveStatus.Idle
        private set

    private var listener: ((SaveStatus) -> Unit)? = null

    fun setListener(onChange: ((SaveStatus) -> Unit)?) {
        listener = onChange
        onChange?.invoke(current)
    }

    fun update(status: SaveStatus) {
        current = status
        listener?.invoke(status)
    }
}
