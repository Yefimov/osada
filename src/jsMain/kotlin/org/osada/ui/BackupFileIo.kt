package org.osada.ui

import kotlinx.browser.document
import kotlinx.browser.window

/**
 * The file-writing and text-escaping mechanics shared by the two backup surfaces
 * ([CampaignRunBackup] and [ProfileBackup]).
 *
 * Kept in one place so both keep using the existing hidden-anchor download from
 * [org.osada.OSGlue.disksave] instead of growing a second download mechanism per feature.
 */
internal fun downloadJson(
    fileName: String,
    payload: dynamic,
) {
    val anchor = document.createElement("a").asDynamic()
    anchor.download = fileName
    val blob = js("(function(text) { return new Blob([text], {type: 'application/json'}); })")(JSON.stringify(payload))
    val url = js("URL.createObjectURL")(blob) as String
    anchor.href = url
    document.body?.appendChild(anchor)
    anchor.click()
    anchor.remove()
    window.setTimeout({ js("URL.revokeObjectURL")(url) }, DOWNLOAD_URL_LIFETIME_MS)
}

private const val DOWNLOAD_URL_LIFETIME_MS = 30_000

/**
 * Escapes text taken from an imported file before it goes into a confirmation card.
 *
 * Both previews are injected as HTML because they need line breaks, and campaign/scenario names
 * inside a backup file are attacker-controlled: a shared campaign export is a file one player hands
 * another.
 */
internal fun escapeBackupHtml(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
