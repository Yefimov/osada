package org.osada.model

/*
 * Whether a record may be BOUGHT, by whom, and whether it may be handed out as a prototype —
 * Open General's three purchase/authoring `attr` bits, plus OSADA's own flat rule about bare
 * ground transports.
 *
 * Split out of [EquipmentCombatEligibility] on 2026-08-27, when wiring the three bits pushed that
 * file past its function budget. The split is not only arithmetic: what a record may SHOOT AT and
 * what a player may BUY are answered by different callers (`rules/AttackEligibility` against
 * `ui/EquipmentCostsCalculator`, `model/PlayerEconomy` and `ai/AIPurchasing`), and the one thing
 * that must never happen is a purchase gate quietly reaching a combat path or the reverse. The
 * `attr` bit table these masks come from stays in [EquipmentCombatEligibility]'s header, which is
 * the single place a bit's identity is recorded.
 */

/**
 * OG's `Can't Buy`, inverted: whether this record may be bought at all. **Wired 2026-08-27**, a month
 * after the `attr` table was decoded and this function corrected but left with no caller.
 *
 * **It was reading the wrong bit** (`262144`, now known to be [ATTR_MASK_CANNOT_ATTACK_NAVAL])
 * until the `attr` table above was decoded, and was then kept rather than deleted because a
 * function named `isPurchasable` that silently answered "can it shoot at ships?" is a trap for the
 * next reader. `DEFERRED.md` §7.32 left the wiring as a separate decision on the grounds that
 * *"`Can't Buy` is set on a great many scenario-only records, so honouring it would visibly shrink
 * what the player may buy in every imported campaign"*.
 *
 * **That cost is now measured rather than feared, and it is much smaller than the sentence implies.**
 * Over each efile's own availability set — the list a campaign actually offers — the bit removes
 * 1.9% (`eqp-lxf`) to 5.9% (`eqp-ag`) of the entries, with two outliers: `eqp-basekorp` 16.5% and
 * `eqp-pzliga` 23.7%, the latter backing two deployed scenarios. Those are OG's own authoring
 * decisions about its own content, and the alternative is a purchase list that offers bunkers and
 * scenario props the author marked unbuyable.
 *
 * **Purchase only — never the upgrade list.** OG's label is *"can't be bought"* and no source in
 * this project says an upgrade is a purchase; [org.osada.ui.EquipmentCatalogStrip] already lists
 * cards a player may not buy but may upgrade into (the support-country case) and explains the
 * refusal in the detail pane instead of hiding them, so this bit follows that established shape:
 * the card stays, the Buy button goes, and [org.osada.ui.EquipmentCostsCalculator] names the rule.
 * Widening it to upgrades would be an inference, and `docs/og-fidelity-plan.md` §Q.1 is the
 * standing warning against exactly that.
 */
fun Equipment.isPurchasable(eqid: Int): Boolean = (equipmentMap[eqid]?.attr?.and(ATTR_MASK_CANNOT_BUY) ?: 0) == 0

/**
 * OG's `No AI buy`, inverted: whether the computer opponent may buy this record. Wired 2026-08-27.
 *
 * A separate bit from `Can't Buy` and a strictly weaker one — a record may be bought by a human and
 * refused to the AI, which is how OG keeps prototypes, one-off scenario props and unbalanced
 * late-war equipment out of an AI shopping list without hiding them from the player. 7,065 of the
 * 56,970 merged records carry it, against 4,069 for `Can't Buy`.
 *
 * Read by `ai/AIPurchasing` alone. It must never reach a human purchase list: doing so would delete
 * a third of some efiles' catalogues on a bit that says nothing about the player.
 */
fun Equipment.isAiPurchasable(eqid: Int): Boolean = (equipmentMap[eqid]?.attr?.and(ATTR_MASK_NO_AI_BUY) ?: 0) == 0

/**
 * OG's `No Prototype`, inverted: whether this record may be handed out as the brilliant-victory
 * prototype award. Wired 2026-08-27.
 *
 * OSADA has had the prototype mechanic since the port began — `GameEndgame` sets `awardPrototype`
 * on a `briliant` outcome and `GameScenarioLoading` grants one drawn by
 * `Scenario.getRandomPrototype` from next year's Tank..Anti-Tank and Artillery..Tactical Bomber
 * records costing at least `PROTOTYPE_MIN_COST`. What it never had is OG's own opt-out, so 5,989 of
 * the 56,970 merged records the author excluded from that draw were in it.
 *
 * This is the one of the three purchase bits with an exact existing mechanic to attach to, which is
 * why it needed no new rule — only the filter it was always meant to be.
 */
fun Equipment.canBeAwardedAsPrototype(eqid: Int): Boolean =
    (equipmentMap[eqid]?.attr?.and(ATTR_MASK_NO_PROTOTYPE) ?: 0) == 0

/**
 * A bare Ground Transport is never bought as a unit of its own. A transport is acquired by
 * ATTACHING it to a unit at purchase time (`eqUserSel.eqtransport`), which this does not affect --
 * only the "buy a Horse as your combat unit" case, which has no defensible reading: it cannot
 * attack, cannot capture, and exists solely to carry something.
 *
 * **This replaced an attr-bit gate on 2026-07-26 (user request).** The previous rule permitted a
 * transport whose `attr` had bit 262144 -- which DEFERRED.md §1.5/§1.7 then recorded as
 * "purchasable" -- with a per-country fallback for countries that never set the bit. That fallback
 * did NOT fire for every country: 29 of 289 `eqp-united` countries do set the bit on a transport,
 * and country 20 (USSR) flags only 4 of its 28, refusing the other 24.
 *
 * **262144 has since been identified, and it was never a purchasability bit: it is `Can't Naval
 * Atk`** -- see this file's own `attr` table above, and DEFERRED.md §7.32/§7.44. That explains every
 * number the old note called "suspect": only 1,060 of 46,978 records carry it (2.3%) with **zero
 * Tank and zero Anti-tank** (class 2 = 0/3,024, class 4 = 0/3,186), which is nonsense for
 * purchasability and exactly right for a naval-attack prohibition.
 *
 * Purchasability is bit 7, `Can't Buy`, inverted -- `Equipment.isPurchasable` reads it and currently
 * has no caller (DEFERRED.md §7.32 item 2c). **So the standing advice is unchanged but for a new
 * reason:** do not restore an attr-based gate *here*, because no attr bit describes what this
 * function decides. The flat class rule below is the rule.
 *
 * (This comment and the file header used to contradict each other -- the header naming the bit while
 * this block called it unidentified. DEFERRED.md §7.44 asked for them to be collapsed; done
 * 2026-07-28.)
 */
fun Equipment.isPurchasableGroundTransport(eqid: Int): Boolean =
    equipmentMap[eqid]?.uclass != org.osada.UnitClass.GROUND_TRANSPORT.value
