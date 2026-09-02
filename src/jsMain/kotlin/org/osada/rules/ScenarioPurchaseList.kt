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
 * ### The masks, the resolved list, and which of the two this models
 *
 * `OPENTXT_SAMPLE/fronts.txt` defines up to 32 Fronts (`#`) and 32 Factions (`@`) **per country**,
 * and each equipment record carries a 32-bit mask of each (`equip.xeqp` `@48` and `@52`). In
 * `eqp-lxf` the Fronts are climate variants (`#CLIM Default/Snow/Desert/Jungle`) and the Factions
 * are branch groupings (`@ARMY + Mountain 30-55`, `@NAVY + Carriers + Aviation 43-55`).
 *
 * **CORRECTION 2026-09-01 — this doc used to say the scenario stores neither mask. It stores both.**
 * The measurement behind the old claim was right and the conclusion was not: the player record's
 * only free run before its 40-entry turn-prestige table, `+40..+48`, really is zero on all 3,034
 * player records, but the masks are not in the player record. They sit in a fixed header block that
 * tiles `849..1008` exactly — `fronts` = 20 × u32 at 849, `factions` = 20 × u32 at 929, indexed
 * `player * 5 + countrySlot`, where slot k is the player record's country byte `+7+k`. Every part
 * of that index is measured by a controlled diff, not inferred. See
 * `tools/og-import/SCENARIO_FORMAT_NOTES.md` for the diffs and the resolution rule.
 *
 * What OpenSuite ALSO writes when the author ticks a Faction is the RESOLVED LIST — a `.buy4` text
 * sidecar naming every equipment code, with the Suite's own count in its header. **That list is
 * what this class models, and it stays the right thing to model here**: `.buy4` is hand-editable
 * and is layered on top of the masks rather than being a dump of them (`bn9s16` excludes
 * `SU-76 CS`, which the mask rule admits and which carries no `Can't Buy`).
 *
 * **The masks themselves are imported and read since 2026-09-01** — [FrontsAndFactions], on 208
 * deployed scenarios against these five. They live in the runtime rather than in this list because
 * F/F also gates transport, carrier and container boarding, not just purchasing, and because a
 * static list cannot express a matcher. The two compose: `Player.buyUnit` asks both, so the author's
 * hand edits still narrow what the masks admit.
 *
 * `equip.cfg`'s `ff_mustmatch` (*"force units to land in carrier to match F/F. Only use efile F/F
 * settings for unit and carrrier/transport, **not scenario settings**"*) is built too, and NOT for
 * the reason this doc used to give: its own wording excludes the scenario masks, so it wants the
 * EQUIPMENT masks (`equip.xeqp` `@48`/`@52`), which were available all along
 * ([FrontsAndFactions.cargoMatchesCarrier]).
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
