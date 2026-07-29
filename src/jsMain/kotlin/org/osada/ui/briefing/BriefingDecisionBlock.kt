package org.osada.ui.briefing

import org.osada.i18n.I18n

// The "YOUR DECISION" block on the operational briefing: a choice the player skipped past in the
// conversation, rendered as A/B options that gate the BEGIN button. Split out of
// ScenarioBriefingBuilder to keep that object within the project's function-count limit, following
// the same sibling-file convention as the rest of this package.

/**
 * Renders a decision the player still owes an answer for at the top of the orders stage, and
 * disables BEGIN until it is made. Reached when SKIP (or Esc) jumped the conversation: skipping
 * the ceremony is fine, deciding by omission is not — choices commit prestige, resupply and
 * campaign routing.
 *
 * Pass a null [line] to clear the block and re-enable BEGIN.
 */
internal fun renderPendingDecision(
    view: ScenarioBriefingView,
    line: BriefingLine?,
    onChoice: (String) -> Unit,
) {
    val blocked = line != null
    view.beginButton.asDynamic().disabled = blocked
    view.beginButton.classList.toggle("osada-briefing__button--disabled", blocked)
    view.beginButton.setAttribute(
        "title",
        if (blocked) I18n.t("briefing.decision.required.help") else "",
    )
    if (line == null) return

    val sectionClass =
        "osada-briefing__order-section osada-briefing__order-section--wide osada-briefing__decision"
    val section = element("section", sectionClass)
    child(section, "h2", "osada-briefing__order-heading").textContent = I18n.t("briefing.decision.title")
    child(section, "p", "osada-briefing__order-text").textContent = plainText(line.text)
    val options = child(section, "div", "osada-briefing__decision-options")
    line.choices.forEachIndexed { index, choice ->
        options.appendChild(decisionOption(index, choice, onChoice))
    }
    // Ahead of the orders text: it is the one thing that must be answered before starting.
    view.ordersContent.insertBefore(section, view.ordersContent.firstChild)
}

private fun decisionOption(
    index: Int,
    choice: BriefingChoice,
    onChoice: (String) -> Unit,
) = element("button", "osada-briefing__decision-option").apply {
    asDynamic().type = "button"
    setAttribute(
        "aria-label",
        I18n.t("briefing.decision.option.aria", mapOf("number" to index + 1, "text" to choice.text)),
    )
    child(this, "span", "osada-briefing__decision-label").textContent = "${'A' + index}"
    child(this, "span", "osada-briefing__decision-text").textContent = choice.text
    val preview = BriefingChoicePreview.of(choice)
    if (preview.isNotBlank()) {
        child(this, "span", "osada-briefing__decision-hint").textContent = preview
        setAttribute("title", preview)
    }
    addEventListener("click", { e ->
        e.stopPropagation()
        onChoice(choice.id)
    })
}
