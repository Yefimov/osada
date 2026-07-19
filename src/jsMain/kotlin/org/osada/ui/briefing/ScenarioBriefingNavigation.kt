package org.osada.ui.briefing

/** Dialogue-navigation half of [ScenarioBriefingController], split out to keep its
 *  function count in bounds. */
internal fun ScenarioBriefingController.currentLine(): BriefingLine? = briefing?.lineById(path.lastOrNull()?.lineId)

/** Every turn spoken so far along the CURRENT branch, in order: each visited line, and after any
 *  line the player answered, their chosen reply as its own turn. This is what the conversation
 *  log renders -- the player can scroll back through the whole exchange, and a branch taken
 *  earlier stays visible exactly as it played out. */
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

/** Entry point for every "advance" input (click/Enter/Space/ArrowRight): the FIRST such input
 *  while a line is still typing completes it instantly; only once fully revealed does a
 *  SECOND input move to the next line. Never advances past an unanswered choice. */
internal fun ScenarioBriefingController.advanceOrComplete() {
    if (stage != BriefingStage.DIALOGUE) return
    val lineEl = revealingLine
    if (lineEl != null && BriefingTypewriter.isRevealing()) {
        BriefingTypewriter.complete(lineEl)
    } else {
        nextLine()
    }
}

internal fun ScenarioBriefingController.nextLine() {
    val current = currentLine() ?: return showOrders()
    if (current.choices.isNotEmpty() && path.lastOrNull()?.selectedChoice == null) {
        view
            ?.root
            ?.querySelector(".osada-dialogue__choice")
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

internal fun ScenarioBriefingController.showOrders() {
    stage = BriefingStage.ORDERS
    renderCurrentStage()
    view?.beginButton?.focus()
}
