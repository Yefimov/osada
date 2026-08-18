package org.osada.ui

import kotlinx.browser.document
import org.osada.hero.HeroDeskRecord
import org.osada.i18n.I18n

/**
 * The explicitly limited dossier for a migrated Hall of Fame summary
 * (`docs/design/hero-desk-and-profile-archive.md` §5).
 *
 * A separate, much smaller surface on purpose. The legacy `osada_hall_of_fame` store kept five
 * localized display strings and nothing else: no portrait recipe, no hero id, no traits, no medals,
 * no service history. Routing such a record through the full dossier would have filled five tabs
 * with empty states, and "no medals recorded" is a different and false claim from "medals were
 * never stored for this record". This shows only the facts that are actually present, under a
 * notice saying so.
 */
internal object HeroDeskLegacyDossier {
    private const val BOX_ID = "uiHeroDeskLegacy"

    fun isOpen(): Boolean = byId(BOX_ID) != null

    fun close() = delTag(byId(BOX_ID))

    fun open(
        record: HeroDeskRecord,
        onClose: () -> Unit,
    ) {
        close()
        val parent = document.body ?: return
        val box = addTag(parent, "div")
        box.id = BOX_ID
        box.className = "osada-dossier osada-hero-dossier"

        val header = addTag(box, "div")
        header.className = "osada-hero-header"
        val id = addTag(header, "div")
        id.className = "osada-hero-id"
        heroDeskText(id, "osada-hero-name", "${record.rank} ${record.name}".trim())
        heroDeskText(id, "osada-hero-sub", record.campaignName)
        val closeIcon = addTag(header, "span")
        closeIcon.className = "osada-ico osada-ico--close osada-hero-close"
        closeIcon.title = I18n.t("common.close.label")
        closeIcon.asButton(ariaLabel = I18n.t("common.close.label")) {
            close()
            onClose()
        }

        val body = addTag(box, "div")
        body.className = "osada-hero-tabbody"
        heroDeskText(body, "osada-hero-desk-warning", I18n.t("hero.desk.legacy.notice"))
        listOfNotNull(
            record.statusLabel.takeIf { it.isNotBlank() }?.let { I18n.t("hero.dossier.status", mapOf("value" to it)) },
            record.renownLabel.takeIf { it.isNotBlank() }?.let { I18n.t("hero.dossier.renown", mapOf("value" to it)) },
            record.potentialLabel
                .takeIf { it.isNotBlank() }
                ?.let { I18n.t("hero.dossier.potential", mapOf("value" to it)) },
        ).forEach { heroDeskText(body, "osada-hero-line", it) }
    }
}
