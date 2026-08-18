package org.osada.ui

import kotlinx.browser.document

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
    anchor.href = "data:application/force-download," + encodeURIComponentSafe(JSON.stringify(payload))
    anchor.click()
}

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

private fun encodeURIComponentSafe(value: String): String = js("encodeURIComponent")(value) as String
