@file:Suppress("MaxLineLength")

package org.osada.ui

import org.osada.i18n.I18n
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
            addMark(parent, "RCN", I18n.t("equipment.mechanics.recon_movement"))
        }
        if (UnitCapabilities.canOverrun(data)) addMark(parent, "OVR", I18n.t("equipment.mechanics.tank_overrun"))
        // Both read the same predicates CombatResolver.isSupportFireEligible uses, so a badge can
        // never claim a defensive-fire role the combat code would not actually grant.
        if (UnitCapabilities.hasSupportFire(data)) {
            addMark(parent, "SUP", I18n.t("equipment.mechanics.support_fire"))
        }
        if (UnitCapabilities.hasAirDefenceFire(data)) {
            addMark(parent, "AA", I18n.t("equipment.mechanics.anti_air"))
        }
    }

    fun addHeadquartersMark(
        parent: HTMLElement,
        experienceBars: Int? = null,
    ) {
        val mark = addTag(parent, "span")
        val description =
            if (experienceBars == null) {
                I18n.t("equipment.mechanics.headquarters")
            } else {
                I18n.plural("equipment.mechanics.headquarters_bars", experienceBars)
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
