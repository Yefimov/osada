package org.osada.rules

import org.osada.UnitClass
import org.osada.model.EquipmentData

/**
 * Open General's **transport weights** — which formations a given non-organic transport will carry.
 *
 * > *"Units can be assigned a 'weight' to isolate which units can use a given transport or
 * > container. **Weights are implemented as bit-type data.**"* — `OpenGen_Features.html`
 *
 * ### The question this answers, and how long it was open
 *
 * `docs/og-open-questions.md` Q1.3 asked, for two days, **which field the TRANSPORT side reads**.
 * The cargo side was never in doubt — a formation carries `AtpW`/`NtpW`/`RtpW`/`HtpW`, one per
 * transport type. Two candidates were proposed for the transport side and both were refuted by a
 * shuffled-null control (`GtpW`, +0.09 pp; `HangarW`, no effect on any of the four types), and a
 * third (`HangarW` on both sides) was refuted the same way on 2026-08-30.
 *
 * **The answer is that there is no separate field: the transport reads its OWN same-named weight.**
 * Settled 2026-08-30 by the owner opening OpenSuite's *Transports* tab, which shows one grid per
 * transport type — Ground with 16 cells (1, 2, 4 … 32768) and Air / Naval / Rail / Helo with 8
 * each (1 … 128) — and a controlled save per cell confirming every offset:
 *
 * | grid | byte | confirmed by |
 * |---|---|---|
 * | Ground | `@40` u16 | `tr_ground_1` → `0x0001`, `tr_ground_65535` → `0xFFFF` |
 * | Air | `@42` u8 | `tr_air_1` → `0x01`, `tr_air_255` → `0xFF` |
 * | Naval | `@43` u8 | `naval_1` → `0x01`, `naval_15` → `0x0F` |
 * | Rail | `@44` u8 | `rail_1` → `0x01` |
 * | Helo | `@45` u8 | `tr_rail_1` → `0x01` |
 *
 * **The same tab exists on the transports themselves**, and the corpus agrees: of 2,067 Air
 * Transport records **82% carry a non-zero `AtpW`**, and of 2,721 Naval Transport records **73%
 * carry a non-zero `NtpW`** — in each case the transport's own type's field and no other.
 *
 * ### Why the earlier measurement said the opposite, and it is worth remembering
 *
 * This object's own question was once recorded as *"`NtpW` is 0 on 7,166 of 7,187 naval-transport
 * records (99.7%), which makes the AND vacuous"*. **That measurement read unit class 21, which is
 * not the naval transport** — it is the carrier/capital tier, which has no `*tpW` at all and
 * carries `HangarW` instead. The naval transport is class 20. One wrong class byte made a
 * populated field look empty and sent the whole question down a two-day detour, which is exactly
 * the trap `docs/og-sources.md` records under *"OG's unit classes are not OSADA's"*.
 *
 * ### The predicate, and why the zero escapes matter
 *
 * `cargo == 0 || transport == 0 || (cargo and transport) != 0`. **Zero means "unrestricted" on
 * either side**, which is what keeps this additive: a formation whose author set no weight may ride
 * anything, and a transport with no weight carries anything. That is the reading that cannot
 * overstate — the alternative, treating 0 as "matches nothing", would ground every unit in the
 * efiles that leave the field alone.
 */
object TransportWeights {
    /** The weight a formation presents when boarding [transportClass], or null when the class is
     *  not a non-organic transport this rule governs. */
    fun weightFor(
        data: EquipmentData,
        transportClass: Int,
    ): Int? =
        when (transportClass) {
            UnitClass.AIR_TRANSPORT.value -> data.airWeight
            UnitClass.NAVAL_TRANSPORT.value -> data.navalWeight
            // Rail is deliberately absent. OSADA folds OG's RT class into `GROUND_TRANSPORT`
            // (`docs/og-sources.md`), which is ALSO the organic truck -- and the organic side reads
            // `GtpW`, which `ui/EquipmentCatalogStrip` has been ANDing since before this question
            // was asked. Deciding between them from the class byte alone is impossible, and rail
            // entrainment does not go through `embark` at all: `rules/RailTransport` owns it.
            else -> null
        }

    /**
     * Whether [cargo] may ride [transport] of [transportClass].
     *
     * Both sides read the SAME field — the one belonging to the transport's own type — and a zero
     * on either side means "no restriction".
     */
    fun compatible(
        cargo: EquipmentData,
        transport: EquipmentData,
        transportClass: Int,
    ): Boolean {
        // `RAIL_UNKNOWN` (-1) is OG having said nothing about this record -- 4,140 merged records
        // have no OG source at all -- and reads as unrestricted for the same reason 0 does. A null
        // class is one this rule does not govern, and is likewise unrestricted.
        val cargoWeight = weightFor(cargo, transportClass) ?: 0
        val transportWeight = weightFor(transport, transportClass) ?: 0
        return cargoWeight <= 0 || transportWeight <= 0 || cargoWeight and transportWeight != 0
    }
}
