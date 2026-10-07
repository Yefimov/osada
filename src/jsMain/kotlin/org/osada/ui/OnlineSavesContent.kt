package org.osada.ui

import org.osada.account.AccountProfile
import org.osada.account.OnlineAccount
import org.osada.i18n.I18n
import org.w3c.dom.HTMLElement

/** The two states of [OnlineSavesPanel]'s body. Text only: nothing here is ever parsed as HTML. */
internal object OnlineSavesContent {
    fun renderSignedOut(body: HTMLElement) {
        paragraph(body, I18n.t("account.signed_out.intro"))
        paragraph(body, I18n.t("account.retention.policy"), "osada-account__note")
        val actions = addTag(body, "div")
        actions.className = "osada-account__actions"
        OnlineAccount.config.providers.forEach { provider ->
            button(
                actions,
                I18n.t("account.provider.continue_with", mapOf("provider" to providerName(provider))),
                "osada-account__primary",
            ) {
                OnlineSavesActions.startSignIn(provider, link = false)
            }
        }
        button(actions, I18n.t("account.keep_guest"), "osada-account__secondary") { OnlineSavesPanel.close() }
        if (!OnlineAccount.config.signupEnabled) paragraph(body, I18n.t("account.signup_closed"), "osada-account__note")
    }

    fun renderSignedIn(
        body: HTMLElement,
        profile: AccountProfile,
    ) {
        paragraph(body, I18n.t("account.signed_in.since", mapOf("date" to I18n.formatDateTime(profile.createdAt))))
        val list = addTag(body, "ul")
        list.className = "osada-account__providers"
        profile.identities.forEach { identity ->
            val item = addTag(list, "li")
            val label = addTag(item, "span")
            label.textContent =
                I18n.t(
                    "account.provider.linked",
                    mapOf(
                        "provider" to providerName(identity.provider),
                        "date" to I18n.formatDateTime(identity.linkedAt),
                    ),
                )
            if (profile.identities.size > 1) {
                button(
                    item,
                    I18n.t("account.provider.unlink"),
                    "osada-account__link",
                ) { OnlineSavesActions.unlink(identity.provider) }
            }
        }
        OnlineAccount.config.providers.filterNot(profile::hasProvider).forEach { provider ->
            button(
                body,
                I18n.t("account.provider.link", mapOf("provider" to providerName(provider))),
                "osada-account__secondary",
            ) {
                OnlineSavesActions.startSignIn(provider, link = true)
            }
        }
        paragraph(
            body,
            I18n.t(
                "account.storage.used",
                mapOf(
                    "used" to formatBytes(profile.usedBytes),
                    "quota" to formatBytes(profile.quotaBytes),
                ),
            ),
            "osada-account__note",
        )
        paragraph(
            body,
            I18n.t("account.retention.until", mapOf("date" to I18n.formatDateTime(profile.retentionDeadline))),
            "osada-account__note",
        )
        OnlineSavesSync.render(body)
        val actions = addTag(body, "div")
        actions.className = "osada-account__actions"
        button(actions, I18n.t("account.sign_out"), "osada-account__secondary") {
            OnlineSavesActions.signOut(everywhere = false)
        }
        button(actions, I18n.t("account.sign_out_everywhere"), "osada-account__secondary") {
            OnlineSavesActions.signOut(everywhere = true)
        }
        button(actions, I18n.t("account.delete.button"), "osada-account__danger") { OnlineSavesActions.confirmDelete() }
    }

    private fun providerName(provider: String): String = I18n.t("account.provider.name.$provider")

    private fun formatBytes(bytes: Double): String =
        if (bytes < MEBIBYTE) {
            I18n.t("account.storage.kib", mapOf("value" to kotlin.math.ceil(bytes / KIBIBYTE).toInt()))
        } else {
            I18n.t("account.storage.mib", mapOf("value" to (kotlin.math.round(bytes / MEBIBYTE * TENTHS) / TENTHS)))
        }

    fun paragraph(
        parent: HTMLElement,
        text: String,
        className: String = "",
    ): HTMLElement =
        addTag(parent, "p").also {
            it.className = className
            it.textContent = text
        }

    fun button(
        parent: HTMLElement,
        label: String,
        className: String,
        onActivate: () -> Unit,
    ): HTMLElement =
        addTag(parent, "button").also {
            it.className = className
            it.setAttribute("type", "button")
            it.textContent = label
            it.onclick = { _ -> onActivate() }
        }

    private const val KIBIBYTE = 1024.0
    private const val MEBIBYTE = 1024.0 * 1024.0
    private const val TENTHS = 10.0
}
