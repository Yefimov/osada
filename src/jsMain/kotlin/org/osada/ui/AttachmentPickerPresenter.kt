package org.osada.ui

import org.osada.i18n.I18n
import org.osada.model.EfileConfig
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.purchaseAttachment
import org.osada.rules.Attachments
import org.w3c.dom.HTMLElement

/**
 * The attachment picker (DEFERRED.md §1.4, `docs/design/attachments.md` §5).
 *
 * **Two slot cards over a grid of choices.** The `MAX_PER_UNIT` cap is the structure of the dialog,
 * not a rule stated in prose: the player sees two slots, filled or empty, before seeing anything
 * they could buy. Every choice tile states bonus, penalty and cost *together* — §26's
 * no-hidden-modifiers rule, which a tile showing "+2 Spot" and hiding "−1 Movement" would fail.
 *
 * **Opened from the equipment window only** ([EquipmentUnitStrip]), never from the in-battle unit
 * action strip. OG allows attachment changes at initial HQ only, never during an ordinary scenario
 * turn (DEFERRED.md §1.18); the equipment window is where buying and upgrading already happen, and
 * its unit strip is the one surface that carries a real owned [GameUnit] rather than an equipment
 * TYPE — which is what §7.22 believed the window lacked.
 *
 * **Nothing here uses `.smallButton`.** That class renders its text through the `osada-menu` icon
 * font, which turns ordinary words into unrelated glyphs; the previous picker inherited it from
 * `HeroPromotionPresenter`'s DOM shape and rendered every label as gibberish. Styling is
 * `.osada-atp-*`, defined in `osada-theme.css`.
 *
 * **Fitted attachments are informational.** Removing one is a real OG operation, but the refund
 * rate is not documented anywhere located so far, and inventing an economic rule is worse than
 * offering no button (DEFERRED.md §1.18).
 */
internal object AttachmentPickerPresenter {
    private const val BOX_ID = "uiAttachmentBox"

    /** Opens the picker for [unit]. [onChanged] runs after a successful purchase so the caller can
     *  refresh whatever it is showing (the equipment window re-renders its unit strip). */
    fun open(
        unit: GameUnit,
        player: Player,
        onChanged: () -> Unit,
    ) {
        val mainBody = byId("mainbody") ?: return
        close()
        val box = addTag(mainBody, "div")
        box.id = BOX_ID
        box.className = "osada-atp"
        // Deliberately NOT makeVisible(): that helper sets `display: inline`, and `transform` does
        // not apply to a non-replaced inline element -- the `translate(-50%, -50%)` centring and
        // the width were both being discarded, so the dialog rendered mispositioned. A freshly
        // created div is already visible; it needs no help.
        render(box, unit, player, onChanged)
    }

    /** Fills [box] from scratch. Re-run in place after a purchase so the dialog survives it — a
     *  unit has two slots, and closing after the first made filling the second a second trip. */
    private fun render(
        box: HTMLElement,
        unit: GameUnit,
        player: Player,
        onChanged: () -> Unit,
    ) {
        clearTag(box)
        buildHeader(box, unit)
        buildSlotRow(box, unit)
        buildChoices(box, unit, player, onChanged)
        buildFooter(box, player, onChanged)
    }

    fun close() {
        delTag(byId(BOX_ID))
    }

    /** Whether the picker is currently on screen. Read by [MainMenuButtonHandler.handleGlobalEscape],
     *  which has to close THIS before `#equipment` underneath it — see DEFERRED.md §4.13. Presence
     *  in the DOM is the whole state: this dialog is created on open and removed on close, never
     *  hidden. */
    fun isOpen(): Boolean = byId(BOX_ID) != null

    private fun buildHeader(
        box: HTMLElement,
        unit: GameUnit,
    ) {
        val header = addTag(box, "div")
        header.className = "osada-atp__header"
        val title = addTag(header, "div")
        title.className = "osada-atp__title"
        title.textContent = unit.customName ?: unit.unitData(true).name
        val count = addTag(header, "div")
        count.className = "osada-atp__count"
        count.textContent =
            I18n.t(
                "attachments.count",
                mapOf("fitted" to Attachments.fittedSlots(unit).size, "max" to Attachments.MAX_PER_UNIT),
            )
        val closeButton = addTag(header, "span")
        closeButton.className = "osada-atp__close"
        closeButton.innerHTML = "&#10005;" // ✕
        closeButton.title = I18n.t("attachments.close.help")
        // Glyph-only, so it needs the explicit aria-label; asButton supplies the Enter/Space
        // handler the role promises (§4.14).
        closeButton.asButton(ariaLabel = I18n.t("attachments.close.help")) { close() }
    }

    /** One card per slot, `MAX_PER_UNIT` of them, filled left to right. */
    private fun buildSlotRow(
        box: HTMLElement,
        unit: GameUnit,
    ) {
        val row = addTag(box, "div")
        row.className = "osada-atp__slots"
        val fitted = Attachments.fittedSlots(unit)
        repeat(Attachments.MAX_PER_UNIT) { index ->
            val card = addTag(row, "div")
            val entry = fitted.getOrNull(index)
            card.className = "osada-atp__slot" + if (entry == null) " osada-atp__slot--empty" else ""
            val label = addTag(card, "div")
            label.className = "osada-atp__slotlabel"
            label.textContent = I18n.t("attachments.slot.label", mapOf("n" to index + 1))
            if (entry == null) {
                val hint = addTag(card, "div")
                hint.className = "osada-atp__slothint"
                hint.textContent = I18n.t("attachments.slot.empty")
            } else {
                val (number, slot) = entry
                val name = addTag(card, "div")
                name.className = "osada-atp__slotname"
                name.textContent = slot.name
                addEffectLines(card, unit, number, slot)
            }
        }
    }

    /** The bonus line and the penalty line, always both, always in the same order and weight. */
    private fun addEffectLines(
        parent: HTMLElement,
        unit: GameUnit,
        slotNumber: Int,
        slot: EfileConfig.AttachmentSlot,
    ) {
        val bonus = addTag(parent, "div")
        bonus.className = "osada-atp__bonus"
        // Bridging/Bunker Buster (Tier 2) are boolean grants, not stat deltas -- their efile
        // `bonus` column is not a magnitude to display (ATOMIC's Bridging bonus is even 0), so they
        // get a fixed "grants X" line instead of "+N stat" like every Tier 1/Fast Entrench slot.
        // previewBonus, NOT bonus, for the stat-delta case: the choice tiles describe slots the
        // unit does not own yet, and bonus() is ownership-gated, so every purchasable tile would
        // read "+0". Same scaling for both, so the preview cannot disagree with what is delivered.
        bonus.textContent =
            when (slotNumber) {
                Attachments.SLOT_BRIDGING -> I18n.t("attachments.grant.bridging")
                Attachments.SLOT_BUNKER_BUSTER -> I18n.t("attachments.grant.bunker_buster")
                else -> "+${Attachments.previewBonus(unit, slotNumber)} ${attachmentBonusStatName(slotNumber)}"
            }
        val penalty = addTag(parent, "div")
        penalty.className = "osada-atp__penalty"
        penalty.textContent = attachmentPenaltyText(slotNumber, slot)
    }

    private fun buildChoices(
        box: HTMLElement,
        unit: GameUnit,
        player: Player,
        onChanged: () -> Unit,
    ) {
        val options = Attachments.availableSlots(unit)
        val heading = addTag(box, "div")
        heading.className = "osada-atp__heading"
        heading.textContent = I18n.t("attachments.available")
        if (options.isEmpty()) {
            val empty = addTag(box, "div")
            empty.className = "osada-atp__none"
            empty.textContent = I18n.t("attachments.none")
            return
        }
        val grid = addTag(box, "div")
        grid.className = "osada-atp__grid"
        options.forEach { (number, slot) -> addChoiceTile(grid, unit, player, number, slot, onChanged) }
    }

    private fun addChoiceTile(
        grid: HTMLElement,
        unit: GameUnit,
        player: Player,
        slotNumber: Int,
        slot: EfileConfig.AttachmentSlot,
        onChanged: () -> Unit,
    ) {
        val cost = Attachments.cost(unit, slotNumber)
        val affordable = cost != null && player.prestige >= cost
        val tile = addTag(grid, "div")
        tile.className = "osada-atp__tile" + if (affordable) "" else " osada-atp__tile--locked"
        val name = addTag(tile, "div")
        name.className = "osada-atp__tilename"
        name.textContent = slot.name
        addEffectLines(tile, unit, slotNumber, slot)
        val price = addTag(tile, "div")
        price.className = "osada-atp__cost"
        // A null cost means the slot could not be priced at all -- never render that as "0",
        // which reads as "free" rather than as "unavailable" (DEFERRED.md §4.10).
        price.textContent =
            if (cost == null) {
                I18n.t("attachments.cost.unavailable")
            } else {
                I18n.t("attachments.cost", mapOf("cost" to cost))
            }
        if (!affordable) {
            tile.title = I18n.t("attachments.unaffordable.help")
            return
        }
        tile.asButton {
            if (player.purchaseAttachment(unit, slotNumber)) {
                // Stay open and re-render: the slot cards, the remaining choices, their costs and
                // the prestige line all just changed, and the unit may still have a second slot.
                // onChanged refreshes the strip BEHIND the dialog, which is a different element.
                byId(BOX_ID)?.let { render(it, unit, player, onChanged) }
                onChanged()
            }
        }
    }

    private fun buildFooter(
        box: HTMLElement,
        player: Player,
        onChanged: () -> Unit,
    ) {
        val footer = addTag(box, "div")
        footer.className = "osada-atp__footer"
        val prestige = addTag(footer, "span")
        prestige.className = "osada-atp__prestige"
        prestige.textContent = I18n.t("attachments.prestige", mapOf("prestige" to player.prestige))
        val done = addTag(footer, "span")
        done.className = "osada-atp__done"
        done.textContent = I18n.t("attachments.done")
        done.asButton {
            close()
            onChanged()
        }
    }
}
