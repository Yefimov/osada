package org.osada.ui.briefing

import kotlinx.browser.document
import org.w3c.dom.events.Event

/** Keyboard/focus half of [ScenarioBriefingController], split out to keep its
 *  function count in bounds. */
internal fun ScenarioBriefingController.handleKeyDown(event: Event) {
    val e = event.asDynamic()
    val key = e.key as? String
    if (key == null || handleTabKeyDown(key, e)) return
    val tagName = (e.target?.tagName as? String)?.uppercase()
    if (isNonNavigableTarget(tagName) || handleChoiceDigitKey(key, e) || handleChoiceArrowKey(key, e)) return
    handleNavigationKey(key, e)
}

private fun isNonNavigableTarget(tagName: String?): Boolean = isTypingField(tagName) || tagName == "BUTTON"

private fun ScenarioBriefingController.handleTabKeyDown(
    key: String,
    e: dynamic,
): Boolean {
    if (key != "Tab") return false
    trapFocus(e)
    return true
}

/** Digit 1-9 selects the matching dialogue choice, if one exists at that index. Ignored while
 *  the line is still typewriter-revealing -- the first input completes the reveal instead. */
private fun ScenarioBriefingController.handleChoiceDigitKey(
    key: String,
    e: dynamic,
): Boolean {
    val isDigitChoiceKey =
        stage == BriefingStage.DIALOGUE &&
            !BriefingTypewriter.isRevealing() &&
            key.length == 1 &&
            key[0] in '1'..'9'
    val choice = if (isDigitChoiceKey) currentLine()?.choices?.getOrNull(key[0].digitToInt() - 1) else null
    if (choice != null) {
        e.preventDefault()
        choose(choice.id)
    }
    return choice != null
}

private val CHOICE_CYCLE_KEYS = setOf("ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight")

private fun ScenarioBriefingController.handleNavigationKey(
    key: String,
    e: dynamic,
) {
    when (key) {
        "Enter", " ", "ArrowRight" ->
            if (stage == BriefingStage.DIALOGUE) {
                e.preventDefault()
                advanceOrComplete()
            }
        "Escape" -> {
            e.preventDefault()
            e.stopPropagation()
            // Esc out of the conversation behaves exactly like SKIP: it may shorten the ceremony
            // but must carry any unanswered decision to the orders stage rather than dropping it.
            if (stage == BriefingStage.DIALOGUE) skipToNextChoiceOrOrders() else finishBriefing()
        }
    }
}

/** Arrow keys cycle focus among the visible dialogue-choice buttons (wrapping); does nothing
 *  and defers to normal navigation when focus isn't currently on a choice button. */
private fun ScenarioBriefingController.handleChoiceArrowKey(
    key: String,
    e: dynamic,
): Boolean {
    val choices = if (key in CHOICE_CYCLE_KEYS) activeChoiceButtons() else null
    val length = (choices?.length as? Int) ?: 0
    val activeIndex = if (choices != null && length > 0) indexOfActiveChoice(choices) else -1
    if (activeIndex < 0) return false

    val forward = key == "ArrowDown" || key == "ArrowRight"
    val nextIndex = if (forward) (activeIndex + 1) % length else (activeIndex - 1 + length) % length
    e.preventDefault()
    val nextChoice: dynamic = choices.item(nextIndex)
    nextChoice.focus()
    return true
}

private fun ScenarioBriefingController.activeChoiceButtons(): dynamic =
    if (stage == BriefingStage.DIALOGUE) {
        view?.root?.querySelectorAll(".osada-dialogue__choice")?.asDynamic()
    } else {
        null
    }

private fun indexOfActiveChoice(choices: dynamic): Int {
    val active = document.activeElement
    val length = (choices.length as? Int) ?: 0
    for (i in 0 until length) {
        if (choices.item(i) == active) return i
    }
    return -1
}

private fun ScenarioBriefingController.trapFocus(event: dynamic) {
    val root = view?.root ?: return
    val nodes = root.querySelectorAll("button:not([disabled]), [tabindex='0']").asDynamic()
    val length = (nodes.length as? Int) ?: 0
    if (length == 0) return

    val first: dynamic = nodes.item(0)
    val last: dynamic = nodes.item(length - 1)
    val active = document.activeElement
    val shift = event.shiftKey as? Boolean ?: false
    if (shift && active == first) {
        event.preventDefault()
        last.focus()
    } else if (!shift && active == last) {
        event.preventDefault()
        first.focus()
    }
}

/** Ensures keyboard focus is somewhere inside the briefing so its keydown listener (bound to
 *  the root, which only receives bubbled events from its own focused subtree) keeps working.
 *  Once a line finishes revealing, the choices' onDone callback focuses the first choice
 *  button directly, superseding this. */
internal fun ScenarioBriefingController.focusPrimaryControl() {
    val currentView = view ?: return
    if (stage == BriefingStage.ORDERS) {
        currentView.beginButton.focus()
    } else {
        currentView.root.focus()
    }
}

private fun isTypingField(tagName: String?): Boolean =
    tagName == "INPUT" || tagName == "TEXTAREA" || tagName == "SELECT"
