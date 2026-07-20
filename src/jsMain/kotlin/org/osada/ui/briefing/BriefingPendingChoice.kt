package org.osada.ui.briefing

// Carrying an unanswered dialogue decision to the operational briefing.
//
// SKIP (and Esc) drop the player at the briefing, but a decision they skipped past travels with
// them: pendingChoiceLine reports it, the orders stage renders it (renderPendingDecision), and
// BEGIN stays disabled until it is answered. Choices commit real consequences
// (CampaignNarrative.commitChoice -- prestige, resupply, campaign routing), so skipping may
// shorten the ceremony but must never decide by omission.
//
// Split out of ScenarioBriefingNavigation.kt to keep that file within the function-count limit.

/** Upper bound on lines a single skip may traverse. Cycle guard: authored `next` ids can point
 *  backwards, and a malformed campaign must not hang the UI. */
private const val MAX_SKIP_STEPS = 500

/**
 * Fast-forwards the conversation to the next UNANSWERED choice, or to the end of the branch if
 * there is none. Does not change [ScenarioBriefingController.stage] — the caller decides where the
 * player ends up.
 */
internal fun ScenarioBriefingController.advanceToNextPendingChoice() {
    var steps = 0
    while (steps < MAX_SKIP_STEPS) {
        steps++
        val current = currentLine()
        val step = path.lastOrNull()
        val stopHere = current != null && current.choices.isNotEmpty() && step?.selectedChoice == null
        val next = if (current == null || stopHere) null else resolveNext(current, step?.selectedChoice)
        if (next == null) return
        path += ScenarioBriefingController.DialogueStep(next.id)
    }
}

/** The line holding a choice the player still owes an answer for, or null when nothing is pending.
 *  Drives the orders-stage decision block and the BEGIN gate. */
internal fun ScenarioBriefingController.pendingChoiceLine(): BriefingLine? {
    val current = currentLine() ?: return null
    val answered = path.lastOrNull()?.selectedChoice != null
    return if (current.choices.isNotEmpty() && !answered) current else null
}

/** Answering a choice from the ORDERS stage: commit it, then walk on to the next pending decision
 *  (a conversation may hold several) and re-render so the block either advances or disappears. */
internal fun ScenarioBriefingController.chooseFromOrders(choiceId: String) {
    choose(choiceId)
    stage = BriefingStage.ORDERS
    advanceToNextPendingChoice()
    renderCurrentStage()
}
