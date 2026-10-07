@file:Suppress("TooManyFunctions")

package org.osada.multiplayer.ui

import kotlinx.browser.document
import org.osada.i18n.I18n
import org.osada.multiplayer.coop.CoopCheckpoint
import org.osada.multiplayer.coop.CoopRecoveryPlan
import org.osada.multiplayer.persistence.CoopCheckpointStore
import org.osada.multiplayer.transport.RoomLobbyParticipant
import org.osada.ui.StartMenuBuilder
import org.osada.ui.downloadJson
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement

internal object CoopRecoveryScreen {
    class Selection(
        var checkpoint: CoopCheckpoint? = CoopCheckpointStore.latest(),
    )

    fun appendPicker(
        body: HTMLElement,
        name: () -> String,
        online: Boolean,
        onRestore: (String, CoopCheckpoint) -> Unit,
        selection: Selection = Selection(),
    ) {
        val card = MultiplayerScreen.panel("osada-mp-card osada-coop-recovery")
        card.id = "coopRecoveryCard"
        body.appendChild(card)
        showCheckpoint(card, selection, name, online, onRestore)
    }

    private fun showCheckpoint(
        card: HTMLElement,
        selection: Selection,
        name: () -> String,
        online: Boolean,
        onRestore: (String, CoopCheckpoint) -> Unit,
    ) {
        val checkpoint = selection.checkpoint
        card.innerHTML = ""
        card.appendChild(
            MultiplayerScreen.cardHeading(
                I18n.t("multiplayer.coop.recovery.title"),
                I18n.t("multiplayer.coop.recovery.help"),
            ),
        )
        card.appendChild(
            MultiplayerScreen.text(
                checkpoint?.let { describe(it) } ?: I18n.t("multiplayer.coop.recovery.none"),
            ),
        )
        val restore = MultiplayerScreen.button(I18n.t("multiplayer.coop.recovery.create"), primary = true)
        restore.id = "coopRecoveryCreate"
        restore.disabled = checkpoint == null || !online
        restore.onclick = { checkpoint?.let { onRestore(name(), it) } }
        card.appendChild(restore)
        val export = MultiplayerScreen.button(I18n.t("multiplayer.coop.recovery.export"), quiet = true)
        export.id = "coopRecoveryExport"
        export.disabled = checkpoint == null
        export.onclick = { checkpoint?.let(::exportCheckpoint) }
        card.appendChild(export)
        val file = document.createElement("input") as HTMLInputElement
        file.type = "file"
        file.id = "coopRecoveryFile"
        file.accept = ".json,application/json"
        file.style.display = "none"
        file.onchange = {
            file.files?.item(0)?.let { selected ->
                if ((selected.size as Number).toDouble() > CoopCheckpoint.MAX_CHARS) {
                    MultiplayerScreen.showError(I18n.t("multiplayer.coop.recovery.invalid"))
                } else {
                    readFile(selected, card, name, online, onRestore, selection)
                }
            }
        }
        card.appendChild(file)
        val import = MultiplayerScreen.button(I18n.t("multiplayer.coop.recovery.import"), quiet = true)
        import.id = "coopRecoveryImport"
        import.onclick = { file.click() }
        card.appendChild(import)
    }

    private fun readFile(
        file: dynamic,
        card: HTMLElement,
        name: () -> String,
        online: Boolean,
        onRestore: (String, CoopCheckpoint) -> Unit,
        selection: Selection,
    ) {
        val reader = js("new FileReader()")
        reader.onloadend = {
            runCatching { CoopCheckpoint.decode(reader.result as String) }
                .onSuccess {
                    selection.checkpoint = it
                    showCheckpoint(card, selection, name, online, onRestore)
                }.onFailure { MultiplayerScreen.showError(I18n.t("multiplayer.coop.recovery.invalid")) }
        }
        reader.readAsText(file)
    }

    private fun exportCheckpoint(checkpoint: CoopCheckpoint) {
        downloadJson(
            "osada-coop-${checkpoint.scenarioFile}-r${checkpoint.snapshot.revision}.json",
            JSON.parse<dynamic>(checkpoint.encode()),
        )
    }

    private fun describe(checkpoint: CoopCheckpoint): String =
        I18n.t(
            "multiplayer.coop.recovery.summary",
            mapOf(
                "file" to contentTitle(checkpoint.scenarioFile, checkpoint.campaignFile),
                "turn" to checkpoint.turn,
                "revision" to checkpoint.snapshot.revision,
                "count" to checkpoint.state.commanders.size,
            ),
        )

    private fun contentTitle(
        scenario: String,
        campaign: String?,
    ): String {
        val title =
            if (campaign == null) {
                val row = StartMenuBuilder.scenarioList().firstOrNull { (it[0] as? String) == scenario }
                if (row == null) null else row[1] as? String
            } else {
                StartMenuBuilder.campaignList().firstOrNull { (it.file as? String) == campaign }?.title as? String
            }
        return title ?: campaign ?: scenario
    }

    class Lobby(
        val roomCode: String?,
        val plan: CoopRecoveryPlan,
        val participants: List<RoomLobbyParticipant>,
        val selfId: String,
        val isHost: Boolean,
        val canStart: Boolean,
        val message: String?,
        val onBind: (String, String) -> Unit,
        val onReady: () -> Unit,
        val onStart: () -> Unit,
        val onLeave: () -> Unit,
        val onReplace: (String) -> Unit,
    )

    fun showLobby(model: Lobby) {
        val root = MultiplayerScreen.ensureRoot()
        root.innerHTML = ""
        MultiplayerScreen.appendHeader(
            root,
            I18n.t("multiplayer.coop.eyebrow"),
            I18n.t("multiplayer.coop.recovery.title"),
            I18n.t("multiplayer.coop.recovery.bind_help"),
        )
        val body = MultiplayerScreen.panel("osada-mp-body osada-mp-body--lobby")
        root.appendChild(body)
        body.appendChild(MultiplayerScreen.text(model.roomCode.orEmpty()).apply { className = "osada-mp-room-code" })
        val plan = model.plan
        body.appendChild(
            MultiplayerScreen.text(
                I18n.t(
                    "multiplayer.coop.recovery.summary",
                    mapOf(
                        "file" to contentTitle(plan.value.scenarioFile as String, plan.value.campaignFile as? String),
                        "turn" to plan.value.turn,
                        "revision" to plan.value.revision,
                        "count" to plan.commanderIds.size,
                    ),
                ),
            ),
        )
        model.message?.let { body.appendChild(MultiplayerScreen.text(it)) }
        if (plan.value.canReplaceCommander == true) {
            body.appendChild(MultiplayerScreen.text(I18n.t("multiplayer.coop.replacement.help")))
        }
        plan.commanderIds.forEach { id -> body.appendChild(bindingRow(model, id)) }
        model.participants.forEach { participant ->
            body.appendChild(
                MultiplayerScreen.text(
                    "${participant.displayName}: " +
                        I18n.t(
                            if (participant.ready) {
                                "multiplayer.coop.recovery.ready"
                            } else {
                                "multiplayer.coop.recovery.waiting"
                            },
                        ),
                ),
            )
        }
        root.appendChild(footer(model))
    }

    private fun footer(model: Lobby): HTMLElement {
        val footer = MultiplayerScreen.panel("osada-mp-footer")
        val ready =
            MultiplayerScreen.button(
                I18n.t(
                    if (model.participants.any { it.participantId == model.selfId && it.ready }) {
                        "multiplayer.ready.cancel.label"
                    } else {
                        "multiplayer.ready.label"
                    },
                ),
            )
        ready.id = "coopRecoveryReady"
        ready.disabled = model.selfId !in model.plan.bindings.values
        ready.onclick = { model.onReady() }
        footer.appendChild(ready)
        if (model.isHost) {
            val start = MultiplayerScreen.button(I18n.t("multiplayer.coop.recovery.resume"), primary = true)
            start.id = "coopRecoveryStart"
            start.disabled = !model.canStart
            start.onclick = { model.onStart() }
            footer.appendChild(start)
        }
        val leave = MultiplayerScreen.button(I18n.t("multiplayer.leave.label"), quiet = true)
        leave.onclick = { model.onLeave() }
        footer.appendChild(leave)
        return footer
    }

    private fun bindingRow(
        model: Lobby,
        id: String,
    ): HTMLElement {
        val select = document.createElement("select") as HTMLSelectElement
        select.id = "coopRecoveryBind-$id"
        select.className = "osada-mp-input"
        select.disabled = !model.isHost
        val blank = document.createElement("option") as HTMLOptionElement
        blank.value = ""
        blank.textContent = I18n.t("multiplayer.coop.recovery.choose")
        select.appendChild(blank)
        model.participants.filter { it.connected }.forEachIndexed { index, participant ->
            val option = document.createElement("option") as HTMLOptionElement
            option.value = participant.participantId
            option.textContent = "${index + 1}. ${participant.displayName}"
            option.disabled =
                model.plan.bindings.any { (commander, bound) -> commander != id && bound == participant.participantId }
            select.appendChild(option)
        }
        select.value = model.plan.bindings[id].orEmpty()
        select.onchange = { model.onBind(id, select.value) }
        val row = MultiplayerScreen.field(model.plan.name(id), select)
        if (model.plan.value.canReplaceCommander == true && model.isHost) {
            val replace = MultiplayerScreen.button(I18n.t("multiplayer.coop.replacement.replace"), quiet = true)
            replace.id = "coopRecoveryReplace-$id"
            replace.disabled = model.plan.bindings[id].isNullOrEmpty()
            replace.onclick = { model.onReplace(id) }
            row.appendChild(replace)
        }
        return row
    }
}
