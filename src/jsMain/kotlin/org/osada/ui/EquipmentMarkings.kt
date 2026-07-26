@file:Suppress("MaxLineLength")

package org.osada.ui

import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.rules.UnitCapabilities
import org.w3c.dom.HTMLElement

/** Visible markings for intrinsic equipment capabilities; every badge states its exact rule. */
internal object EquipmentMarkings {
    fun render(
        parent: HTMLElement?,
        data: EquipmentData,
        unit: GameUnit? = null,
    ) {
        if (parent == null) return
        clearTag(parent)
        if (UnitCapabilities.isHeadquarters(data)) {
            addHeadquartersMark(parent, unit?.experience?.div(UnitCapabilities.EXPERIENCE_PER_BAR))
        }
        if (UnitCapabilities.hasPhasedMovement(
                data,
            )
        ) {
            addMark(parent, "RCN", UnitCapabilities.RECON_MOVEMENT_DESCRIPTION)
        }
        if (UnitCapabilities.canOverrun(data)) addMark(parent, "OVR", UnitCapabilities.TANK_OVERRUN_DESCRIPTION)
    }

    fun addHeadquartersMark(
        parent: HTMLElement,
        experienceBars: Int? = null,
    ) {
        val mark = addTag(parent, "span")
        val description =
            if (experienceBars == null) {
                UnitCapabilities.HEADQUARTERS_SUPPORT_DESCRIPTION
            } else {
                "Combat Support: currently lends $experienceBars experience bar(s) to adjacent friendly units on " +
                    "the same air/ground layer. " +
                    "Multiple Combat Support units stack. " +
                    "(Detected from the unit's name — a genuine Combat Support special or leader may be missed, " +
                    "or a unit merely named \"HQ\" may be flagged in error.)"
            }
        addMark(parent, "HQ", description, "hq", mark)
    }

    private fun addMark(
        parent: HTMLElement,
        text: String,
        description: String,
        modifier: String = text.lowercase(),
        existing: HTMLElement? = null,
    ) {
        val mark = existing ?: addTag(parent, "span")
        mark.className = "osada-capability-mark osada-capability-mark--$modifier"
        mark.textContent = text
        mark.title = description
    }
}
