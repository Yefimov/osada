package org.osada.ui

import kotlinx.browser.document
import org.osada.account.AccountRedirect
import org.osada.account.OnlineAccount
import org.osada.cloud.CloudSync
import org.osada.i18n.I18n
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventListener

/**
 * "Online saves": optional sign-in, linked providers, storage use, sign-out and account deletion
 * (online-saves-and-player-profile.md §3.1, §6.1). Reached from a quiet row on the Save/Load screen,
 * never a launch wall. Every server-provided value is written as text, never as HTML.
 */
internal object OnlineSavesPanel {
    private const val DIALOG_ID = "osadaOnlineSaves"
    private const val ENTRY_ID = "onlineSavesEntry"
    private var keydownHandler: EventListener? = null
    private var previouslyFocused: HTMLElement? = null

    /** The quiet entry row under the backup buttons; hidden while the server offers no accounts. */
    fun buildEntry(parent: HTMLElement) {
        val row = addTag(parent, "button")
        row.id = ENTRY_ID
        row.className = "osada-account-entry"
        row.setAttribute("type", "button")
        row.style.display = "none"
        row.onclick = { open() }
        OnlineAccount.onChange(::refreshEntry)
        CloudSync.onChange {
            refreshEntry()
            if (isOpen()) render(currentStatus())
        }
        I18n.onLanguageChanged {
            refreshEntry()
            if (isOpen()) render(null)
        }
    }

    fun refreshEntry() {
        val row = byId(ENTRY_ID) ?: return
        val config = OnlineAccount.config
        row.style.display = if (config.enabled) "" else "none"
        val profile = OnlineAccount.profile
        row.textContent =
            if (profile == null) {
                I18n.t("account.entry.off")
            } else {
                I18n.t("account.entry.on", mapOf("status" to OnlineSavesSync.shortStatus()))
            }
    }

    fun isOpen(): Boolean = byId(DIALOG_ID) != null

    /** Opens the panel, showing [result] (a provider round trip's outcome) when one is pending. */
    fun open(
        result: String? = OnlineAccount.takePendingResult(),
        refresh: Boolean = true,
    ) {
        close()
        val mainBody = byId("mainbody") ?: return
        previouslyFocused = document.activeElement as? HTMLElement
        val box = addTag(mainBody, "div")
        box.id = DIALOG_ID
        box.className = "uiMessageBox osadaConfirmBox osada-account"
        box.setAttribute("role", "dialog")
        box.setAttribute("aria-modal", "true")
        box.setAttribute("aria-labelledby", "$DIALOG_ID-title")
        val handler =
            EventListener { e: Event ->
                if ((e.asDynamic().key as? String) == "Escape") {
                    e.preventDefault()
                    close()
                }
            }
        keydownHandler = handler
        document.addEventListener("keydown", handler)
        render(result?.let { I18n.t(AccountRedirect.messageKey(it)) }, initialFocus = true)
        makeVisible(DIALOG_ID)
        // A fresh page after a provider redirect may not know the session yet.
        if (refresh) {
            OnlineAccount.refresh {
                if (isOpen()) render(currentStatus())
                CloudSync.reconcile()
            }
        }
    }

    fun close() {
        keydownHandler?.let { document.removeEventListener("keydown", it) }
        keydownHandler = null
        byId(DIALOG_ID)?.let {
            clearTag(it)
            delTag(it)
        }
        previouslyFocused?.focus()
        previouslyFocused = null
    }

    internal fun currentStatus(): String? = byId("$DIALOG_ID-status")?.textContent?.takeIf { it.isNotBlank() }

    internal fun setStatus(text: String) {
        byId("$DIALOG_ID-status")?.textContent = text
    }

    /**
     * Rebuilds the dialog. A re-render (sync status changed) keeps keyboard focus on the button the
     * player was on; only the first render of an [open] moves focus to the main action.
     */
    internal fun render(
        status: String?,
        initialFocus: Boolean = false,
    ) {
        val box = byId(DIALOG_ID) ?: return
        val focusedLabel = (document.activeElement as? HTMLElement)?.takeIf { box.contains(it) }?.textContent
        clearTag(box)
        val title = addTag(box, "div")
        title.id = "$DIALOG_ID-title"
        title.className = "uiMessageBoxTitle"
        title.textContent = I18n.t("account.title")
        val body = addTag(box, "div")
        body.className = "uiMessageBoxBody osada-account__body"
        val statusLine = addTag(body, "p")
        statusLine.id = "$DIALOG_ID-status"
        statusLine.className = "osada-account__status"
        statusLine.setAttribute("role", "status")
        statusLine.setAttribute("aria-live", "polite")
        statusLine.textContent = status.orEmpty()
        val profile = OnlineAccount.profile
        if (profile ==
            null
        ) {
            OnlineSavesContent.renderSignedOut(body)
        } else {
            OnlineSavesContent.renderSignedIn(body, profile)
        }
        val footer = addTag(box, "div")
        footer.className = "osadaConfirmBoxButtons"
        val closeButton =
            OnlineSavesContent.button(
                footer,
                I18n.t("common.close.label"),
                "osadaConfirmBoxCancel",
            ) { close() }
        val buttons = box.querySelectorAll("button")
        val sameButton =
            (0 until buttons.length)
                .mapNotNull { buttons.item(it) as? HTMLElement }
                .firstOrNull { focusedLabel != null && it.textContent == focusedLabel }
        when {
            sameButton != null -> sameButton.focus()
            initialFocus || focusedLabel != null ->
                (box.querySelector("button.osada-account__primary") as? HTMLElement ?: closeButton).focus()
        }
    }
}
