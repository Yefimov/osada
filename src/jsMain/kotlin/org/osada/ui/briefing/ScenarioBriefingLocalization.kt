package org.osada.ui.briefing

import org.osada.i18n.I18n

// The staff-table photo the main menu uses, so an operation with no authored art of its own
// opens on the same desk the player just left.
private const val DEFAULT_BACKGROUND = "resources/staff_table_background.png"

internal fun ScenarioBriefingController.renderLocalizedChrome(
    currentView: ScenarioBriefingView,
    parsed: ScenarioBriefing,
    scenarioFacts: ScenarioFacts,
) {
    currentView.title.textContent = parsed.title
    currentView.subtitle.textContent = "${parsed.actLabel} · ${parsed.locationLabel}"
    currentView.headerDate.textContent = scenarioFacts.dateLabel
    currentView.beginButton.textContent = I18n.t(beginLabelKey)
    val background = parsed.background?.takeIf { it.isNotBlank() } ?: DEFAULT_BACKGROUND
    currentView.backdrop.style.backgroundImage = "url(\"$background\")"
    currentView.skipButton.textContent = I18n.t("briefing.skip.label")
    currentView.skipButton.title = I18n.t("briefing.skip.help")
    currentView.hint.textContent = I18n.t("briefing.continue.label")
}

internal fun ScenarioBriefingController.refreshLocalization() {
    // One guard rather than three `?: return`s: detekt's ReturnCount limit is 2, and a suppression
    // here would join the eight already tracked as debt in `DEFERRED.md` 4.1 for no gain.
    val source = currentSource
    val currentFacts = facts
    val currentView = view
    if (source == null || currentFacts == null || currentView == null) return
    val selectedChoices = path.associate { it.lineId to it.selectedChoice?.id }
    val localized = BriefingLocalization.parse(source)
    val localizedFacts = BriefingLocalization.localizeFacts(source, currentFacts)
    path.forEach { step ->
        val choiceId = selectedChoices[step.lineId]
        step.selectedChoice = localized.lineById(step.lineId)?.choices?.firstOrNull { it.id == choiceId }
    }
    BriefingTypewriter.cancel()
    revealingLine = null
    renderedTurns = emptyList()
    briefing = localized
    facts = localizedFacts
    renderLocalizedChrome(currentView, localized, localizedFacts)
    renderCurrentStage()
}
