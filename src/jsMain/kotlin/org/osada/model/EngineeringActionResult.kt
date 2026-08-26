package org.osada.model

/**
 * What a Build/Repair/Demolish order actually did, for the caller's message.
 *
 * In its own file rather than beside [beginEngineering] so the file name matches the declaration,
 * the same shape `MineActionResult` already has next to `MinefieldOperations`.
 */
internal enum class EngineeringActionResult {
    NOT_ALLOWED,

    /** An instant demolition: the bridge or terrain feature is already gone. */
    DEMOLISHED,

    /** A multi-turn job is now under way on this hex. */
    STARTED,
}
