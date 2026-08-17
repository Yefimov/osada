package org.osada.ui

import org.osada.OSGlue
import org.osada.i18n.I18n
import org.osada.save.SaveStatus
import org.osada.save.SaveStatusBus
import kotlin.js.Date

/** Quiet `Saving...`/`Saved HH:MM`/`Save failed` indicator, `docs/design/save-recovery.md`
 *  section 6. Subscribes to [SaveStatusBus], which [org.osada.GameStatePersistence] pushes to
 *  after every commit attempt -- this file has no persistence logic of its own. */
internal object SaveStatusPresenter {
    private const val ELEMENT_ID = "osadaSaveStatus"

    fun install() {
        SaveStatusBus.setListener(::render)
    }

    private fun render(status: SaveStatus) {
        val el = byId(ELEMENT_ID) ?: return
        el.classList.remove("osadaSaveStatus--saving", "osadaSaveStatus--saved", "osadaSaveStatus--failed")
        el.onclick = null
        when (status) {
            is SaveStatus.Idle -> {
                el.textContent = ""
            }
            is SaveStatus.Saving -> {
                el.classList.add("osadaSaveStatus--saving")
                el.textContent = I18n.t("hud.save_status.saving")
                el.title = ""
            }
            is SaveStatus.Saved -> {
                el.classList.add("osadaSaveStatus--saved")
                val d = Date(status.atMillis)
                val hh = d.getHours().toString().padStart(2, '0')
                val mm = d.getMinutes().toString().padStart(2, '0')
                el.textContent = I18n.t("hud.save_status.saved", mapOf("time" to "$hh:$mm"))
                el.title = ""
            }
            is SaveStatus.Failed -> {
                el.classList.add("osadaSaveStatus--failed")
                el.textContent = I18n.t("hud.save_status.failed")
                el.title = I18n.t("hud.save_status.failed.export_now_help")
                el.onclick = { _ ->
                    val name = "osada-campaign-export-${Date().getTime().toLong()}.json"
                    OSGlue.disksave(name)
                }
            }
        }
    }
}
