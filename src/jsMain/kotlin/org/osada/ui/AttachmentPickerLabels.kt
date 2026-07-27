package org.osada.ui

import org.osada.i18n.I18n
import org.osada.model.EfileConfig
import org.osada.rules.Attachments
import org.osada.rules.PENALTY_AMMO
import org.osada.rules.PENALTY_FUEL
import org.osada.rules.PENALTY_INITIATIVE
import org.osada.rules.PENALTY_MOVEMENT
import org.osada.rules.effectiveMalusType

/*
 * The attachment picker's two label formatters, split from AttachmentPickerPresenter purely to
 * keep that object inside the project's function-count limit -- the same treatment
 * `AttachmentPenalties.kt` gives `Attachments` and `TerrainMovementCost.kt` gives `TerrainEx`.
 * Nothing but the picker should call these.
 */

/** Which stat this slot's bonus raises. Keyed on the slot NUMBER, never the efile's display name —
 *  ATOMIC's "Ammunition" and LXF's "Support" are one mechanic (`OG_ABILITY_AUDIT.md` §4). */
internal fun attachmentBonusStatName(slotNumber: Int): String =
    when (slotNumber) {
        Attachments.SLOT_RECON -> I18n.t("attachments.stat.spot")
        Attachments.SLOT_AIR_DEFENSE -> I18n.t("attachments.stat.airdefence")
        Attachments.SLOT_ANTI_TANK -> I18n.t("attachments.stat.hardattack")
        Attachments.SLOT_SUPPORT_AMMO -> I18n.t("attachments.stat.ammo")
        Attachments.SLOT_FAST_ENTRENCH -> I18n.t("attachments.stat.entrenchment")
        Attachments.SLOT_FUEL_PODS -> I18n.t("attachments.stat.fuel")
        Attachments.SLOT_FAST_SPEED -> I18n.t("attachments.stat.movement")
        else -> I18n.t("attachments.stat.unknown")
    }

/**
 * The penalty line. Resolves the malus type through [effectiveMalusType], so a slot whose efile
 * omits the column shows the documented default rather than "no penalty" — the same correction the
 * rules layer makes (DEFERRED.md §1.19).
 */
internal fun attachmentPenaltyText(
    slotNumber: Int,
    slot: EfileConfig.AttachmentSlot,
): String {
    val stat =
        when (effectiveMalusType(slotNumber, slot.penaltyType)) {
            PENALTY_MOVEMENT -> I18n.t("attachments.stat.movement")
            PENALTY_INITIATIVE -> I18n.t("attachments.stat.initiative")
            PENALTY_AMMO -> I18n.t("attachments.stat.ammo")
            PENALTY_FUEL -> I18n.t("attachments.stat.fuel")
            else -> null
        }
    return if (slot.penalty == 0 || stat == null) I18n.t("attachments.penalty.none") else "${slot.penalty} $stat"
}
