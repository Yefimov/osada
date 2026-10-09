package org.osada

/**
 * The end of a battle's opening (`UI.releaseToBattle`, after briefing choices and `start` events):
 * the Restart Mission checkpoint is taken without the pending marker, and the autosave is brought up
 * to it at once. Until then the autosave was the pre-briefing one, so a reload before the next save
 * replayed the briefing over a checkpoint that already held its choices; now both agree, and the
 * choices are on disk with the opening.
 *
 * A REPLAYED opening (`Game.openingReplay`) replaces any checkpoint already stored for the mission:
 * that one came from an earlier completion the restored save no longer reflects, and the player may
 * just have chosen differently (`MissionRestartCheckpoint.capture`). Ordinary play keeps the first
 * capture immutable.
 */
internal fun Game.completeOpening(): MissionRestartCheckpoint.Outcome {
    val wasPending = openingPending
    val replayed = openingReplay
    openingPending = false
    openingReplay = false
    val outcome = missionRestartCheckpoint.capture(replaceExisting = replayed)
    if (wasPending) state?.save()
    return outcome
}
