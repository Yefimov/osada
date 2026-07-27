package org.osada.ui

import org.osada.i18n.I18n
import org.osada.model.GameUnit
import org.osada.rules.Attachments
import org.osada.rules.initiativePenalty
import org.osada.rules.movementPenalty
import org.w3c.dom.HTMLElement

/**
 * Marks the unit-info stat chips an attachment is currently changing (DEFERRED.md §1.4).
 *
 * Without this the panel showed the unit's **base** equipment stats while the rules layer quietly
 * used the modified ones, so a player could not tell a kitted-out unit from a bare one anywhere
 * outside the picker — the same hidden-modifier failure `docs/design/attachments.md` §5 forbids in
 * the purchase UI, just on the other side of the transaction.
 *
 * Applied AFTER [UnitStatCard]'s base fill, so the number shown is the effective one, tinted and
 * carrying a tooltip that names the source and the delta. The localized help text lives on the
 * parent `.statsGlyph` (`GameplayLocalization:491-495`), so setting `title` on the value element
 * itself does not collide with it.
 *
 * **Ammo and fuel are deliberately not marked.** `uAmmo`/`uFuel` render a `current/max` string
 * rather than a bare number, and their max already flows through `SupplyRules`; rewriting those
 * strings here would duplicate the formatting rather than reuse it. They remain visible in the
 * picker and the service record.
 */
internal object UnitStatAttachmentMarks {
    private const val BOOSTED = "osada-stat--boosted"
    private const val REDUCED = "osada-stat--reduced"

    /** Stat chips whose value a Tier 1 attachment can move, with the delta for [unit]. */
    private fun deltas(unit: GameUnit): List<Triple<String, Int, String>> =
        listOf(
            Triple("uSpot", Attachments.bonus(unit, Attachments.SLOT_RECON), "attachments.stat.spot"),
            Triple("uAHard", Attachments.bonus(unit, Attachments.SLOT_ANTI_TANK), "attachments.stat.hardattack"),
            Triple("uAAir", Attachments.bonus(unit, Attachments.SLOT_AIR_DEFENSE), "attachments.stat.airdefence"),
            Triple(
                "uMovement",
                Attachments.bonus(unit, Attachments.SLOT_FAST_SPEED) + Attachments.movementPenalty(unit),
                "attachments.stat.movement",
            ),
            Triple("uIni", Attachments.initiativePenalty(unit), "attachments.stat.initiative"),
        )

    /** Re-applies every mark for [unit]. Always clears first: these chips are reused for whichever
     *  unit is selected, and a stale tint would attribute one unit's attachment to another. */
    fun apply(unit: GameUnit) {
        val fitted = Attachments.purchasedSlots(unit)
        deltas(unit).forEach { (id, delta, statKey) ->
            val element = byId(id) ?: return@forEach
            clear(element)
            if (delta == 0) return@forEach
            val base = element.textContent?.trim()?.toIntOrNull() ?: return@forEach
            element.textContent = (base + delta).toString()
            element.classList.add(if (delta > 0) BOOSTED else REDUCED)
            element.title =
                I18n.t(
                    "attachments.stat.modified",
                    mapOf(
                        "stat" to I18n.t(statKey),
                        "delta" to if (delta > 0) "+$delta" else delta.toString(),
                        "source" to fitted.joinToString(", ") { (_, slot) -> slot.name },
                    ),
                )
        }
    }

    private fun clear(element: HTMLElement) {
        element.classList.remove(BOOSTED)
        element.classList.remove(REDUCED)
        element.removeAttribute("title")
    }
}
