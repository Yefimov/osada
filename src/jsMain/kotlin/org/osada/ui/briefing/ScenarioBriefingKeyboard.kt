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
    if (isTypingField(tagName) || handleChoiceDigitKey(key, e) || tagName == "BUTTON") return
    handleNavigationKey(key, e)
}

private fun ScenarioBriefingController.handleTabKeyDown(
    key: String,
    e: dynamic,
): Boolean {
    if (key != "Tab") return false
    trapFocus(e)
    return true
}

/** Digit 1-9 selects the matching dialogue choice, if one exists at that index. */
private fun ScenarioBriefingController.handleChoiceDigitKey(
    key: String,
    e: dynamic,
): Boolean {
    val isDigitChoiceKey = stage == BriefingStage.DIALOGUE && key.length == 1 && key[0] in '1'..'9'
    val choice = if (isDigitChoiceKey) currentLine()?.choices?.getOrNull(key[0].digitToInt() - 1) else null
    if (choice != null) {
        e.preventDefault()
        choose(choice.id)
    }
    return choice != null
}

private fun ScenarioBriefingController.handleNavigationKey(
    key: String,
    e: dynamic,
) {
    when (key) {
        "Enter", " ", "ArrowRight" ->
            if (stage == BriefingStage.DIALOGUE) {
                e.preventDefault()
                nextLine()
            }
        "ArrowLeft" ->
            if (stage == BriefingStage.DIALOGUE) {
                e.preventDefault()
                previousLine()
            }
        "Escape" -> {
            e.preventDefault()
            e.stopPropagation()
            if (stage == BriefingStage.DIALOGUE) showOrders()
        }
    }
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

internal fun ScenarioBriefingController.focusPrimaryControl() {
    val currentView = view ?: return
    if (stage == BriefingStage.ORDERS) {
        currentView.beginButton.focus()
        return
    }
    val firstChoice = currentView.root.querySelector(".osada-conversation__choice")?.asDynamic()
    if (firstChoice != null && firstChoice != undefined) firstChoice.focus() else currentView.nextButton.focus()
}

private fun isTypingField(tagName: String?): Boolean =
    tagName == "INPUT" || tagName == "TEXTAREA" || tagName == "SELECT"
