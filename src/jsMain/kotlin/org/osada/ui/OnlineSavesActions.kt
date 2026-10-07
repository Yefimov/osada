package org.osada.ui

import kotlinx.browser.window
import org.osada.account.AccountApi
import org.osada.account.AccountJson
import org.osada.account.AccountRedirect
import org.osada.account.OnlineAccount
import org.osada.i18n.I18n

/** What the panel's buttons do. Every failure leaves the player signed in or a guest, never stuck. */
internal object OnlineSavesActions {
    private var busy = false

    fun startSignIn(
        provider: String,
        link: Boolean,
    ) {
        if (busy) return
        busy = true
        OnlineSavesPanel.setStatus(I18n.t("account.status.redirecting"))
        AccountApi.startSignIn(provider, link, OnlineAccount.profile?.csrf) { reply ->
            busy = false
            val url = reply.json?.url as? String
            if (reply.ok && url != null && url.startsWith("https://")) {
                // Leaving the page is safe: browser saves are already committed at their own boundaries.
                window.location.assign(url)
            } else {
                OnlineSavesPanel.setStatus(failureText(reply.status, reply.error))
            }
        }
    }

    fun signOut(everywhere: Boolean) {
        val csrf = OnlineAccount.profile?.csrf ?: return
        AccountApi.logout(everywhere, csrf) { reply ->
            if (reply.ok) {
                OnlineAccount.update(null)
                OnlineSavesPanel.render(I18n.t("account.result.signed-out"))
            } else {
                // The session may already be gone (expired, revoked elsewhere): ask the server.
                val failure = failureText(reply.status, reply.error)
                OnlineAccount.refresh { if (OnlineSavesPanel.isOpen()) OnlineSavesPanel.render(failure) }
            }
        }
    }

    fun unlink(provider: String) {
        val csrf = OnlineAccount.profile?.csrf ?: return
        AccountApi.unlink(provider, csrf) { reply ->
            if (reply.ok) OnlineAccount.update(AccountJson.profile(reply.json))
            OnlineSavesPanel.render(
                if (reply.ok) I18n.t("account.result.unlinked") else failureText(reply.status, reply.error),
            )
        }
    }

    /** Deletion is explained separately and confirmed; device saves and downloaded files are kept (§3.4). */
    fun confirmDelete() {
        val csrf = OnlineAccount.profile?.csrf ?: return
        OnlineSavesPanel.close()
        ConfirmCard.open(
            I18n.t("account.delete.title"),
            escapeHtml(I18n.t("account.delete.body")),
            I18n.t("account.delete.confirm"),
        ) {
            AccountApi.deleteAccount(csrf) { reply ->
                if (reply.ok) OnlineAccount.update(null)
                OnlineSavesPanel.open(null)
                OnlineSavesPanel.setStatus(
                    if (reply.ok) I18n.t("account.result.deleted") else failureText(reply.status, reply.error),
                )
            }
        }
    }

    private fun failureText(
        status: Int,
        error: String?,
    ): String =
        when {
            status == 0 -> I18n.t("account.status.offline")
            error != null -> I18n.t(AccountRedirect.messageKey(error))
            else -> I18n.t("account.result.failed")
        }

    fun escapeHtml(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
}
