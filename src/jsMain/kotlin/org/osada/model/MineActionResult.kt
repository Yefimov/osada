package org.osada.model

/**
 * What a minefield command actually did, so the caller reports the outcome rather than assuming it
 * (`model/MinefieldOperations.kt`, OG manual 9.9).
 *
 * [FAILED_ATTEMPT] is the reason this is an enum rather than a Boolean: OG's clearing attempt *"can
 * fail, and a failed attempt suppresses the unit"*, so "nothing was cleared" and "nothing was
 * allowed" are different sentences to the player and must stay distinguishable.
 */
enum class MineActionResult {
    LAID,
    CLEARED,
    FAILED_ATTEMPT,
    NOT_ALLOWED,
}
