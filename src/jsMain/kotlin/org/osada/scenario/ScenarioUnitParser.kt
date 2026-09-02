package org.osada.scenario

import org.osada.model.GameUnit
import org.osada.model.Leaders
import org.osada.model.getPlayer
import org.osada.model.setTransport
import org.osada.rules.GameRules
import org.osada.rules.calculateUnitCosts
import org.osada.rules.isSea
import org.w3c.dom.Element

/**
 * [ScenarioLoader]'s `<unit>` element parser, shared by [ScenarioReinforcementParser] and
 * [ScenarioHexParser]. Split out purely to keep those objects within the project's
 * function-count/class-size limits.
 */
internal object ScenarioUnitParser {
    fun parse(
        el: Element,
        scenario: Scenario,
    ): GameUnit? {
        val eqid = el.getAttribute("id")?.toIntOrNull()
        val owner = el.getAttribute("owner")?.toIntOrNull()
        val isValid = eqid != null && owner != null && eqid >= 0 && owner >= 0
        if (!isValid) return null
        val unit = GameUnit(eqid)
        unit.owner = owner
        applyUnitAttributes(el, unit, scenario)
        applyCostAccounting(unit, owner, scenario)
        return unit
    }

    private fun applyUnitAttributes(
        el: Element,
        unit: GameUnit,
        scenario: Scenario,
    ) {
        el.getAttribute("face")?.toIntOrNull()?.let { unit.facing = it }
        el.getAttribute("flag")?.toIntOrNull()?.let { unit.flag = it }
        el.getAttribute("transport")?.toIntOrNull()?.let { unit.setTransport(it) }
        el.getAttribute("carrier")?.toIntOrNull()?.let { unit.carrier = it }
        el.getAttribute("exp")?.toIntOrNull()?.let { unit.experience = it }
        el.getAttribute("ent")?.toIntOrNull()?.let { unit.entrenchment = it }
        el.getAttribute("str")?.toIntOrNull()?.let { unit.strength = it }
        unit.isTemporaryBorrowed = el.getAttribute("temporaryBorrowed")?.toBooleanStrictOrNull() ?: false
        // Scripted non-combatants (detainees, refugees, civilian columns) are not formations: their
        // destruction must not be filed as an equipment loss, and surviving must not enrol them in
        // the campaign core. Absent attribute keeps every existing scenario's behaviour unchanged.
        unit.nodossier = el.getAttribute("nodossier")?.toBooleanStrictOrNull() ?: false
        // OG's scenario-designated Depot (`GameUnit.isScenarioDepot`), recovered 2026-08-29 from
        // `.xscn` unit @50 bit 1. Authored data, read unconditionally; `DepotSupply` decides
        // whether it does anything.
        el.getAttribute("depot")?.toIntOrNull()?.let { unit.isScenarioDepot = it != 0 }
        applyBasicStrength(el, unit, scenario)
        applyAuthoredSupply(el, unit)
        applyAuthoredAiOrders(el, unit)
        applyAuthoredRoles(el, unit)
        applyAuthoredLeader(el, unit)
    }

    /**
     * The two roles a scenario author may give a placed formation.
     *
     * * `msu` -- OG's Must-Survive Unit (manual 3.7.1, `.xscn` unit `@43` bit 0), already gated on
     *   the scenario's AllowMSU switch by the importer, so a bare attribute means the author meant
     *   it.
     * * `core` -- OG's **Make Core** tick (`@44` bit 2), the author enrolling this formation in the
     *   campaign core. Setting the flag is only half of it; `CoreUnitListOperations` owns the
     *   enrollment that makes it survive the scenario transition.
     */
    private fun applyAuthoredRoles(
        el: Element,
        unit: GameUnit,
    ) {
        el.getAttribute("msu")?.toIntOrNull()?.let { unit.mustSurvive = it != 0 }
        el.getAttribute("core")?.toIntOrNull()?.let { unit.isCore = it != 0 }
    }

    private fun applyAuthoredLeader(
        el: Element,
        unit: GameUnit,
    ) {
        if (el.hasAttribute("ldr")) {
            // OG's AUTHORED **individual** leader attribute (`.xscn` unit @36, the Suite's
            // "According list of leaders" selector), remapped from OG's numbering to `LeaderType`
            // by `tools/og-import/add_leader_traits.py`. Before this the attribute's presence alone
            // was read and the ability was ROLLED, so every authored leader had the wrong one.
            //
            // It must be @36 and never @37. `HeroTraitResolver.legacyHasTrait` grants this field
            // AND `Leaders.getUnitClassLeader(unit)` separately, and `generateLeader` rolls from
            // index 1 up so it can never return the class attribute — so this field is the
            // individual one. Putting @37 here would collapse the resolver's two disjuncts and cost
            // the formation a trait, which is what happened for one day in 2026-09-01's first
            // deployment.
            //
            // Absent or unmappable falls back to the roll, which is what OG's own "According list
            // of leaders" default means anyway.
            unit.leader =
                el.getAttribute("ldrtrait")?.toIntOrNull()?.takeIf { it > 0 }
                    ?: Leaders.generateLeader(unit)
            // OG's CLASS attribute (`@37`, the Suite's "According unit's class"), the second half of
            // the authored pair. It OVERRIDES `Leaders.getUnitClassLeader`'s derivation rather than
            // adding a third trait — see `GameUnit.leaderClassTrait`. Zero is OG's own default
            // ("derive it"), so only a positive value is stored.
            unit.leaderClassTrait =
                el.getAttribute("ldrclasstrait")?.toIntOrNull()?.takeIf { it > 0 } ?: -1
        }
    }

    /**
     * OG's authored **AI orders** -- OpenSuite's "Unit settings" panel, `.xscn` unit `@45`, `@50`,
     * `@56`, `@58`, `@59`, `@62`, `@64`.
     *
     * Placed content, read unconditionally: none of these bytes is gated on a scenario switch, and
     * `tools/og-import/add_ai_orders.py` writes an attribute only where the byte says something.
     * What the fields DO -- and the decision that they constrain OSADA's planner rather than replace
     * it -- is `rules/AiOrders`.
     *
     * The objective hex is stored 0-based like every other coordinate in the deployed XML, so -1 is
     * "no objective" and 0,0 is a legal hex. OG cannot tell those two apart (its own unset value IS
     * 0,0); the importer resolves it there rather than here.
     */
    private fun applyAuthoredAiOrders(
        el: Element,
        unit: GameUnit,
    ) {
        el.getAttribute("anchored")?.toIntOrNull()?.let { unit.aiAnchored = it != 0 }
        el.getAttribute("holduntil")?.toIntOrNull()?.let { unit.aiHoldUntilTurn = it }
        el.getAttribute("fearless")?.toIntOrNull()?.let { unit.aiFearless = it != 0 }
        el.getAttribute("objcol")?.toIntOrNull()?.let { unit.aiObjectiveCol = it }
        el.getAttribute("objrow")?.toIntOrNull()?.let { unit.aiObjectiveRow = it }
        el.getAttribute("freeoh")?.toIntOrNull()?.let { unit.aiFreeObjectiveDistance = it }
        el.getAttribute("objfrom")?.toIntOrNull()?.let { unit.aiObjectiveFromOrdinal = it }
        el.getAttribute("followpos")?.toIntOrNull()?.let { unit.aiFollowsObjectiveUnit = it != 0 }
        el.getAttribute("ordinal")?.toIntOrNull()?.let { unit.aiOrdinal = it }
        // OG's authored ATTACHMENTS (`@40`/`@41`) and the author's own veto on them (`@50` bit 3).
        // The ids are per EFILE; `rules/Attachments` resolves them against the active `equip.cfg`
        // and drops anything that efile does not define.
        unit.authoredAttachmentIds =
            el
                .getAttribute("attach")
                ?.split(",")
                ?.mapNotNull { it.trim().toIntOrNull() }
                ?.filter { it > 0 }
                .orEmpty()
        el.getAttribute("noattach")?.toIntOrNull()?.let { unit.attachmentsForbidden = it != 0 }
    }

    /**
     * OG's Basic Strength (`GameUnit.basicStrength`, scenario unit `@23`) and the reset its own
     * option controls.
     *
     * > *"Use current / basic strength as defined (**so no reset current to basic**)"* — the
     * > OpenSuite report's own gloss on `opt_use_basic_strength`
     *
     * So the option ON leaves both values as the author wrote them, and OFF resets
     * `current := basic` — which makes **OFF the harsher setting**, since a formation authored
     * `10/5` starts at 5 there. 332 of the 397 deployed scenarios whose source parses set it.
     *
     * A scenario whose source could not be read is `null` and keeps its authored current strength,
     * the direction that takes nothing from the player.
     */
    private fun applyBasicStrength(
        el: Element,
        unit: GameUnit,
        scenario: Scenario,
    ) {
        val basic = el.getAttribute("bstr")?.toIntOrNull() ?: return
        unit.basicStrength = basic
        // Read off the scenario BEING LOADED, never off `GameHolder`: during load the holder still
        // points at the previous scenario (or at nothing), so a unit would be reset according to
        // the last battle's option rather than this one's.
        if (scenario.useBasicStrength == false) {
            unit.strength = basic
        }
    }

    /**
     * OG's **authored fuel and ammunition** — `opt_use_fuel` / `opt_use_ammo`, scenario unit `@28`
     * and `@29`.
     *
     * > *"use fuel as defined"*, *"use ammo as defined"* — options bitfield `1009` bits 2 and 3
     *
     * Byte 1009 is OG's *"what a placed unit starts with"* byte and its neighbours are already
     * built: `opt_default_xp` (bit 0), `opt_use_basic_strength` (bit 1), `opt_allow_default_str`
     * (bit 7). *"As defined"* means here what it means there — the value the author wrote stands
     * instead of the record's own maximum. 94 of the 397 deployed scenarios whose source parses set
     * the fuel bit and 98 the ammo bit; 959 placed formations across 69 of them start short.
     *
     * **The attribute is only written when the value is BELOW the maximum**, and the importer does
     * the gating on the scenario's own switch, so an absent attribute means exactly what it always
     * meant: this formation starts with a full load. `tools/og-import/add_unit_supply.py` carries
     * the reasoning, including why 3,033 units authored ABOVE their record's maximum are dropped
     * rather than honoured.
     *
     * `halfshot` is `@29` bit 0 — OG stores ammunition doubled, so an odd value is a half shot
     * already spent ([org.osada.model.GameUnit.halfShotPending]). Rounding it away would hand the
     * formation a free attack.
     */
    private fun applyAuthoredSupply(
        el: Element,
        unit: GameUnit,
    ) {
        el.getAttribute("fuel")?.toIntOrNull()?.let { unit.fuel = it }
        el.getAttribute("ammo")?.toIntOrNull()?.let { unit.ammo = it }
        el.getAttribute("halfshot")?.toIntOrNull()?.let { unit.halfShotPending = it != 0 }
    }

    private fun applyCostAccounting(
        unit: GameUnit,
        owner: Int,
        scenario: Scenario,
    ) {
        if (GameRules.isSea(unit)) return
        val unitSide = scenario.map.getPlayer(owner).side
        scenario.unitsCostPerSide[unitSide] += GameRules.calculateUnitCosts(unit.eqid, unit.transport?.eqid ?: -1)
        scenario.expPerSide[unitSide].exp = (scenario.expPerSide[unitSide].exp as Int) + unit.experience
        scenario.expPerSide[unitSide].count = (scenario.expPerSide[unitSide].count as Int) + 1
    }
}
