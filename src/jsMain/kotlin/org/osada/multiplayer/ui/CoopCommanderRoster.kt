package org.osada.multiplayer.ui

import org.osada.hero.HeroDisplay
import org.osada.i18n.I18n
import org.osada.multiplayer.coop.CoopAbilityCatalogue
import org.osada.multiplayer.coop.CoopCommander
import org.osada.ui.addTag
import org.osada.ui.byId
import org.osada.ui.delTag
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent

/**
 * The players of a cooperative room, shown the way the single-player Headquarters shows its heroic
 * commanders (`CommanderRosterPresenter` / `LeaderDossierPresenter`), and with the same styles: a
 * roster of portrait rows, and a dossier per player with tabs. A cooperative room has no legendary
 * heroes besides its players, so this is what the headquarters button opens there.
 *
 * Everyone sees everyone's chosen abilities, units, biography and score. Only your own dossier lets
 * you change your map mark. Names and every other player-supplied text go in through `textContent`.
 */
internal object CoopCommanderRoster {
    private const val ROSTER_ID = "uiCoopRoster"

    fun isOpen(): Boolean = byId(ROSTER_ID) != null || CoopCommanderDossier.isOpen()

    fun close() {
        delTag(byId(ROSTER_ID))
        CoopCommanderDossier.close()
    }

    /** Re-renders whatever is open with the latest room state. */
    fun refresh(model: CoopHud.Model) {
        if (byId(ROSTER_ID) != null) openRoster(model)
        CoopCommanderDossier.refresh(model)
    }

    // -- Roster --------------------------------------------------------------------------------

    fun openRoster(model: CoopHud.Model) {
        delTag(byId(ROSTER_ID))
        val mainBody = byId("mainbody") ?: return
        val state = model.state
        val box = addTag(mainBody, "div")
        box.id = ROSTER_ID
        box.className = "osada-dossier osada-hero-roster"

        val header = addTag(box, "div")
        header.className = "osada-hero-header"
        val title = addTag(header, "div")
        title.className = "osada-hero-id"
        CoopDossierBits.addText(title, "osada-hero-name", I18n.t("multiplayer.coop.hud.title"))
        CoopDossierBits.addText(title, "osada-hero-sub", missionLine(model) ?: I18n.t("multiplayer.coop.roster.help"))
        CoopDossierBits.closeButton(header) { close() }

        val body = addTag(box, "div")
        body.className = "osada-hero-tabbody"
        state.commanders.forEach { commander -> rosterRow(body, model, commander) }
        excuseControls(body, model)
        body.appendChild(CoopEconomyView.render(state, model.selfCommanderId))
    }

    private fun rosterRow(
        body: HTMLElement,
        model: CoopHud.Model,
        commander: CoopCommander,
    ) {
        val isSelf = commander.id == model.selfCommanderId
        val card = addTag(body, "div")
        card.className = "osada-hero-rosterrow osada-coop-rosterrow" + if (isSelf) " is-self" else ""
        card.appendChild(CoopHud.portrait(commander, "osada-hero-rosterrow-portrait"))
        val copy = addTag(card, "div")
        copy.className = "osada-hero-rosterrow-copy"
        val nameLine = addTag(copy, "div")
        nameLine.className = "osada-hero-rosterrow-name osada-coop-nameline"
        nameLine.appendChild(CoopHud.seatMark(commander, "osada-coop-seat"))
        addTag(nameLine, "span").textContent =
            commander.name + if (isSelf) " " + I18n.t("multiplayer.coop.roster.you") else ""
        val abilities =
            commander.abilityIds
                .mapNotNull(CoopAbilityCatalogue::byId)
                .joinToString(", ") { HeroDisplay.trait(it.leader, "").title }
                .ifEmpty { I18n.t("multiplayer.coop.abilities.none") }
        CoopDossierBits.addText(
            copy,
            "osada-hero-rosterrow-sub",
            listOf(
                CoopDossierBits.statusOf(model, commander),
                I18n.t("multiplayer.coop.hud.units", mapOf("count" to model.state.unitsOf(commander.id).size)),
                abilities,
            ).joinToString(" · "),
        )
        card.onclick = { _: MouseEvent -> CoopCommanderDossier.open(model, commander.id) }
    }

    private fun excuseControls(
        body: HTMLElement,
        model: CoopHud.Model,
    ) {
        if (!model.isHost) return
        val state = model.state
        state.commanders
            .filter { model.connected[it.participantId] == false && it.id !in state.excused }
            .forEach { absent ->
                val substitute = state.commanders.firstOrNull { it.id != absent.id && it.id !in state.excused }
                val label =
                    if (substitute != null) {
                        I18n.t(
                            "multiplayer.coop.hud.excuse.delegate",
                            mapOf("name" to absent.name, "to" to substitute.name),
                        )
                    } else {
                        I18n.t("multiplayer.coop.hud.excuse", mapOf("name" to absent.name))
                    }
                val go = addTag(body, "button")
                go.className = "osada-hero-locate"
                go.textContent = label
                go.onclick = { e: MouseEvent ->
                    e.stopPropagation()
                    model.onExcuse(absent.id, true, substitute?.id)
                }
            }
    }

    // -- Helpers -------------------------------------------------------------------------------

    private fun missionLine(model: CoopHud.Model): String? {
        val state = model.state
        return when {
            model.gameEnded ->
                I18n.t(
                    if (model.isHost) "multiplayer.coop.mission.over.host" else "multiplayer.coop.mission.over.guest",
                )

            state.campaignTotal > 0 ->
                I18n.t(
                    "multiplayer.coop.mission.progress",
                    mapOf("number" to state.missionIndex + 1, "total" to state.campaignTotal),
                )

            else -> null
        }
    }
}
