@file:Suppress("ReturnCount", "TooManyFunctions")

package org.osada.ui.briefing

import org.osada.Game
import org.osada.campaign.CampaignNarrative
import org.osada.current
import org.osada.getCampaignPlayer
import org.osada.i18n.I18n
import org.osada.multiplayer.client.OsadaMultiplayer
import org.osada.multiplayer.command.BriefingReady
import org.osada.multiplayer.command.ChooseCampaignDialogueOption
import org.osada.multiplayer.command.ResolveCampaignVote
import org.osada.multiplayer.coop.CoopBriefing
import org.osada.multiplayer.coop.CoopDialogueNode
import org.osada.multiplayer.coop.CoopRuntime
import org.osada.resolveScenarioBriefing
import org.osada.scenario.objectiveReport
import org.osada.ui.GameplayLocalization
import org.w3c.dom.Element

/** Presentation follows committed decisions; only the host calls the existing consequence engine. */
internal object CoopBriefingBridge {
    private var showing: String? = null

    fun capture() {
        val coop = CoopRuntime.state ?: return
        if (Game.current?.campaign == null || coop.briefing != null) return
        val source = source() ?: return
        val parsed = BriefingLocalization.parse(source)
        coop.briefing =
            CoopBriefing(
                parsed.dialogue.map { line ->
                    CoopDialogueNode(
                        line.id,
                        line.next ?: parsed.nextSequential(line)?.id,
                        line.choices.associate { choice ->
                            choice.id to (choice.next ?: line.next ?: parsed.nextSequential(line)?.id)
                        },
                    )
                },
            )
    }

    fun synchronize() {
        val coop = CoopRuntime.state ?: return reset()
        val shared = coop.briefing ?: return
        val source = source() ?: return
        val key = "${coop.missionIndex}:${source.scenarioFile}"
        if (!shared.open) {
            if (showing == key || ScenarioBriefingController.currentSource?.scenarioFile == source.scenarioFile) {
                ScenarioBriefingController.close(runCallback = true)
            }
            showing = key
            return
        }
        Game.current?.uiMessageClicked = false
        if (showing != key || !ScenarioBriefingController.isVisible()) {
            showing = key
            val game = Game.current ?: return
            val scenario = game.scenario ?: return
            val facts =
                ScenarioFacts(
                    scenario.name,
                    GameplayLocalization.scenarioDateLabel(scenario.date),
                    "",
                    scenario.getDescription(),
                    scenario.objectiveReport(game.spotSide, revealHidden = false).extended,
                )
            ScenarioBriefingController.show(source.campaignFile, source.scenarioFile, facts, source.rawData) {
                if (OsadaMultiplayer.isAuthority()) {
                    game.ui?.releaseCoopOpening()
                } else {
                    game.uiMessageClicked = true
                }
            }
        }
        ScenarioBriefingController.synchronizeSharedDecisions()
        renderVotes()
    }

    fun choose(
        lineId: String,
        choiceId: String,
    ): Boolean {
        val coop = CoopRuntime.state ?: return false
        val shared = coop.briefing ?: return false
        if (!shared.open) return false
        val command =
            if (shared.winners(coop.requiredCommanderIds()).size > 1 && OsadaMultiplayer.isAuthority()) {
                ResolveCampaignVote(choiceId, coop.humanPlayerId)
            } else {
                ChooseCampaignDialogueOption(lineId, choiceId, coop.humanPlayerId)
            }
        OsadaMultiplayer.submitOrder(command)
        renderVotes()
        return true
    }

    fun finish(): Boolean {
        val coop = CoopRuntime.state ?: return false
        if (coop.briefing?.open != true) return false
        OsadaMultiplayer.submitOrder(BriefingReady(coop.humanPlayerId))
        return true
    }

    /** Applier entry point, never a local button: uses validated authored effects exactly once. */
    fun commit(
        lineId: String,
        choiceId: String,
    ): Boolean {
        val source = source() ?: return false
        val offered =
            BriefingLocalization
                .parse(source)
                .lineById(lineId)
                ?.choices
                .orEmpty()
        val choice = offered.firstOrNull { it.id == choiceId } ?: return false
        return CampaignNarrative.commitChoice(
            source.scenarioFile,
            lineId,
            choiceId,
            offered.map { it.id },
            choice.effects,
            Game.current?.getCampaignPlayer(),
        )
    }

    private fun source(): BriefingSource? {
        val game = Game.current ?: return null
        val campaign = game.campaign ?: return null
        val scenario = game.scenario ?: return null
        val file = scenario.file ?: return null
        return BriefingSource(
            campaign.file,
            file,
            scenario.name,
            game.resolveScenarioBriefing(campaign.getCurrentScenario(), file),
        )
    }

    internal fun renderVotes() {
        val coop = CoopRuntime.state ?: return
        val shared = coop.briefing ?: return
        val view = ScenarioBriefingController.view ?: return
        view.root.classList.add("osada-briefing--coop")
        view.root.querySelector(".osada-coop-vote-status")?.let { it.parentNode?.removeChild(it) }
        val pending = shared.pending()
        val votes = pending?.let { shared.votes[it.id] }.orEmpty()
        val required = coop.requiredCommanderIds()
        val winners = shared.winners(required)
        val tie = winners.size > 1
        val status = element("p", "osada-briefing__order-text osada-coop-vote-status")
        status.setAttribute("role", "status")
        status.textContent =
            if (pending == null) {
                I18n.t(
                    "multiplayer.coop.briefing_ready",
                    mapOf(
                        "ready" to shared.ready.count { it in required },
                        "total" to required.size,
                    ),
                )
            } else {
                I18n.t(
                    if (tie) "multiplayer.coop.vote_tie" else "multiplayer.coop.vote_wait",
                    mapOf("ready" to votes.keys.count { it in required }, "total" to required.size),
                )
            }
        view.root.querySelector(".osada-briefing__shell")?.appendChild(status)
        val self = coop.commanderOfParticipant(OsadaMultiplayer.localParticipantId())?.id
        view.beginButton.asDynamic().disabled =
            pending != null ||
            self in shared.ready ||
            !OsadaMultiplayer.acceptsLocalCommands()
        decorateChoices(view, votes[self], tie, winners)
    }

    private fun decorateChoices(
        view: ScenarioBriefingView,
        selected: String?,
        tie: Boolean,
        winners: Set<String>,
    ) {
        val choices = view.root.querySelectorAll(".osada-dialogue__choice, .osada-briefing__decision-option")
        for (index in 0 until choices.length) {
            val button = choices.item(index) as? Element ?: continue
            val id = button.getAttribute("data-choice-id")
            val unavailable = !OsadaMultiplayer.acceptsLocalCommands()
            button.asDynamic().disabled = unavailable || (tie && (!OsadaMultiplayer.isAuthority() || id !in winners))
            button.setAttribute("aria-pressed", (id != null && selected == id).toString())
        }
    }

    fun reset() {
        showing = null
    }
}

internal fun ScenarioBriefingController.synchronizeSharedDecisions() {
    val shared = CoopRuntime.state?.briefing ?: return
    var changed = false
    path.forEach { step ->
        val selected = shared.decisions[step.lineId]
        val choice = briefing?.lineById(step.lineId)?.choices?.firstOrNull { it.id == selected }
        if (choice != null && step.selectedChoice != choice) {
            step.selectedChoice = choice
            changed = true
        }
    }
    val current = currentLine()
    val selected = path.lastOrNull()?.selectedChoice
    if (changed && current != null && selected != null) {
        resolveNext(current, selected)?.let { path += ScenarioBriefingController.DialogueStep(it.id) }
        if (stage == BriefingStage.ORDERS) advanceToNextPendingChoice()
        renderCurrentStage()
    }
}
