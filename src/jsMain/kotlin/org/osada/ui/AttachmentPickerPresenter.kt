package org.osada.ui

import org.osada.i18n.I18n
import org.osada.model.EfileConfig
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.purchaseAttachment
import org.osada.multiplayer.client.OsadaMultiplayer
import org.osada.multiplayer.command.PurchaseAttachment
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
 * **A choice is provisional until the dialog is left** (2026-09-26, owner's request). Clicking a
 * tile puts the attachment in the next free slot WITHOUT charging for it; clicking that slot takes it
 * back out; the footer shows what the choices will cost, from 0 up. Leaving the dialog by any path -- Done,
 * the close cross, Escape, or the equipment window closing under it -- buys what is in the slots.
 * That is why [close] commits rather than discards: a teardown path that silently dropped a
 * player's choices would be worse than one that honours them.
 *
 * **Attachments bought on an earlier visit are informational.** Removing one is a real OG
 * operation, but the refund rate is not documented anywhere located so far, and inventing an
 * economic rule is worse than offering no button (DEFERRED.md §1.18).
 */
internal object AttachmentPickerPresenter {
    private const val BOX_ID = "uiAttachmentBox"

    /** What the open dialog is editing: the unit, who pays, and the choices not yet bought. */
    private class Session(
        val unit: GameUnit,
        val player: Player,
        val onChanged: () -> Unit,
        val pending: MutableList<Pair<Int, EfileConfig.AttachmentSlot>> = mutableListOf(),
    ) {
        fun pendingCost(): Int = pending.sumOf { (number, _) -> Attachments.cost(unit, number) ?: 0 }

        fun prestigeLeft(): Int = player.prestige - pendingCost()
    }

    private var session: Session? = null

    /** Opens the picker for [unit]. [onChanged] runs after anything is bought so the caller can
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
        session = Session(unit, player, onChanged)
        rerender()
    }

    /** Fills the dialog from scratch after every change -- a unit has two slots, and the slot
     *  cards, the remaining choices, their affordability and the prestige line all move together. */
    private fun rerender() {
        val box = byId(BOX_ID) ?: return
        val current = session ?: return
        clearTag(box)
        buildHeader(box, current)
        buildSlotRow(box, current)
        buildChoices(box, current)
        buildFooter(box, current)
    }

    /** Leaves the dialog, BUYING whatever is in the slots -- see the class comment for why every
     *  way out commits. Safe to call when nothing is open. */
    fun close() {
        val current = session
        session = null
        delTag(byId(BOX_ID))
        if (current == null || current.pending.isEmpty()) return
        if (OsadaMultiplayer.active) {
            // One order for every pending slot: a room accepts one outstanding command at a time.
            OsadaMultiplayer.submitOrder(
                PurchaseAttachment(current.unit.id, current.pending.map { it.first }, current.player.id),
            )
        } else {
            current.pending.forEach { (number, _) -> current.player.purchaseAttachment(current.unit, number) }
        }
        current.onChanged()
    }

    /** Whether the picker is currently on screen. Read by [MainMenuButtonHandler.handleGlobalEscape],
     *  which has to close THIS before `#equipment` underneath it -- see DEFERRED.md §4.13. Presence
     *  in the DOM is the whole state: this dialog is created on open and removed on close, never
     *  hidden. */
    fun isOpen(): Boolean = byId(BOX_ID) != null

    private fun buildHeader(
        box: HTMLElement,
        current: Session,
    ) {
        val unit = current.unit
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
                mapOf(
                    "fitted" to Attachments.fittedSlots(unit).size + current.pending.size,
                    "max" to Attachments.MAX_PER_UNIT,
                ),
            )
        val closeButton = addTag(header, "span")
        closeButton.className = "osada-atp__close"
        closeButton.innerHTML = "&#10005;" // ✕
        closeButton.title = I18n.t("attachments.close.help")
        // Glyph-only, so it needs the explicit aria-label; asButton supplies the Enter/Space
        // handler the role promises (§4.14).
        closeButton.asButton(ariaLabel = I18n.t("attachments.close.help")) { close() }
    }

    /** One card per slot, `MAX_PER_UNIT` of them, filled left to right: what the unit already
     *  owns first, then this visit's choices, which can be clicked back out. */
    private fun buildSlotRow(
        box: HTMLElement,
        current: Session,
    ) {
        val row = addTag(box, "div")
        row.className = "osada-atp__slots"
        val owned = Attachments.fittedSlots(current.unit)
        val shown = owned + current.pending
        repeat(Attachments.MAX_PER_UNIT) { index ->
            val entry = shown.getOrNull(index)
            addSlotCard(row, current, index, entry, isPending = entry != null && index >= owned.size)
        }
    }

    private fun addSlotCard(
        row: HTMLElement,
        current: Session,
        index: Int,
        entry: Pair<Int, EfileConfig.AttachmentSlot>?,
        isPending: Boolean,
    ) {
        val card = addTag(row, "div")
        card.className =
            "osada-atp__slot" +
            when {
                entry == null -> " osada-atp__slot--empty"
                isPending -> " osada-atp__slot--pending"
                else -> ""
            }
        val label = addTag(card, "div")
        label.className = "osada-atp__slotlabel"
        label.textContent = I18n.t("attachments.slot.label", mapOf("n" to index + 1))
        val addHint = { text: String ->
            val hint = addTag(card, "div")
            hint.className = "osada-atp__slothint"
            hint.textContent = text
        }
        if (entry == null) {
            addHint(I18n.t("attachments.slot.empty"))
            return
        }
        val (number, slot) = entry
        val name = addTag(card, "div")
        name.className = "osada-atp__slotname"
        name.textContent = slot.name
        addEffectLines(card, current.unit, number, slot)
        if (!isPending) return
        addHint(I18n.t("attachments.slot.pending"))
        card.asButton(I18n.t("attachments.slot.pending")) {
            current.pending.removeAll { it.first == number }
            rerender()
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
        current: Session,
    ) {
        val chosen = current.pending.map { it.first }.toSet()
        val room = Attachments.fittedSlots(current.unit).size + current.pending.size < Attachments.MAX_PER_UNIT
        val options =
            if (room) Attachments.availableSlots(current.unit).filter { it.first !in chosen } else emptyList()
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
        options.forEach { (number, slot) -> addChoiceTile(grid, current, number, slot) }
    }

    private fun addChoiceTile(
        grid: HTMLElement,
        current: Session,
        slotNumber: Int,
        slot: EfileConfig.AttachmentSlot,
    ) {
        val cost = Attachments.cost(current.unit, slotNumber)
        // Against what will be LEFT once this visit's choices are paid for, so two choices can
        // never add up to more than the player has.
        val affordable = cost != null && current.prestigeLeft() >= cost
        val tile = addTag(grid, "div")
        tile.className = "osada-atp__tile" + if (affordable) "" else " osada-atp__tile--locked"
        val name = addTag(tile, "div")
        name.className = "osada-atp__tilename"
        name.textContent = slot.name
        addEffectLines(tile, current.unit, slotNumber, slot)
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
            current.pending.add(slotNumber to slot)
            rerender()
        }
    }

    private fun buildFooter(
        box: HTMLElement,
        current: Session,
    ) {
        val footer = addTag(box, "div")
        footer.className = "osada-atp__footer"
        // What this visit's choices will cost, starting at 0 and growing with each one -- the
        // number the player is agreeing to when the dialog is left (owner's request, 2026-09-26).
        val total = addTag(footer, "span")
        total.className = "osada-atp__prestige"
        total.textContent = I18n.t("attachments.total_cost", mapOf("cost" to current.pendingCost()))
        val done = addTag(footer, "span")
        done.className = "osada-atp__done"
        done.textContent = I18n.t("attachments.done")
        done.asButton { close() }
    }
}
