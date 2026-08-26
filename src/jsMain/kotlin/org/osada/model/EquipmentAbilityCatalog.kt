@file:Suppress("MaxLineLength")

package org.osada.model

import org.osada.rules.CounterBatteryFire
import org.osada.rules.Engineering
import org.osada.rules.ExtendedLos
import org.osada.rules.Minefields

/**
 * Every named OG equipment ability a record carries: a short badge code, and a plain-language line.
 *
 * **Added 2026-08-19 as prose only; given badges 2026-08-23** on the owner's report — *"It looks
 * like you don't have badges for all abilities. So it's not obvious for player."* The original
 * design kept the visible badges at five (HQ/RCN/OVR/SUP/AA) on the reasoning that 52 badges would
 * make the unit card unreadable. The measured distribution says otherwise: over the 56,970 shipped
 * records the median record carries **2** abilities and 95% carry 5 or fewer, so a wrapping badge
 * row with an overflow chip is perfectly legible. The five primary badges keep their distinct
 * styling and lead the row; the rest follow.
 *
 * Two tiers, and the badge SHOWS which is which — a muted badge is not a decorative choice, it is
 * the honest statement `docs/og-fidelity-plan.md` §I.2 already required of the prose:
 *
 *  - **WIRED** — OSADA executes it. Normal badge, and the line states what happens.
 *  - **DESCRIPTIVE ONLY** — decoded from OG's data, no OSADA subsystem reads it yet. Muted badge,
 *    and the line says so outright rather than implying a rule that is not there.
 *
 * Every description below is quoted or closely paraphrased from `Manual_OG-en.pdf` §7.2 ("Unit
 * abilities") via `tmp/pdfs/og-comparison/manual.txt`'s extraction, not guessed from the bit's
 * label — `OG_ABILITY_AUDIT.md` §1's standing warning against inventing a mechanic from a name
 * applies here as much as it does to engine code. Left out entirely: `Air Transportable` (redundant
 * with the already-surfaced `embark` field), the pure authoring/administrative bits (`Can't Buy`,
 * `No AI buy`, `No Prototype` — none of which OSADA enforces today, so stating them would claim a
 * rule that is not there), and `Combat Support`/`Support Fire`/`Recon Skill`/`Overrun toggle`/
 * `AD Support`/`Mechanized`'s badge, which the five existing badges (or, for Mechanized, its own
 * mechanics note) already state.
 *
 * `Jet (Stealth)` goes further still: `OG_ABILITY_AUDIT.md` §7.1.1 could not even determine what
 * the ability DOES from either the manual or OG's own UI, only that the bit is real, so its line
 * says that plainly rather than guessing from the name.
 *
 * **The three minefield abilities are hidden unless minefields are actually in play.** `Drop Mines`,
 * `Clear Mines` and `Air Drop Mines` do nothing at all under a ruleset with `minefields` off —
 * which is the DEFAULT (`RuleKey.MINEFIELDS`, and every profile except Open General Fidelity) — so
 * listing them there advertised a button the player would never get. Raised by the owner:
 * *"Don't show it for rulesets that are not OG (I mean, that don't use mines!)."* The gate is a
 * parameter rather than a direct read so the catalog stays testable without a resolved ruleset.
 */
private class AbilityEntry(
    val test: (EquipmentData) -> Boolean,
    val key: String,
    val badge: String,
)

/**
 * One ability a record carries, ready for both surfaces: [badge] for the marks row, [key] for the
 * i18n line that is also the badge's tooltip, [wired] for whether OSADA actually executes it.
 */
class EquipmentAbility internal constructor(
    val key: String,
    val badge: String,
    val wired: Boolean,
)

private const val ATTR_MASK_AIR_SUPPORT = 8192
private const val ATTR_MASK_CARRIER_DEPLOY = 524288

private const val ATTR2_MASK_DISMOUNT_AFTER_MOVE = 2
private const val ATTR2_MASK_NO_DIRT_AIRFIELDS = 4
private const val ATTR2_MASK_ROCKET_BOMBER = 8
private const val ATTR2_MASK_EVADE = 128

private const val ATTR_EX_MASK_NO_LEADER = 1
private const val ATTR_EX_MASK_NO_AMMO_PENALTY = 16
private const val ATTR_EX_MASK_NO_NEED_STATION = 128
private const val ATTR_EX_MASK_TORPEDO_BOMBER = 256
private const val ATTR_EX_MASK_PARTIZAN = 1024
private const val ATTR_EX_MASK_EXPLOIT_SUCCESS = 2048
private const val ATTR_EX_MASK_SINGLE_FIRE_SUP = 32768
private const val ATTR_EX_MASK_KAMIKAZE = 65536
private const val ATTR_EX_MASK_SABOTEUR = 262144
private const val ATTR_EX_MASK_JET_STEALTH = 524288
private const val ATTR_EX_MASK_SUPPLY_UNIT = 1048576

/**
 * WIRED abilities that already have a gameplay effect but no badge — Drop Mines through ASW.
 * Kept separate from [DESCRIPTIVE_ABILITIES] so the two phrasings (imperative "does X" vs honest
 * "decoded, not yet a rule") are never mixed by an editing mistake.
 */
private val WIRED_ABILITIES: List<AbilityEntry> =
    listOf(
        AbilityEntry({ it.attr and ATTR_MASK_DROP_MINES != 0 }, "equipment.ability.drop_mines", "MIN"),
        AbilityEntry({ it.attrEx and ATTR_EX_MASK_CLEAR_MINES != 0 }, "equipment.ability.clear_mines", "CLR"),
        AbilityEntry({ it.attrEx and ATTR_EX_MASK_AIR_DROP_MINES != 0 }, "equipment.ability.air_drop_mines", "AMN"),
        AbilityEntry({ it.attrEx and ATTR_EX_MASK_ALL_WEATHER != 0 }, "equipment.ability.all_weather", "WX"),
        AbilityEntry(
            { it.attrEx and ATTR_EX_MASK_LASTING_SUPPRESSION != 0 },
            "equipment.ability.lasting_suppression",
            "LSP",
        ),
        AbilityEntry({ it.attrEx and ATTR_EX_MASK_NO_INTERCEPT_AIR != 0 }, "equipment.ability.no_intercept_air", "NIA"),
        AbilityEntry({ it.attrEx and ATTR_EX_MASK_ANTI_SUB != 0 }, "equipment.ability.anti_sub", "ASW"),
        AbilityEntry(
            { it.attr and ATTR_MASK_IGNORES_ENTRENCHMENT != 0 },
            "equipment.ability.ignores_entrenchment",
            "ENG",
        ),
        AbilityEntry({ it.attr and ATTR_MASK_BRIDGE != 0 }, "equipment.ability.bridge", "BRG"),
        AbilityEntry({ it.attr and ATTR_MASK_CANNOT_ATTACK_SOFT != 0 }, "equipment.ability.cannot_attack_soft", "-SA"),
        AbilityEntry({ it.attr and ATTR_MASK_CANNOT_ATTACK_HARD != 0 }, "equipment.ability.cannot_attack_hard", "-HA"),
        AbilityEntry({ it.attr and ATTR_MASK_CANNOT_ATTACK_AIR != 0 }, "equipment.ability.cannot_attack_air", "-AA"),
        AbilityEntry({ it.attr and ATTR_MASK_CAN_AIR_ATK != 0 }, "equipment.ability.can_air_attack", "+AA"),
        AbilityEntry({ it.attr and ATTR_MASK_CAPTURE_FLAG != 0 }, "equipment.ability.capture_flag", "CAP"),
        AbilityEntry({ it.attr and ATTR_MASK_MECHANIZED != 0 }, "equipment.ability.mechanized", "MEC"),
        AbilityEntry({ it.attr and ATTR_MASK_NO_SURRENDER != 0 }, "equipment.ability.no_surrender", "NSR"),
        // Wired 2026-08-25 -- both were descriptive-only until then. See their masks'
        // documentation in EquipmentCombatEligibility.kt for what each now actually does.
        AbilityEntry({ it.attr and ATTR_MASK_MOUNTAIN != 0 }, "equipment.ability.mountain", "MTN"),
        AbilityEntry({ it.attr and ATTR_MASK_MARINE != 0 }, "equipment.ability.marine", "MAR"),
        // Wired 2026-08-25 with OG's Dismount toggle and its three optional rules -- Extended
        // LOS (9.5), Counterbattery (9.4) and Build and Repair (9.3). Every one of these seven
        // was a muted grey badge until then; four of them are gated on a ruleset key and are
        // hidden outright when it is off, exactly as the three minefield abilities are.
        AbilityEntry({ it.attr and ATTR_MASK_DISMOUNT != 0 }, "equipment.ability.dismount", "DSM"),
        AbilityEntry({ it.attr2 and ATTR2_MASK_NO_ZOC != 0 }, "equipment.ability.no_zoc", "-ZC"),
        AbilityEntry({ it.attr and ATTR_MASK_CAN_BLOW != 0 }, "equipment.ability.can_blow", "BLW"),
        AbilityEntry({ it.attr2 and ATTR2_MASK_BUILD_REPAIR != 0 }, "equipment.ability.build_repair", "SAP"),
        AbilityEntry({ it.attr2 and ATTR2_MASK_CUT_LOS != 0 }, "equipment.ability.cut_los", "LOS"),
        AbilityEntry(
            { it.attrEx and ATTR_EX_MASK_COUNTER_BATTERY != 0 },
            "equipment.ability.counter_battery",
            "CBT",
        ),
    )

/**
 * DESCRIPTIVE-ONLY abilities: decoded and readable from `equip.xeqp`, named honestly with the
 * manual's own wording, but not read by any OSADA subsystem yet. Each would need its own design
 * under `ruleset-profiles.md` §2's admission rule before it could become a rule — a bitmask read is
 * not that design.
 */
private val DESCRIPTIVE_ABILITIES: List<AbilityEntry> =
    listOf(
        AbilityEntry({ it.attr and ATTR_MASK_AIR_SUPPORT != 0 }, "equipment.ability.air_support", "ASP"),
        AbilityEntry({ it.attr and ATTR_MASK_CARRIER_DEPLOY != 0 }, "equipment.ability.carrier_deploy", "CVD"),
        AbilityEntry(
            { it.attr2 and ATTR2_MASK_DISMOUNT_AFTER_MOVE != 0 },
            "equipment.ability.dismount_after_move",
            "DAM",
        ),
        AbilityEntry({ it.attr2 and ATTR2_MASK_NO_DIRT_AIRFIELDS != 0 }, "equipment.ability.no_dirt_airfields", "-DA"),
        AbilityEntry({ it.attr2 and ATTR2_MASK_ROCKET_BOMBER != 0 }, "equipment.ability.rocket_bomber", "RKT"),
        AbilityEntry({ it.attr2 and ATTR2_MASK_EVADE != 0 }, "equipment.ability.evade", "EVD"),
        // Demoted back from WIRED on review, 2026-08-25. `Allow LOF` says fire is NOT blocked by
        // this unit -- which is already true of every unit, because OSADA has no rule that makes
        // an ordinary unit block line of fire (OG gates that on a `UnitsBlockDLOF` scenario option
        // the importer does not carry). `ExtendedLos.cutsLineOfSight` still honours the bit as an
        // override of `Cut LOS` on the same record, but a record carrying `Allow LOF` ALONE gets
        // no behaviour from it, so a brass badge overstated it.
        AbilityEntry({ it.attr2 and ATTR2_MASK_ALLOW_LOF != 0 }, "equipment.ability.allow_lof", "LOF"),
        AbilityEntry({ it.attrEx and ATTR_EX_MASK_NO_LEADER != 0 }, "equipment.ability.no_leader", "-LD"),
        AbilityEntry({ it.attrEx and ATTR_EX_MASK_NO_AMMO_PENALTY != 0 }, "equipment.ability.no_ammo_penalty", "-AP"),
        AbilityEntry({ it.attrEx and ATTR_EX_MASK_NO_NEED_STATION != 0 }, "equipment.ability.no_need_station", "RAI"),
        AbilityEntry({ it.attrEx and ATTR_EX_MASK_TORPEDO_BOMBER != 0 }, "equipment.ability.torpedo_bomber", "TRP"),
        AbilityEntry({ it.attrEx and ATTR_EX_MASK_PARTIZAN != 0 }, "equipment.ability.partizan", "PTZ"),
        AbilityEntry({ it.attrEx and ATTR_EX_MASK_EXPLOIT_SUCCESS != 0 }, "equipment.ability.exploit_success", "EXP"),
        AbilityEntry({ it.attrEx and ATTR_EX_MASK_SINGLE_FIRE_SUP != 0 }, "equipment.ability.single_fire_sup", "1SF"),
        AbilityEntry({ it.attrEx and ATTR_EX_MASK_KAMIKAZE != 0 }, "equipment.ability.kamikaze", "KMZ"),
        AbilityEntry({ it.attrEx and ATTR_EX_MASK_SABOTEUR != 0 }, "equipment.ability.saboteur", "SAB"),
        AbilityEntry({ it.attrEx and ATTR_EX_MASK_JET_STEALTH != 0 }, "equipment.ability.jet_stealth", "JET"),
        AbilityEntry({ it.attrEx and ATTR_EX_MASK_SUPPLY_UNIT != 0 }, "equipment.ability.supply_unit", "SPL"),
    )

/**
 * The ruleset switches that decide whether an ability does anything at all.
 *
 * Defaults read the live ruleset, so ordinary callers pass nothing. Tests pass the flags they
 * mean instead, which is what keeps the catalog testable with no resolved ruleset -- the
 * constraint the minefield gate was written to satisfy (§K.4) and the reason this grew into a
 * type rather than a fourth boolean parameter.
 */
class AbilityGates(
    val minefields: Boolean = Minefields.enabled(),
    val engineering: Boolean = Engineering.enabled(),
    val counterBattery: Boolean = CounterBatteryFire.enabled(),
    val extendedLos: Boolean = ExtendedLos.enabled(),
)

/**
 * i18n keys of every ability [this] carries, under [gates].
 *
 * Wired abilities first, then descriptive-only ones, both in the table order above. Empty when
 * the record carries none of them (most scenario filler, and every PM-stock `eqp-adlerkorps`/
 * `eqp-pacific` record, which has no OG source to import `attr2`/`attrEx` from at all —
 * `docs/og-fidelity-plan.md` §J.3).
 */
fun EquipmentData.abilityCatalogKeys(gates: AbilityGates = AbilityGates()): List<String> =
    abilityCatalog(gates).map { it.key }

/**
 * The same list as [abilityCatalogKeys], carrying each ability's badge code and wired/descriptive
 * tier as well — what [org.osada.ui.EquipmentMarkings] renders the extended marks row from.
 */
fun EquipmentData.abilityCatalog(gates: AbilityGates = AbilityGates()): List<EquipmentAbility> {
    val data = this
    return (WIRED_ABILITIES.map { it to true } + DESCRIPTIVE_ABILITIES.map { it to false })
        .filter { (entry, _) -> gateFor(entry.key)?.invoke(gates) ?: true }
        .filter { (entry, _) -> entry.test(data) }
        .map { (entry, wired) -> EquipmentAbility(entry.key, entry.badge, wired) }
}

/**
 * The switch an ability depends on, or null when it always applies.
 *
 * An ability whose rule is off does nothing whatsoever, so it is hidden entirely rather than
 * listed as a capability the player can never use — the ruling §K.4 made for the minefield three
 * (*"Don't show it for rulesets that are not OG (I mean, that don't use mines!)"*), applied to
 * every ability that has since gained a key of its own.
 */
private fun gateFor(key: String): ((AbilityGates) -> Boolean)? =
    when (key) {
        "equipment.ability.drop_mines",
        "equipment.ability.clear_mines",
        "equipment.ability.air_drop_mines",
        -> AbilityGates::minefields

        "equipment.ability.can_blow",
        "equipment.ability.build_repair",
        -> AbilityGates::engineering

        "equipment.ability.counter_battery" -> AbilityGates::counterBattery

        "equipment.ability.cut_los" -> AbilityGates::extendedLos

        else -> null
    }
