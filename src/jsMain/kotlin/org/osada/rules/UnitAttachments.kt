package org.osada.rules

import org.osada.hero.HeroCampaign
import org.osada.model.GameUnit

/**
 * The per-unit attachment store (`docs/design/attachments.md` §3.2) for every unit OUTSIDE the hero
 * roster: all units of a standalone scenario, and in a campaign a borrowed auxiliary
 * ([GameUnit.isTemporaryBorrowed]) or any other unit with no active formation. Such a unit has no
 * `CoreFormation` to carry a purchase between battles, so the purchase is written to
 * [GameUnit.purchasedAttachmentIds] and lives exactly as long as the unit does -- an auxiliary's
 * attachments leave with it.
 *
 * A unit WITH an active formation still buys into the formation (`HeroCampaign.purchaseAttachment`),
 * so core carry-over is unchanged, and attachment eligibility never widens hero eligibility.
 */
internal object UnitAttachments {
    /** Whether [unit] buys into its own store rather than a formation's. */
    fun buyer(unit: GameUnit): Boolean = HeroCampaign.activeFormationId(unit) == null

    /**
     * Records [slotNumber] as bought on [unit]. Refuses anything [Attachments.availableSlots] would
     * not offer, the same independent re-check `HeroCampaign.purchaseAttachment` makes for a
     * formation. Prestige is the caller's concern (`Player.purchaseAttachment`).
     */
    fun purchase(
        unit: GameUnit,
        slotNumber: Int,
    ): Boolean {
        val legal = buyer(unit) && Attachments.availableSlots(unit).any { it.first == slotNumber }
        if (legal) unit.purchasedAttachmentIds = unit.purchasedAttachmentIds + slotNumber
        return legal
    }
}
