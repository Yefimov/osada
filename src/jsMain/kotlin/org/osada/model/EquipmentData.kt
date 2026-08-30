package org.osada.model

import kotlin.js.Json

/**
 * [EquipmentData.railTransportable] for a record OG never spoke about — a Panzer Marshal stock
 * roster, or one the merge could not trace back to a source efile.
 *
 * Distinct from 0, which is OG positively saying *"this cannot be entrained"*. The two must not
 * collapse: 4,140 shipped records carry no data, and reading them as a refusal would strip the
 * railway from content that never opted out of it.
 */
const val RAIL_UNKNOWN: Int = -1

@JsExport
@JsName("EquipmentData")
class EquipmentData {
    /** Stable merged-equipment id. The loader assigns it from the JSON object's row key. */
    var eqid: Int = 0
    var gunrange: Int = 0
    var icon: String = ""
    var yearexpired: Int = 0
    var cost: Int = 0
    var initiative: Int = 0
    var spotrange: Int = 0
    var hardatk: Int = 0
    var softatk: Int = 0
    var uclass: Int = 0
    var airdef: Int = 0
    var fuel: Int = 0
    var airseaweight: Int = 0
    var rangedefmod: Int = 0
    var airatk: Int = 0
    var groundweight: Int = 0
    var movmethod: Int = 0
    var navalatk: Int = 0
    var movpoints: Int = 0
    var grounddef: Int = 0
    var target: Int = 0
    var yearavailable: Int = 0
    var name: String = ""
    var country: Int = 0
    var closedef: Int = 0
    var ammo: Int = 0
    var attr: Int = 0
    var embark: Int = 0

    /**
     * OG's `Special4` byte, added 2026-08-19 (`docs/og-fidelity-plan.md` §C). Bits 0..7:
     * Build/Repair, Dismount After Move, No Dirt Airfields, Rocket Bomber, Cut LOS, Allow LOF,
     * No ZOC, Evade. Defaults to 0 ("no data imported") for any equipment JSON whose parsehints
     * don't carry it -- see [EquipmentCombatEligibility.kt] for the full bit table and which of
     * these are read by anything today.
     */
    var attr2: Int = 0

    /**
     * OG's `SpecialEx` bytes 0..2, packed the same way `attr` packs `Special1..3`
     * (`byte0 + (byte1 shl 8) + (byte2 shl 16)`), added 2026-08-19. Bits 0..23: No Leader,
     * Lasting Sup., All Weather, Overrun toggle, No Ammo penalty, No Intercept Air, Clear mines,
     * NoNeedStation, Torpedo bomber, Counter Battery, Partizan, Exploit Success, Anti Sub (ASW),
     * AD Support, *(unused)*, SingleFireSup., Kamikaze, AirDropMines, Saboteur, Jet (Stealth),
     * Supply Unit, *(unused x3)*. Defaults to 0, same rule as [attr2].
     */
    var attrEx: Int = 0

    /**
     * OG's **Bomber Size** (`equip.xeqp` @22, OpenSuite's `BombCode` column), imported 2026-08-26.
     *
     * It is what gates OG's `Can bombard/barrage` ability — the `'='` mark the game's own
     * `tips1.txt` tells the player to look for — and it is **not** one of the 52 special bits, which
     * is why the ability was filed as "blocked on an unknown bit" until the owner checked in OG
     * itself (`docs/og-fidelity-plan.md` §Q.2). Every LXF Level Bomber and every Battleship carries
     * a non-zero value; 6,872 of the 56,970 merged records do.
     *
     * Defaults to 0 for the 4,271 records whose pre-merge source could not be re-identified and for
     * Panzer Marshal's own stock rosters — "no data", the same rule [attr2] follows.
     */
    var bombsize: Int = 0

    /**
     * OG's **Hangar Capacity** (`equip.xeqp` @120, OpenSuite's `HangarCap` column), imported
     * 2026-08-27 — how many aircraft a carrier holds (manual §9.7).
     *
     * `docs/og-fidelity-plan.md` §M recorded carrier capacity as absent for exactly one reason:
     * the byte *"is dumped to CSV by `tools/og-import/xeqp_to_csv.py` and is not deployed into the
     * game's equipment data"*. It is now, by `tools/eqp-merge/add_hangar_capacity.py`.
     *
     * **The offset was confirmed by population before anything read it**, the same test §Q.2
     * applied to [bombsize]: over the eleven OG efiles this project can read, **266 of 322 carrier
     * records carry a value of 1..6, and every one of the seven efiles that authors the field at
     * all does so for 100% of its carriers.** The 56 that carry none are `eqp-olgcw` and
     * `eqp-olgww2`, the two up-converted old-format efiles whose ability bytes §J.2 already showed
     * to be manufactured rather than authored — so their silence is the same known gap, not a
     * counter-example. `CV` = 6, `CVL` = 3, `CVE` = 2 in the shipped data, which is the right
     * shape for fleet, light and escort carriers.
     *
     * Defaults to 0 for the 4,271 records whose pre-merge source could not be re-identified and for
     * Panzer Marshal's own stock rosters — "no data", the same rule [attr2] and [bombsize] follow,
     * and the reason `rules/CarrierHangar` never reads 0 as *"this ship has no hangar"*.
     */
    var hangarCap: Int = 0

    /**
     * OG's `RTP?` — *"Units be configured to use Train Transport"* (`equip.xeqp` byte @38 bit 3).
     *
     * The author's Features page lists four things a scenario needs before trains run, and this is
     * the fourth: a train, a station, track, **and units permitted to use it**. 62.9% of the
     * corpus's 198,037 records carry it, and it discriminates inside the ground classes rather than
     * merely separating ground from sea — Infantry 94.0%, Tank 94.3%, Artillery 86.2%, but
     * **AirDefence 27.0% and Fortification 31.3%**, against 0–1% for every naval and air class.
     * Emplaced guns and permanent works mostly cannot be entrained, and OG says so per record.
     *
     * **Three-valued, and [RAIL_UNKNOWN] is not a synonym for "no".** Deployed by
     * `tools/eqp-merge/add_rail_transportable.py`, which leaves the sentinel on the 4,140 Panzer
     * Marshal stock records that have no OG source at all. Reading those as "cannot rail" would
     * silently strip the mechanic from content OG never spoke about, so [canUseRailTransport] reads
     * unknown as PERMITTED — `docs/og-sources.md`: a RULE-level permission reads silence as
     * permission, and only a sub-option that ADDS an obstruction reads silence as false.
     */
    var railTransportable: Int = RAIL_UNKNOWN

    /**
     * OG's other three non-organic transport permissions — `equip.xeqp` `@38` bits 0, 1 and 2.
     *
     * **They exist because [embark] cannot hold them.** `embark` is an ORDINAL (Para 3 > ATP 2 >
     * NTP 1), so a record carrying more than one keeps only the highest — and **40,375 of the
     * 198,037 shipped records carry more than one**. Measured on the deployed rosters, the ordinal
     * was losing naval transportability for **15,037 records** and air for 1,739: a marine
     * battalion OG lets ride either a landing craft or a transport aircraft could board only the
     * aircraft.
     *
     * Exactly the defect §AF.1 found for rail and fixed the same way — a field per permission
     * rather than one ordinal pretending to be four. Deployed by
     * `tools/eqp-merge/add_transport_permissions.py` 2026-08-30.
     *
     * Three-valued like [railTransportable]: [RAIL_UNKNOWN] is "OG said nothing", which
     * `EmbarkRules` reads as permitted, and 0 is OG saying no. [embark] is deliberately left in
     * place and unchanged — the purchase UI, the AI and the save format all read it.
     */
    var navalTransportable: Int = RAIL_UNKNOWN

    var airTransportable: Int = RAIL_UNKNOWN

    var paraDroppable: Int = RAIL_UNKNOWN

    /** Whether OG permits this equipment to be carried by rail. Unknown counts as permitted; see
     *  [railTransportable] for why that direction rather than the other. */
    fun canUseRailTransport(): Boolean = railTransportable != 0

    /**
     * OG's remaining **transport weight masks** — `AtpW` @42, `NtpW` @43, `RtpW` @44, `HtpW` @45
     * and the container-side `HangarW` @46. Deployed 2026-08-29 by
     * `tools/eqp-merge/add_transport_weights.py`. [groundweight] is the sixth and shipped already.
     *
     * **They are bitmasks, and that much is settled**: the author writes *"weights are implemented
     * as bit-type data"*, and 82–99.7% of each field's non-zero values are a single bit, with the
     * eight powers of two the most common values everywhere.
     *
     * **Nothing reads them yet, deliberately.** The natural predicate — cargo mask AND transport
     * mask — is confirmed for ground (`ui/EquipmentCatalogStrip` already applies it to
     * [groundweight]) and **refuted for naval**: 7,166 of 7,187 naval-transport records carry
     * `NtpW = 0`, which would make the test vacuous, and [hangarWeight] does not share the cargo
     * mask space either. The carrier-side field is `docs/og-open-questions.md` Q1.3. The data is
     * deployed now so that answering it is a rule change and not another import.
     *
     * [RAIL_UNKNOWN] on a record with no OG source: 0 is a real mask value here, so it cannot
     * double as "no data".
     */
    var airWeight: Int = RAIL_UNKNOWN
    var navalWeight: Int = RAIL_UNKNOWN
    var railWeight: Int = RAIL_UNKNOWN
    var heloWeight: Int = RAIL_UNKNOWN
    var hangarWeight: Int = RAIL_UNKNOWN

    // 1-based (1=January), matching the OG CSV's own MonthAvail/MonthExpired convention. Default
    // to full-year coverage: any equipment JSON whose parsehints don't include these two fields
    // (PM's own original adlerkorps/pacific sets, never touched by the OG import) behaves exactly
    // as it did before month granularity existed.
    var monthavailable: Int = 1
    var monthexpired: Int = 12
}

/**
 * Returns an equipment view whose player-facing numeric capabilities are multiplied by [multiplier].
 * Identity, classification, price and availability stay unchanged: multiplying `uclass`, `target`,
 * `movmethod`, dates or cost would corrupt rule dispatch/economy rather than strengthen the unit.
 *
 * **`spotrange` is deliberately excluded.** It is not a strength stat — it is the fog-of-war input.
 * At ×10 every unit saw the whole map, so switching Stalin Regime on silently disabled fog of war,
 * and enemy units stayed revealed afterwards because the hexes had already been spotted. That made
 * a power toggle also an information toggle, which is what "Observer Mode" is separately for
 * (`noFOW`) — the two must stay independent, and the player must be able to run Stalin Regime with
 * fog intact. Reported 2026-07-31 ("Stalin Regime shouldn't disable fog of war"; "I can see enemy
 * units even when I disable Stalin Regime and Observer Mode").
 */
internal fun EquipmentData.withStatMultiplier(multiplier: Int): EquipmentData =
    EquipmentData().also { result ->
        result.eqid = eqid
        result.gunrange = gunrange * multiplier
        result.icon = icon
        result.yearexpired = yearexpired
        result.cost = cost
        result.initiative = initiative * multiplier
        result.spotrange = spotrange
        result.hardatk = hardatk * multiplier
        result.softatk = softatk * multiplier
        result.uclass = uclass
        result.airdef = airdef * multiplier
        result.fuel = fuel * multiplier
        result.airseaweight = airseaweight
        result.rangedefmod = rangedefmod * multiplier
        result.airatk = airatk * multiplier
        result.groundweight = groundweight
        result.movmethod = movmethod
        result.navalatk = navalatk * multiplier
        result.movpoints = movpoints * multiplier
        result.grounddef = grounddef * multiplier
        result.target = target
        result.yearavailable = yearavailable
        result.name = name
        result.country = country
        result.closedef = closedef * multiplier
        result.ammo = ammo * multiplier
        result.attr = attr
        result.embark = embark
        result.attr2 = attr2
        result.attrEx = attrEx
        result.bombsize = bombsize
        result.hangarCap = hangarCap
        result.railTransportable = railTransportable
        result.navalTransportable = navalTransportable
        result.airTransportable = airTransportable
        result.paraDroppable = paraDroppable
        result.airWeight = airWeight
        result.navalWeight = navalWeight
        result.railWeight = railWeight
        result.heloWeight = heloWeight
        result.hangarWeight = hangarWeight
        result.monthavailable = monthavailable
        result.monthexpired = monthexpired
    }

/** Whether this equipment can be bought/found in [year]/[month] (1-based month, matching
 *  monthavailable/monthexpired). Shared by the equipment window, the unit-card tooltip, and the
 *  AI's own purchase filter so all three agree on the same availability window. */
fun EquipmentData.isAvailableIn(
    year: Int,
    month: Int,
): Boolean {
    val afterStart = year > yearavailable || (year == yearavailable && month >= monthavailable)
    val beforeEnd = year < yearexpired || (year == yearexpired && month <= monthexpired)
    return afterStart && beforeEnd
}

fun Json.toEquipmentData(parseHints: List<String>): EquipmentData {
    val data = EquipmentData()
    val values = this.unsafeCast<Array<dynamic>>()
    // The field set is split across three helpers purely to keep each `when` under detekt's
    // cyclomatic-complexity limit. Every hint matches at most one arm across all three, so
    // calling all three per field is behaviour-identical to the original single `when`.
    parseHints.forEachIndexed { index, hint ->
        val value = values[index]
        data.applyEquipmentFieldsA(hint, value)
        data.applyEquipmentFieldsB(hint, value)
        data.applyEquipmentFieldsC(hint, value)
        data.applyEquipmentFieldsD(hint, value)
    }
    return data
}

private fun EquipmentData.applyEquipmentFieldsA(
    hint: String,
    value: dynamic,
) {
    when (hint) {
        "gunrange" -> gunrange = value as Int
        "icon" -> icon = value as String
        "yearexpired" -> yearexpired = value as Int
        "cost" -> cost = value as Int
        "initiative" -> initiative = value as Int
        "spotrange" -> spotrange = value as Int
        "hardatk" -> hardatk = value as Int
        "softatk" -> softatk = value as Int
        "uclass" -> uclass = value as Int
        "airdef" -> airdef = value as Int
    }
}

private fun EquipmentData.applyEquipmentFieldsB(
    hint: String,
    value: dynamic,
) {
    when (hint) {
        "fuel" -> fuel = value as Int
        "airseaweight" -> airseaweight = value as Int
        "rangedefmod" -> rangedefmod = value as Int
        "airatk" -> airatk = value as Int
        "groundweight" -> groundweight = value as Int
        "movmethod" -> movmethod = value as Int
        "navalatk" -> navalatk = value as Int
        "movpoints" -> movpoints = value as Int
        "grounddef" -> grounddef = value as Int
        "target" -> target = value as Int
    }
}

private fun EquipmentData.applyEquipmentFieldsC(
    hint: String,
    value: dynamic,
) {
    when (hint) {
        "yearavailable" -> yearavailable = value as Int
        "name" -> name = value as String
        "country" -> country = value as Int
        "closedef" -> closedef = value as Int
        "ammo" -> ammo = value as Int
        "attr" -> attr = value as Int
        "embark" -> embark = value as Int
        "monthavailable" -> monthavailable = value as Int
        "monthexpired" -> monthexpired = value as Int
    }
}

/** OG's `Special4`/`SpecialEx` (2026-08-19), Bomber Size (2026-08-26), Hangar Capacity
 *  (2026-08-27) and Rail Transportable (2026-08-29) -- see [EquipmentData.attr2],
 *  [EquipmentData.attrEx], [EquipmentData.bombsize], [EquipmentData.hangarCap] and
 *  [EquipmentData.railTransportable]. */
private fun EquipmentData.applyEquipmentFieldsD(
    hint: String,
    value: dynamic,
) {
    when (hint) {
        "attr2" -> attr2 = value as Int
        "attrEx" -> attrEx = value as Int
        "bombsize" -> bombsize = value as Int
        "hangarcap" -> hangarCap = value as Int
        "railtransportable" -> railTransportable = value as Int
        "navaltransportable" -> navalTransportable = value as Int
        "airtransportable" -> airTransportable = value as Int
        "paradroppable" -> paraDroppable = value as Int
        "airweight" -> airWeight = value as Int
        "navalweight" -> navalWeight = value as Int
        "railweight" -> railWeight = value as Int
        "heloweight" -> heloWeight = value as Int
        "hangarweight" -> hangarWeight = value as Int
    }
}
