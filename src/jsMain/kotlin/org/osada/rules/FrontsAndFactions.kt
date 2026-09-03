package org.osada.rules

import org.osada.model.EfileConfig
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.Player

/**
 * Open General's **Fronts and Factions** — the two 32-bit masks that decide which equipment a
 * scenario admits, and which cargo a transport, carrier or container will take.
 *
 * > *"Any unit having front=zero is compatible with any other front, and same for faction"*
 * > — `luis-guzman.com/OpenGen_Features.html`, "Fronts and Factions"
 *
 * `OPENTXT_SAMPLE/fronts.txt` declares up to 32 Fronts (`#`) and 32 Factions (`@`) **per country**;
 * every equipment record carries a mask of each (`equip.xeqp` `@48`/`@52`), and every scenario
 * carries one of each **per player, per country slot** (`.xscn` `849..1008`). **208 of the 397
 * OpenGen-header scenarios OSADA deploys author a mask**, against the five that ship a `.buy4`.
 *
 * ## The matcher is context-free, and deliberately so
 *
 * [matches] knows nothing about countries, dates, purchasability or `.buy4`. Those are
 * **purchase-only** conditions, while this same matcher also answers whether a formation may board
 * a transport or enter a carrier — folding a purchase filter into it would quietly refuse
 * embarkations. Each action composes the matcher with its own conditions instead:
 *
 * * purchasing → [admitsForPurchase], layered under `Player.buyUnit`'s existing country, date,
 *   `Can't Buy` and `rules/ScenarioPurchaseList` checks;
 * * boarding a transport / entering a container → [cargoMatchesCarrier], which is `equip.cfg`'s
 *   `ff_mustmatch` and uses the EQUIPMENT masks on both sides.
 *
 * ## Two different pairings, and they must not be confused
 *
 * * **unit mask vs SCENARIO mask** is the purchase/upgrade rule. It is what the author ticks in
 *   OpenSuite's "Set F/F.." dialog.
 * * **unit mask vs CARRIER mask** is `ff_mustmatch`: *"force units to land in carrier to match F/F.
 *   Only use efile F/F settings for unit and carrrier/transport, **not scenario settings**"*
 *   (`EFILE_NOKORP/equip.cfg`). Its own wording excludes the scenario masks. `CarrierHangars` named
 *   this as unbuilt on the grounds that the masks did not exist; they did — the equipment ones were
 *   available all along, and it is the scenario ones that arrived in 2026-09.
 *
 * ## What the rule was measured against
 *
 * `tools/og-import/verify_fronts_factions.py` re-runs the check against the five deployed scenarios
 * that also ship a hand-edited `.buy4`, **from the shipped data rather than the source binaries** —
 * so it validates the two importers as well as the rule. 678 listed entries, **0 missing** and 30
 * extra. Missing is the number that has to stay zero: it would mean the rule taking away equipment
 * the author's own resolved list gives. Seven of the extras are `Train`/`GAZ-64`, which OG excludes
 * as pool classes and OSADA refuses separately as bare ground transports; the other 23 are records
 * the author trimmed from the list by hand, and all five of those scenarios deploy the list too, so
 * `rules/ScenarioPurchaseList` intersects them away. The masks are what serve the other 203
 * scenarios, which have no list at all.
 *
 * ## What "no data" means, everywhere
 *
 * Absent attribute, absent slot, and a zero mask all mean **allowed**. A record with no OG source
 * ships `-1` and [EquipmentData] normalises it to the wildcard; a player with no restricted slot
 * gets no `ff` attribute at all. Nothing here can refuse a purchase for lack of information.
 */
object FrontsAndFactions {
    /**
     * The rule, in one expression: **zero is a wildcard on both sides**.
     *
     * Pure and context-free — no player, no scenario, no equipment record. Everything else in this
     * file composes this with conditions of its own.
     */
    fun matches(
        unitMask: Int,
        allowedMask: Int,
    ): Boolean = unitMask == 0 || allowedMask == 0 || (unitMask and allowedMask) != 0

    /**
     * Whether [player]'s authored Fronts/Factions admit equipment record [eqid] for PURCHASE or
     * UPGRADE — *"available to the player to buy new units **or upgrade existing ones**"*.
     *
     * Composed of exactly three things and nothing else:
     *
     * 1. the slot's country must be the record's country, because OG's masks are declared per
     *    country and slot 3's bit 4 means nothing to slot 1's nation;
     * 2. [matches] on Fronts;
     * 3. [matches] on Factions.
     *
     * **A record whose country no slot names is ALLOWED here.** Country eligibility is a separate,
     * older rule (the campaign's own nation, the player's support countries) enforced elsewhere;
     * answering it a second time from this side would be two rules disagreeing about one question.
     *
     * **Any compatible slot admits.** OG permits the same country in more than one support slot, so
     * the slots are searched rather than looked up — see [org.osada.model.FrontFactionSlot].
     */
    fun admitsForPurchase(
        player: Player?,
        eqid: Int,
    ): Boolean {
        val record = if (eqid > 0) Equipment.getEquipment(eqid) else null
        val forCountry =
            record
                ?.let { eq ->
                    player?.frontFactionSlots?.filter { it.country == eq.country }
                }.orEmpty()
        // No record, no slots, or no slot for this record's country: F/F says nothing, and the
        // separate country rule is what answers instead.
        return forCountry.isEmpty() ||
            forCountry.any {
                matches(record!!.fronts, it.fronts) && matches(record.factions, it.factions)
            }
    }

    /**
     * OG's **pool classes**: the transport types a scenario hands out through a per-player POOL
     * rather than a shop.
     *
     * The resolution rule that reproduces OG's own `.buy4` files reads *"class not in {8, 14, 15,
     * 20}"* — Rail, Air, Helo and Naval Transport in `strings_efile.txt`'s `[classes]`, which are
     * exactly the four pools at player record `+19..+22`. Those four classes appear **zero times**
     * in the 987 entries of the five deployed `.buy4` files, while Ground Transport appears 90
     * times. Without the filter the wildcard reading admits 6-9 transports per scenario that OG's
     * own resolved list excludes.
     *
     * **OSADA has two of the four as classes.** Its rail transport is folded into Ground Transport
     * (`rules/RailTransport` explains why) and it has no helo class at all, so the OG set reduces to
     * `AIR_TRANSPORT` and `NAVAL_TRANSPORT`. Ground Transport is purchasable in OG too, and OSADA
     * already refuses a BARE one for its own reason
     * (`Equipment.isPurchasableGroundTransport`).
     */
    private fun isPoolClass(record: EquipmentData?): Boolean =
        record?.uclass == org.osada.UnitClass.AIR_TRANSPORT.value ||
            record?.uclass == org.osada.UnitClass.NAVAL_TRANSPORT.value

    /**
     * Whether [player] may buy a pool-class transport at all.
     *
     * **Gated on the scenario having AUTHORED its pools**, which is what
     * [Player.transportPoolsAuthored] records. An OG scenario always writes the three pool
     * attributes — zero included — and its air and naval transports come from those pools, so
     * offering them in the shop as well would hand the player a second, unlimited source the author
     * did not provide. Panzer Marshal's own inherited campaigns (adlerkorps, pacific) author no
     * pools at all and have always bought transports; reading their silence as OG's zero would
     * remove air transport from those campaigns entirely, which is the failure mode
     * `docs/design/efile-config.md` §2 trap 4 names.
     */
    fun poolClassPurchasable(
        player: Player?,
        eqid: Int,
    ): Boolean {
        if (player?.transportPoolsAuthored != true) return true
        return !isPoolClass(Equipment.getEquipment(eqid))
    }

    /**
     * `equip.cfg`'s **`ff_mustmatch`** — *"force units to land in carrier to match F/F. Only use
     * efile F/F settings for unit and carrrier/transport, **not scenario settings**"*.
     *
     * EQUIPMENT masks on both sides, per that sentence: the cargo's own Fronts/Factions against the
     * carrier's or transport's. Off by default and off in every shipped efile, so this returns true
     * everywhere until an efile turns it on — which is the direction that refuses nothing.
     */
    fun cargoMatchesCarrier(
        passenger: GameUnit?,
        carrier: GameUnit?,
    ): Boolean {
        val enforced = EfileConfig.intKey("ff_mustmatch", 0) != 0
        val cargo = if (enforced) passenger?.unitData(true) else null
        val hull = if (enforced) carrier?.unitData(true) else null
        // Key off, or either side unknown: nothing to compare, and the direction that refuses
        // nothing is the right one for a key no shipped efile sets.
        return cargo == null ||
            hull == null ||
            (matches(cargo.fronts, hull.fronts) && matches(cargo.factions, hull.factions))
    }
}
