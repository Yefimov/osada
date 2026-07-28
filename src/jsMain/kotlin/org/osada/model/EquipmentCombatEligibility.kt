package org.osada.model

/*
 * `Equipment.attr` -- FULLY DECODED 2026-07-27. Read this before adding or trusting a mask.
 *
 * `attr` is not a PM invention: for every OG-imported record it is three of OG's own one-byte
 * ability fields packed by the importer (`tools/og-import/csv_to_eqp.py:105`):
 *
 *     attr = Special1 + (Special2 shl 8) + (Special3 shl 16)
 *
 * so bits 0..7 are Special1, 8..15 Special2, 16..23 Special3. OG's equipment view lists its
 * abilities in four columns of thirteen; the FIRST EIGHT ROWS of each column are one of those bytes,
 * in order. The remaining five rows of each column, and all of column four, live in fields the CSV
 * export does not carry -- **those abilities are simply not present in our data** (`No Leader`,
 * `All weather`, the mine specials, `Combat Support`'s neighbours, `No ZOC`, `Capture Flag`'s
 * column-four entries, `Evade`, `Kamikaze`, `Supply unit`, ...). See `OG_ABILITY_AUDIT.md`.
 *
 * | bit | mask | OG ability | bit | mask | OG ability |
 * |---|---|---|---|---|---|
 * | 0 | 1 | Lasting Sup. | 12 | 4096 | **Support Fire** |
 * | 1 | 2 | Can Blow | 13 | 8192 | Air Support |
 * | 2 | 4 | **Ignore trench** | 14 | 16384 | Air Transportable |
 * | 3 | 8 | **Bridge** | 15 | 32768 | **CAN Air Atk** |
 * | 4 | 16 | **Can't Soft Atk** | 16 | 65536 | Combat Support |
 * | 5 | 32 | **Can't Hard Atk** | 17 | 131072 | No Prototype |
 * | 6 | 64 | **Can't Air Atk** | 18 | 262144 | **Can't Naval Atk** |
 * | 7 | 128 | **Can't Buy** | 19 | 524288 | Carrier Deploy |
 * | 8 | 256 | No AI buy | 20 | 1048576 | Capture Flag |
 * | 9 | 512 | Mountain | 21 | 2097152 | Mechanized |
 * | 10 | 1024 | Recon Skill | 22 | 4194304 | Marine |
 * | 11 | 2048 | Dismount | 23 | 8388608 | **NoSurrender** |
 *
 * **How it was established — nine independent confirmations, no contradictions.** Bits 2..6 were
 * already in this file as faithful ports of `openpanzer.js`'s numeric masks and land exactly on the
 * matching column-one rows. The user then read two records in OG's own equipment view:
 *
 *  - `Yak-9P` (`attr 384` = bits 7+8) -- reported true: **only** `Can't Buy` and `No AI buy`.
 *  - BASEKORP `Fort`, OG index `E 335` (`attr 8392704` = bits 12+23) -- reported true: **only**
 *    `Support Fire` and `NoSurrender`.
 *
 * Corroborated across all 46,978 `eqp-united` records: `Yak-9R` (a recon variant) carries bits
 * 4+5+6+10 = can't attack soft/hard/air, plus Recon Skill; `Yak-9D SQNLDR` carries Combat Support;
 * every OLGCW bunker carries Can't Buy + No AI buy + Support Fire + NoSurrender. Bit 23 is on 58%
 * immobile records and 56% of the Fortification class; bit 12 on 74% of Anti-Tank, 63% of Flak, 49%
 * of Destroyer -- the defensive-fire archetypes.
 *
 * **Two masks this file previously guessed were WRONG, and are corrected here:**
 *  - `262144` was `ATTR_MASK_PURCHASABLE`. It is **`Can't Naval Atk`**. That settles the doubt
 *    recorded in `DEFERRED.md` §1.5/§7.25 about the old ground-transport gate: it was testing naval
 *    attack capability. Purchasability is bit 7 (`Can't Buy`), INVERTED.
 *  - `32768` was `ATTR_MASK_SPECIAL_ANTI_AIR`. It is **`CAN Air Atk`** -- the same idea (a unit that
 *    may attack aircraft although its class would not), so the one use of it is unaffected; only the
 *    name was wrong.
 */
private const val ATTR_MASK_IGNORES_ENTRENCHMENT = 4
private const val ATTR_MASK_BRIDGE = 8
private const val ATTR_MASK_CANNOT_ATTACK_SOFT = 16
private const val ATTR_MASK_CANNOT_ATTACK_HARD = 32
private const val ATTR_MASK_CANNOT_ATTACK_AIR = 64
private const val ATTR_MASK_CANNOT_BUY = 128
private const val ATTR_MASK_CAN_AIR_ATK = 32768

/**
 * OG's `Support Fire`, bit 12 — a **TOGGLE that reverses the class default**, not a grant.
 *
 * Fire support is on by default for Artillery; this bit flips whichever way the class defaults. See
 * `UnitCapabilities.hasSupportFire` for the evidence (three surviving toggles are all rare on the
 * class they default to; `Recon Skill` is on 10 of 2,880 Recon records). Four of OG's toggles are in
 * range of our data — bit 0 `Lasting Sup.`, bit 10 `Recon Skill`, bit 11 `Dismount`, bit 12
 * `Support Fire` — and **only bit 12 is read as a toggle today**. Do not read any of the others as a
 * plain "has ability" flag.
 */
internal const val ATTR_MASK_SUPPORT_FIRE = 4096

/**
 * OG's `Combat Support`, bit 16 — a plain grant (not one of the six toggles): the unit lends
 * experience bars to adjacent friendlies. Confirmed by BASEKORP `43 HQ` (`E 3814`), whose only
 * enabled ability is `Combat Support` and whose `attr` is exactly `65536`.
 */
internal const val ATTR_MASK_COMBAT_SUPPORT = 65536

/**
 * OG's `Capture Flag`, bit 20 — a grant that **supplements** the default capturing classes rather
 * than replacing them, which is how it is distinguishable from noise: it is set on 0.7% of the four
 * classes that already capture (Tank: 1 record in 3,024) and 37.7% of everything else. Confirmed by
 * BASEKORP `Gaz-3` (`E 528`) and `Armoured Train` (`E 2814`). See `UnitCapabilities.canCaptureHex`.
 */
internal const val ATTR_MASK_CAPTURE_FLAG = 1048576

/** OG's `NoSurrender`: never destroyed-as-surrendered for a retreat it cannot make. Bit 23. */
private const val ATTR_MASK_NO_SURRENDER = 8388608

/** Attribute-bitmask combat eligibility checks, split out of [Equipment] to keep its function count in bounds. */
fun Equipment.isBridge(eqid: Int): Boolean = (equipmentMap[eqid]?.attr?.and(ATTR_MASK_BRIDGE) ?: 0) != 0

fun Equipment.ignoresEntrenchment(eqid: Int): Boolean =
    (equipmentMap[eqid]?.attr?.and(ATTR_MASK_IGNORES_ENTRENCHMENT) ?: 0) != 0

/**
 * OG's `Can't Buy`, inverted: whether this record may be bought at all.
 *
 * **Was reading the wrong bit** (`262144`, now known to be `Can't Naval Atk`) until the `attr` table
 * above was decoded. Corrected rather than deleted even though **nothing calls it today** — a
 * function named `isPurchasable` that silently answered "can it shoot at ships?" is a trap for the
 * next reader. Wiring it into the Purchase list is a separate decision (`DEFERRED.md` §7.32):
 * `Can't Buy` is set on a great many scenario-only records, so honouring it would visibly shrink
 * what the player may buy in every imported campaign.
 */
fun Equipment.isPurchasable(eqid: Int): Boolean = (equipmentMap[eqid]?.attr?.and(ATTR_MASK_CANNOT_BUY) ?: 0) == 0

/** OG's `NoSurrender`: the unit is never destroyed-as-surrendered for a retreat it cannot make.
 *  See the `attr` table above for how the bit was identified. */
fun Equipment.hasNoSurrender(eqid: Int): Boolean = (equipmentMap[eqid]?.attr?.and(ATTR_MASK_NO_SURRENDER) ?: 0) != 0

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

fun Equipment.canInitiateAttackOnUnitType(
    attackerEqid: Int,
    defenderEqid: Int,
): Boolean {
    val attacker = equipmentMap[attackerEqid]
    val defender = equipmentMap[defenderEqid]
    if (attacker == null || defender == null) return false
    return canAttackSubmarineTarget(attacker, defender) && canAttackTargetType(attacker, defender.target)
}

private fun canAttackSubmarineTarget(
    attacker: EquipmentData,
    defender: EquipmentData,
): Boolean {
    if (defender.uclass != org.osada.UnitClass.SUBMARINE.value) return true
    return attacker.uclass == org.osada.UnitClass.DESTROYER.value ||
        attacker.uclass == org.osada.UnitClass.TACTICAL_BOMBER.value
}

private fun canAttackTargetType(
    attacker: EquipmentData,
    target: Int,
): Boolean =
    when (target) {
        org.osada.UnitType.SOFT.value -> attacker.attr.and(ATTR_MASK_CANNOT_ATTACK_SOFT) == 0
        org.osada.UnitType.HARD.value -> attacker.attr.and(ATTR_MASK_CANNOT_ATTACK_HARD) == 0
        org.osada.UnitType.AIR.value -> canAttackAirTarget(attacker)
        else -> true
    }

private fun canAttackAirTarget(attacker: EquipmentData): Boolean {
    if (attacker.attr.and(ATTR_MASK_CANNOT_ATTACK_AIR) != 0) return false
    val attackerClass = attacker.uclass
    val isDedicatedAntiAir =
        attackerClass == org.osada.UnitClass.AIR_DEFENCE.value ||
            attackerClass == org.osada.UnitClass.FIGHTER.value ||
            attackerClass == org.osada.UnitClass.LEVEL_BOMBER.value ||
            attackerClass == org.osada.UnitClass.TACTICAL_BOMBER.value ||
            attackerClass == org.osada.UnitClass.BATTLESHIP.value ||
            attackerClass == org.osada.UnitClass.BATTLE_CRUISER.value ||
            attackerClass == org.osada.UnitClass.LIGHT_CRUISER.value ||
            attackerClass == org.osada.UnitClass.AIR_TRANSPORT.value
    return isDedicatedAntiAir || attacker.attr.and(ATTR_MASK_CAN_AIR_ATK) != 0
}
