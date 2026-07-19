package org.osada.ui.briefing

/** Dialogue-navigation half of [ScenarioBriefingController], split out to keep its
 *  function count in bounds. */
internal fun ScenarioBriefingController.buildTurns(data: ScenarioBriefing): List<DialogueTurn> {
    val turns = mutableListOf<DialogueTurn>()
    path.forEach { step ->
        val line = data.lineById(step.lineId) ?: return@forEach
        turns += DialogueTurn(line.participant(), line.text)
        step.selectedChoice?.let { selected ->
            turns += DialogueTurn(data.player, selected.text, isPlayerResponse = true)
        }
    }
    return turns
}

internal fun ScenarioBriefingController.currentLine(): BriefingLine? = briefing?.lineById(path.lastOrNull()?.lineId)

internal fun ScenarioBriefingController.nextLine() {
    val current = currentLine() ?: return showOrders()
    if (current.choices.isNotEmpty() && path.lastOrNull()?.selectedChoice == null) {
        view
            ?.root
            ?.querySelector(".osada-conversation__choice")
            ?.asDynamic()
            ?.focus()
        return
    }
    advanceTo(resolveNext(current, path.lastOrNull()?.selectedChoice))
}

internal fun ScenarioBriefingController.choose(choiceId: String) {
    val current = currentLine() ?: return
    val choice = current.choices.firstOrNull { it.id == choiceId } ?: return
    path.lastOrNull()?.selectedChoice = choice
    val next = resolveNext(current, choice)
    if (next == null) {
        renderCurrentStage()
        focusPrimaryControl()
    } else {
        path += ScenarioBriefingController.DialogueStep(next.id)
        renderCurrentStage()
        focusPrimaryControl()
    }
}

internal fun ScenarioBriefingController.resolveNext(
    current: BriefingLine,
    choice: BriefingChoice?,
): BriefingLine? {
    val data = briefing ?: return null
    val targetId = choice?.next ?: current.next
    return if (targetId != null) data.lineById(targetId) else data.nextSequential(current)
}

internal fun ScenarioBriefingController.advanceTo(next: BriefingLine?) {
    if (next == null) {
        showOrders()
        return
    }
    path += ScenarioBriefingController.DialogueStep(next.id)
    renderCurrentStage()
    focusPrimaryControl()
}

internal fun ScenarioBriefingController.previousLine() {
    if (stage != BriefingStage.DIALOGUE || path.size <= 1) return
    path.removeAt(path.lastIndex)
    path.lastOrNull()?.let { previous ->
        if (previous.selectedChoice != null) previous.selectedChoice = null
    }
    renderCurrentStage()
    focusPrimaryControl()
}

internal fun ScenarioBriefingController.showOrders() {
    stage = BriefingStage.ORDERS
    renderCurrentStage()
    view?.beginButton?.focus()
}

internal fun ScenarioBriefingController.reviewDialogue() {
    val first = briefing?.dialogue?.firstOrNull() ?: return
    path.clear()
    path += ScenarioBriefingController.DialogueStep(first.id)
    stage = BriefingStage.DIALOGUE
    renderCurrentStage()
    focusPrimaryControl()
}
