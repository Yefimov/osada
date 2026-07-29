package org.osada.ui

import kotlinx.browser.document
import org.osada.UnitClass
import org.osada.i18n.I18n
import org.osada.model.Cell
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent

/**
 * [UICombatLog]'s event-feed row/group plumbing shared by every group builder
 * ([CombatLogCombatGroup], [CombatLogGroups]). Split out purely to keep [UICombatLog] within the
 * project's function-count/class-size limits -- not expected to be called from elsewhere.
 */
internal object CombatLogFeed {
    internal class FeedGroup(
        val icoMod: String,
        val label: String,
        val rowsBody: HTMLElement,
        val count: Int,
    )

    fun emptyGroup(
        icoMod: String,
        label: String,
    ): FeedGroup {
        val body = document.createElement("div") as HTMLElement
        body.className = "osada-tr-group__rows"
        return FeedGroup(icoMod, label, body, 0)
    }

    fun attachGroup(
        container: HTMLElement,
        group: FeedGroup,
    ) {
        val groupEl = addTag(container, "div")
        groupEl.className = "osada-tr-group"
        val header = addTag(groupEl, "div")
        header.className = "osada-tr-group__header"
        header.title =
            I18n.t(
                "turn_report.group.help",
                mapOf("group" to group.label, "count" to group.count),
            )
        val icon = addTag(header, "span")
        icon.className = "osada-ico osada-ico--${group.icoMod} osada-tr-group__ico"
        val label = addTag(header, "span")
        label.className = "osada-tr-group__label"
        label.textContent = group.label
        val badge = addTag(header, "span")
        badge.className = "osada-tr-group__badge"
        badge.textContent = group.count.toString()
        header.onclick = { _: MouseEvent ->
            // In the turn-start teaser, a group header is an "open the full report" affordance
            // (same as the expand button — user request): collapsing a group inside a 220px
            // clipped strip isn't meaningful. Full mode keeps the collapse toggle.
            if (byId("combatLog")?.classList?.contains("osada-tr--teaser") == true) {
                UICombatLog.showCombatLog(true)
            } else {
                groupEl.classList.toggle("osada-tr-group--collapsed")
            }
        }
        groupEl.appendChild(group.rowsBody)
    }

    /** One feed row: icon + bold title line + dim detail line. Clickable (jumps to `pos`) when a
     *  position is available; the raw "(col,row)" lives only in the tooltip, same treatment as
     *  the sidebar log ([HudLog.addAt]). */
    fun addFeedRow(
        container: HTMLElement,
        icon: String,
        title: String,
        detail: String,
        isCore: Boolean,
        isDestroyed: Boolean,
        pos: Cell?,
    ): HTMLElement {
        // Named rowEl, NOT row: `jsObject { row = pos.row; col = pos.col }` below builds a
        // dynamic {row, col} literal via an implicit assignment — a local `val row` in this same
        // scope shadows that and the compiler tries to reassign the val instead (type/reassign
        // error), rather than setting the js object's property.
        val rowEl = addTag(container, "div")
        rowEl.className = "osada-tr-row" +
            (if (isCore) " osada-tr-row--core" else "") +
            (if (isDestroyed) " osada-tr-row--destroyed" else "")
        val iconBox = addTag(rowEl, "div")
        iconBox.className = "osada-tr-row__icon"
        // background-image at natural size, NOT an <img src>: unit icons are multi-frame sprite
        // STRIPS — an <img> (or background-size:contain) would scale the WHOLE strip into the
        // box (a row of tiny units), same bug already fixed for #ecPortrait. Natural size +
        // position 0 0 shows only the first frame.
        if (icon.isNotEmpty()) iconBox.style.backgroundImage = "url($icon)" else iconBox.style.display = "none"
        val body = addTag(rowEl, "div")
        body.className = "osada-tr-row__body"
        val titleDiv = addTag(body, "div")
        titleDiv.className = "osada-tr-row__title"
        titleDiv.innerHTML = title
        if (detail.isNotEmpty()) {
            val detailDiv = addTag(body, "div")
            detailDiv.className = "osada-tr-row__detail"
            detailDiv.innerHTML = detail
        }
        if (pos != null) {
            rowEl.title =
                I18n.t("hud.log.jump.help", mapOf("col" to pos.col, "row" to pos.row))
            rowEl.classList.add("osada-tr-row--clickable")
            rowEl.onclick = { _: MouseEvent ->
                gameRef()?.ui?.uiSetCellOnViewPort(
                    jsObject {
                        row = pos.row
                        col = pos.col
                    },
                )
            }
        }
        return rowEl
    }

    /** Unit-class icon shared by the combat/resupply/reinforce rows: naval classes fall back to
     *  the shared replacement icon rather than each unit's own (often missing) art. */
    fun resolveUnitIcon(eqData: dynamic): String {
        val uclass = eqData.uclass as? Int ?: 0
        val baseIcon = eqData.icon as? String ?: ""
        return if (uclass > UnitClass.AIR_TRANSPORT.value) {
            UIBuilder.navalReplacementIcon
        } else {
            UnitIconResolver.forCurrentScenario(eqData.eqid as? Int ?: 0, baseIcon)
        }
    }

    fun numSpan(value: Any): String = "<span class='combatLogNum'>$value</span>"
}
