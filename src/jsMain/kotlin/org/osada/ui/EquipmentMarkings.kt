@file:Suppress("MaxLineLength")

package org.osada.ui

import org.osada.EmbarkType
import org.osada.i18n.I18n
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.abilityCatalog
import org.osada.rules.UnitCapabilities
import org.w3c.dom.HTMLElement

/**
 * Visible markings for intrinsic equipment capabilities; every badge states its exact rule.
 *
 * Two tiers, in one row. The five PRIMARY badges (HQ/RCN/OVR/SUP/AA) come first and are the ones
 * with a live combat call site behind a class default or toggle. Then, when [render] is asked for
 * the extended row, one badge per remaining OG ability the record carries
 * ([org.osada.model.abilityCatalog]) — muted when OSADA decodes but does not yet execute it.
 *
 * **The extended row exists because of a direct report:** *"It looks like you don't have badges for
 * all abilities. So it's not obvious for player."* It is off by default and asked for only where
 * there is room: the equipment window's detail panel, the unit card and the enemy card. The
 * purchase-list tile (`EquipmentCatalogStrip`) stays at the five, because its badges are stacked in
 * a `flex-direction: column` absolutely-positioned corner (`.eqUnitBox > .osada-capability-marks`)
 * that a long list would run straight out of.
 */
internal object EquipmentMarkings {
    /** Badges past this many are folded into one `+N` chip whose tooltip lists the rest, so a
     *  19-ability outlier can never push the unit card's stat bars out of the bottom zone. */
    private const val EXTENDED_BADGE_LIMIT = 8

    fun render(
        parent: HTMLElement?,
        data: EquipmentData,
        unit: GameUnit? = null,
        extended: Boolean = false,
    ) {
        if (parent == null) return
        clearTag(parent)
        // OG's `Combat Support` grant (attr bit 16) and nothing else. The name test this used to
        // OR in (`isHeadquarters`: does the name contain "HQ" or "headquarters"?) is gone: measured
        // over the 56,970 shipped records it agreed with the bit on 290, missed 1,355 that carry it
        // -- General Staff, Estado Mayor, Komissar, squadron leaders -- and INVENTED 16, of which
        // five are Chinese `HQ-1`/`HQ-2`/`HQ-7`/`HQ-17`/`Hong Qi HQ-1` surface-to-air missiles,
        // not headquarters at all. A badge states a rule the combat code will actually apply, and
        // `combatSupportBars` only ever reads the bit, so the name half was a false claim.
        val hasCombatSupport =
            unit
                ?.let { UnitCapabilities.hasCombatSupport(it) }
                ?: UnitCapabilities.grantsCombatSupport(data)
        if (hasCombatSupport) {
            addHeadquartersMark(parent, unit?.experience?.div(UnitCapabilities.EXPERIENCE_PER_BAR))
        }
        if (UnitCapabilities.hasPhasedMovement(data)) {
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
        // Paratroops. OG has no `Airborne` ability bit at all -- the capability lives in the
        // `embark` field, where 3 is `EmbarkType.AIRBORNE` and every Fallschirm/Paratrooper/VDV
        // record in the shipped data carries it. `EmbarkRules` already executes it (that is what
        // lets the unit board an air transport), it simply had no badge, so a player could not
        // see that `42 Desantny` is airborne and `42 Gvardeyskaya` is not. Reported:
        // *"Desantny and Parashutisti are VDV, shouldn't they be AIRBORNE?"*
        if (data.embark == EmbarkType.AIRBORNE.value) {
            addMark(parent, "AIR", I18n.t("equipment.mechanics.airborne"), "airborne")
        }
        if (extended) renderAbilityBadges(parent, data)
    }

    private fun renderAbilityBadges(
        parent: HTMLElement,
        data: EquipmentData,
    ) {
        val abilities = data.abilityCatalog()
        abilities.take(EXTENDED_BADGE_LIMIT).forEach { ability ->
            val modifier = if (ability.wired) "ability" else "ability-descriptive"
            addMark(parent, ability.badge, I18n.t(ability.key), modifier)
        }
        val overflow = abilities.drop(EXTENDED_BADGE_LIMIT)
        if (overflow.isNotEmpty()) {
            addMark(
                parent,
                "+${overflow.size}",
                overflow.joinToString("\n\n") { I18n.t(it.key) },
                "ability-more",
            )
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
