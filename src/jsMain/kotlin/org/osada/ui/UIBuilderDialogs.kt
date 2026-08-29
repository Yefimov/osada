package org.osada.ui

import org.osada.ui.briefing.ScenarioBriefingController
import org.osada.ui.briefing.ScenarioFacts

// Message/briefing/game-tooltip forwarders for [UIBuilder], split out to keep its function
// count in bounds.

// --- Messages (MessageDialogs) ---
fun UIBuilder.message(
    title: String,
    body: String,
    narrative: Boolean = false,
    callback: (() -> Unit)? = null,
) = MessageDialogs.message(title, body, narrative, callback)

internal fun UIBuilder.showScenarioBriefing(
    campaignFile: String,
    scenarioFile: String,
    facts: ScenarioFacts,
    rawData: dynamic,
    onFinished: () -> Unit,
) = ScenarioBriefingController.show(campaignFile, scenarioFile, facts, rawData, onFinished)

internal fun UIBuilder.primeScenarioBriefing(
    campaignFile: String,
    scenarioFile: String,
    facts: ScenarioFacts,
    rawData: dynamic,
) = ScenarioBriefingController.prime(campaignFile, scenarioFile, facts, rawData)

fun UIBuilder.reopenScenarioBriefing(onClosed: () -> Unit): Boolean = ScenarioBriefingController.reopenLast(onClosed)

fun UIBuilder.isScenarioBriefingVisible(): Boolean = ScenarioBriefingController.isVisible()

fun UIBuilder.clearScenarioBriefing() = ScenarioBriefingController.clearLast()

fun UIBuilder.messageDynamic(
    title: String,
    body: String,
    dialogClass: String = "",
    onShown: (() -> Unit)? = null,
) = MessageDialogs.messageDynamic(title, body, dialogClass, onShown)

fun UIBuilder.showPrototypeAwardMessage(eqid: Int) = MessageDialogs.showPrototypeAwardMessage(eqid)

fun UIBuilder.showAIStatus(active: Boolean) = MessageDialogs.showAIStatus(active)

// --- Map-anchored tooltips (TooltipBuilder) ---
fun UIBuilder.gameToolTip(
    text: String,
    x: Int,
    y: Int,
) = TooltipBuilder.gameToolTip(text, x, y)

fun UIBuilder.gameSmallToolTip(
    text: String,
    x: Int,
    y: Int,
    color: Int,
    id: String?,
    style: Int,
) = TooltipBuilder.gameSmallToolTip(text, x, y, color, id, style)
