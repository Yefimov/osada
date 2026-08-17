package org.osada.ui

import org.osada.i18n.I18n
import org.osada.scenario.AuthorCredits

/**
 * The selection screens' dedicated localized **Author / Authors** row
 * (`docs/player-comfort-roadmap.md`, "Authorship metadata contract").
 *
 * A row of its own rather than a sentence inside the synopsis: the label is translated, the names
 * never are, and a description that is purely a description can be localized later without dragging
 * proper names through a translation pass.
 *
 * Renders nothing at all when the content carries no credit, which is most of the imported
 * catalogue. An empty "Author: —" row would imply the credit is missing rather than absent.
 */
internal object AuthorRow {
    /** Ready-to-insert HTML, or an empty string when [file] has no credits. */
    fun html(file: String?): String {
        val credits = AuthorCredits.forFile(file)
        if (credits.isEmpty()) return ""
        val label = I18n.plural("selection.authors.label", credits.size)
        val names = credits.joinToString("; ") { credit -> nameWithRole(credit) }
        return "<div class=\"osadaAuthorRow\"><b>" + escape(label) + "</b> " + escape(names) + "</div>"
    }

    /** Plain text of the same row, for a tooltip or an accessible label. */
    fun text(file: String?): String {
        val credits = AuthorCredits.forFile(file)
        if (credits.isEmpty()) return ""
        val label = I18n.plural("selection.authors.label", credits.size)
        return label + " " + credits.joinToString("; ") { credit -> nameWithRole(credit) }
    }

    /** The role is only spelled out when it is NOT the plain original authorship: "Tim Ruger", but
     *  "J. Smith (conversion)". Every imported credit we have is an original one, so the common
     *  case stays a bare name. */
    private fun nameWithRole(credit: AuthorCredits.Credit): String =
        if (credit.role == AuthorCredits.Role.ORIGINAL) {
            credit.name
        } else {
            credit.name + " (" + I18n.t("selection.authors.role.${credit.role.name.lowercase()}") + ")"
        }

    private fun escape(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
}
