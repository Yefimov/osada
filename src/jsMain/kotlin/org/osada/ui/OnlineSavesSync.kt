package org.osada.ui

import org.osada.cloud.CloudSync
import org.osada.cloud.CopySummary
import org.osada.cloud.SyncDecision
import org.osada.cloud.SyncStatus
import org.osada.i18n.I18n
import org.w3c.dom.HTMLElement
import kotlin.js.Date

/**
 * The sync part of the signed-in Online saves dialog: one status line, and the choice the player
 * owes when the rules cannot decide (§3.2, §4). Cards say "Saved later by this device" never
 * "newer": only ancestry decides, and the player sees both versions before anything is replaced.
 */
internal object OnlineSavesSync {
    fun render(body: HTMLElement) {
        val section = addTag(body, "section")
        section.className = "osada-account__sync"
        OnlineSavesContent.paragraph(section, statusText(), "osada-account__sync-status")
        when (val decision = CloudSync.decision) {
            is SyncDecision.Choose -> renderChoice(section, decision)
            is SyncDecision.OfferDownload -> {
                card(section, I18n.t("account.sync.card.online"), onlineLines(decision.copy))
                action(section, "account.sync.download", primary = true) { CloudSync.downloadOnline() }
            }
            is SyncDecision.Download ->
                action(section, "account.sync.load_newer", primary = true) { CloudSync.downloadOnline() }
            is SyncDecision.CloudGone ->
                action(section, "account.sync.upload_again", primary = true) { confirmUpload(replacing = false) }
            SyncDecision.OfferFirstUpload ->
                action(
                    section,
                    "account.sync.first_upload",
                    primary = true,
                ) { CloudSync.uploadThisDevice(observed = null) }
            else -> Unit
        }
        if (CloudSync.status != SyncStatus.OFF) {
            action(section, "account.sync.now", primary = false) { CloudSync.reconcile() }
        }
    }

    /** The short form for the Save/Load row. */
    fun shortStatus(): String = I18n.t("account.sync.short.${CloudSync.status.name.lowercase()}")

    private fun statusText(): String {
        val status = CloudSync.status
        return when {
            status == SyncStatus.SYNCED && CloudSync.lastSyncedAt > 0 ->
                I18n.t("account.sync.status.synced_at", mapOf("time" to I18n.formatDateTime(CloudSync.lastSyncedAt)))
            status == SyncStatus.CLOUD_GONE -> {
                val reason = (CloudSync.decision as? SyncDecision.CloudGone)?.reason ?: "missing"
                I18n.t("account.sync.status.cloud_gone.$reason")
            }
            else -> I18n.t("account.sync.status.${status.name.lowercase()}")
        }
    }

    private fun renderChoice(
        section: HTMLElement,
        decision: SyncDecision.Choose,
    ) {
        OnlineSavesContent.paragraph(
            section,
            I18n.t(if (decision.sharedBase) "account.sync.choice.diverged" else "account.sync.choice.unlinked"),
            "osada-account__note",
        )
        val cards = addTag(section, "div")
        cards.className = "osada-account__cards"
        CloudSync.lastLocal?.let {
            card(
                cards,
                I18n.t("account.sync.card.device"),
                localLines(it.summary, it.playedAt),
            )
        }
        card(cards, I18n.t("account.sync.card.online"), onlineLines(decision.copy))
        action(section, "account.sync.use_device", primary = false) { confirmUpload(replacing = true) }
        action(section, "account.sync.use_online", primary = false) { CloudSync.downloadOnline() }
        action(section, "account.sync.download_both", primary = false) { downloadBoth() }
        if (!decision.paused) action(section, "account.sync.decide_later", primary = false) { CloudSync.decideLater() }
    }

    private fun confirmUpload(replacing: Boolean) {
        if (!replacing) {
            CloudSync.uploadThisDevice(observed = null)
            return
        }
        // The copy on the card the player is looking at, not whatever is online by the time they confirm.
        val observed = (CloudSync.decision as? SyncDecision.Choose)?.copy
        OnlineSavesPanel.close()
        ConfirmCard.open(
            I18n.t("account.sync.replace.title"),
            OnlineSavesActions.escapeHtml(I18n.t("account.sync.replace.body")),
            I18n.t("account.sync.replace.confirm"),
        ) {
            OnlineSavesPanel.open(result = null)
            CloudSync.uploadThisDevice(observed)
        }
    }

    /** Both versions to disk before choosing: this device's as a backup, the online one verbatim. */
    private fun downloadBoth() {
        ProfileBackup.exportToFile()
        CloudSync.fetchOnlineDocument { text ->
            if (text == null) {
                OnlineSavesPanel.setStatus(I18n.t("account.sync.status.offline"))
            } else {
                downloadJson("osada-online-copy-${Date().getTime().toLong()}.json", JSON.parse(text))
            }
        }
    }

    private fun localLines(
        summary: CopySummary,
        playedAt: Double,
    ): List<String> =
        listOfNotNull(
            I18n.t("account.sync.card.campaigns", mapOf("count" to summary.campaigns)),
            latestLine(summary),
            playedAt.takeIf { it > 0 }?.let {
                I18n.t(
                    "account.sync.card.saved",
                    mapOf("date" to I18n.formatDateTime(it)),
                )
            },
        )

    private fun onlineLines(copy: org.osada.cloud.CloudCopy): List<String> =
        listOfNotNull(
            I18n.t("account.sync.card.campaigns", mapOf("count" to copy.summary.campaigns)),
            latestLine(copy.summary),
            copy.summary.device
                .takeIf { it in DEVICES }
                ?.let { I18n.t("account.sync.card.from", mapOf("device" to I18n.t("account.sync.device.$it"))) },
            I18n.t(
                "account.sync.card.uploaded",
                mapOf(
                    "uploaded" to I18n.formatDateTime(copy.serverSavedAt),
                    "played" to
                        (
                            copy.clientPlayedAt?.takeIf { it > 0 }?.let(I18n::formatDateTime)
                                ?: I18n.t("account.sync.unknown")
                        ),
                ),
            ),
        )

    private fun latestLine(summary: CopySummary): String? =
        when {
            summary.campaignName.isNotBlank() ->
                I18n.t(
                    "account.sync.card.latest",
                    mapOf(
                        "campaign" to summary.campaignName,
                        "scenario" to summary.scenarioName,
                        "turn" to summary.turn,
                    ),
                )
            summary.scenarioName.isNotBlank() ->
                I18n.t(
                    "account.sync.card.latest_battle",
                    mapOf(
                        "scenario" to summary.scenarioName,
                        "turn" to summary.turn,
                    ),
                )
            else -> null
        }

    private fun card(
        parent: HTMLElement,
        title: String,
        lines: List<String>,
    ) {
        val card = addTag(parent, "div")
        card.className = "osada-account__card"
        OnlineSavesContent.paragraph(card, title, "osada-account__card-title")
        lines.forEach { OnlineSavesContent.paragraph(card, it, "osada-account__note") }
    }

    private fun action(
        parent: HTMLElement,
        key: String,
        primary: Boolean,
        onActivate: () -> Unit,
    ) {
        OnlineSavesContent.button(
            parent,
            I18n.t(key),
            if (primary) "osada-account__primary" else "osada-account__secondary",
            onActivate,
        )
    }

    private val DEVICES = setOf("phone", "tablet", "computer")
}
