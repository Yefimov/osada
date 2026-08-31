package org.osada.rules

import org.osada.model.Player

/**
 * Open General's **Fronts and Factions**, as the scenario actually stores them: a per-player
 * whitelist of everything the author left that player able to buy.
 *
 * > *"F/F can be used to limit the units that the player can get. Both are essentially the same,
 * > and they depend on the Efile used."*
 * > *"You can now check the desired Factions that will be available to the player to **buy new
 * > units or upgrade existing ones**."* — `Manual_OSuite-Scenario.pdf` §3.5
 *
 * ### Why this is a list and not two bitmasks
 *
 * `OPENTXT_SAMPLE/fronts.txt` defines up to 32 Fronts (`#`) and 32 Factions (`@`) **per country**,
 * and each equipment record carries a 32-bit mask of each (`equip.xeqp` `@48` and `@52`). In
 * `eqp-lxf` the Fronts are climate variants (`#CLIM Default/Snow/Desert/Jungle`) and the Factions
 * are branch groupings (`@ARMY + Mountain 30-55`, `@NAVY + Carriers + Aviation 43-55`).
 *
 * **The scenario stores neither mask.** The player record's only free run before its 40-entry
 * turn-prestige table, `+40..+48`, is zero on all 3,034 player records in the 5,848-scenario
 * corpus. What OpenSuite writes when the author ticks a Faction is the RESOLVED LIST — a `.buy4`
 * text sidecar naming every equipment code, with the Suite's own count in its header. So the
 * masks would be a badge with nothing to match against, and the list is the mechanic.
 *
 * That is also why `equip.cfg`'s `ff_mustmatch` (*"force units to land in carrier to match F/F.
 * Only use efile F/F settings for unit and carrrier/transport, **not scenario settings**"*) stays
 * unbuilt and is named in `CarrierHangars`: it is the one rule that consults the masks rather than
 * the resolved list.
 *
 * ### Shape
 *
 * **Absent means unrestricted.** 497 of the 502 deployed scenarios author no list at all, and the
 * five that do (`bn9s02`, `bn9s05`, `bn9s11`, `bn9s14`, `bn9s16`, all `eqp-lxf`) fill both sides.
 * The importer never writes an empty list, so "the author allowed nothing" is not a state this can
 * be in — 83 corpus files carry an empty section for one player and that is read as *not
 * customised*, the reading `docs/og-sources.md` step 3 prescribes when two are available.
 *
 * **Purchase AND upgrade, unlike `Can't Buy`.** The manual's own sentence names both, which is the
 * difference between this and `Equipment.isPurchasable`: that bit is worded *"can't be bought"* and
 * `docs/og-fidelity-plan.md` §Q.1 forbids widening it to upgrades on an inference. Here the source
 * says *"buy new units or upgrade existing ones"*, so both are gated.
 *
 * **No ruleset key.** A purchase list is an authored scenario decision in the same family as
 * victory hexes and escape hexes, and §AM settled that boundary: placed and authored content always
 * executes, general rules stay profile choices. A profile that suppressed it would hand the player
 * a catalogue the author had deliberately closed.
 */
object ScenarioPurchaseList {
    /** Whether [player] has an authored whitelist at all. */
    fun restricts(player: Player?): Boolean = player?.purchaseList != null

    /**
     * Whether [player] may acquire [eqid] — true whenever no list was authored.
     *
     * A transport bought with a unit is checked through this too, by the callers that check the
     * unit: a whitelist that named the tank but not its prime mover would otherwise be bypassed by
     * attaching one.
     */
    fun allows(
        player: Player?,
        eqid: Int,
    ): Boolean {
        val list = player?.purchaseList ?: return true
        return eqid <= 0 || eqid in list
    }
}
