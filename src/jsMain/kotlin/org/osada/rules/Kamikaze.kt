package org.osada.rules

import org.osada.model.ATTR_EX_MASK_KAMIKAZE
import org.osada.model.EfileConfig
import org.osada.model.GameUnit

/**
 * Open General's **`Kamikaze`** (`SpecialEx` 62.0, `attrEx` bit 16) — the formation does not come
 * back. Wired 2026-08-27 from the author's specials reference and `equip.cfg`'s own `kamikaze` key.
 *
 * 61 shipped records carry it, and the population is exactly what the name says: Tactical Bomber
 * 17, Submarine 17, Destroyer 9 — the suicide craft and the human torpedoes.
 *
 * ### Two models, and the efile picks
 *
 * ```
 * kamikaze = 1
 * * default is zero, unit dies after using all ammo or engaged in combat
 * * set 1 to use extended missile rules: units dies after using all fuel and not able to resupply
 * ```
 *
 * | | `kamikaze=0` (default) | `kamikaze=1` (`eqp-lxf`) |
 * |---|---|---|
 * | dies when | it has taken part in combat, or spent its last ammunition | its FUEL runs out |
 * | may resupply | yes | **no** |
 *
 * The second is the missile model: a weapon that flies until it runs out of fuel and cannot be
 * refilled. The first is the aircraft model: it attacks once and is gone.
 *
 * ### Where it is applied, and the one thing it deliberately does not do
 *
 * [expendedAfterCombat] is asked by `CombatApplication` once damage has landed, and
 * [strandedWithoutFuel] by the end-of-turn sweep that `AirOperations` already runs for aircraft out
 * of fuel — so a kamikaze is removed by the same machinery that removes any other loss, and shows
 * up in the turn report the same way.
 *
 * **It does not make the attack stronger.** OG's kamikaze is a unit that dies, not one that hits
 * harder; nothing in the reference gives it a damage bonus, and inventing one from the name is what
 * `OG_ABILITY_AUDIT.md` §1 forbids.
 */
internal object Kamikaze {
    /** Whether [unit]'s equipment carries the ability. Read on the REAL record. */
    fun isKamikaze(unit: GameUnit): Boolean = unit.unitData(true).attrEx and ATTR_EX_MASK_KAMIKAZE != 0

    /** Whether this efile runs OG's *"extended missile rules"* — `kamikaze=1`, which `eqp-lxf` sets. */
    private fun missileRules(): Boolean = EfileConfig.flag("kamikaze", false)

    /**
     * Whether [unit] is spent by having fought — the default model's *"dies after... engaged in
     * combat"*, and its *"after using all ammo"* half.
     *
     * Under the missile rules neither applies: such a unit dies of fuel instead, so it may attack
     * more than once as long as it can still fly.
     */
    fun expendedAfterCombat(unit: GameUnit): Boolean = isKamikaze(unit) && !missileRules()

    /**
     * Whether [unit] is spent for want of fuel — the missile model's *"dies after using all fuel"*.
     *
     * Only under `kamikaze=1`; the default model has no fuel clause, and an ordinary aircraft that
     * runs dry is already `AirOperations`' business.
     */
    fun strandedWithoutFuel(unit: GameUnit): Boolean = isKamikaze(unit) && missileRules() && unit.getFuel() <= 0

    /**
     * Whether [unit] may take on ammunition and fuel at all — *"not able to resupply"*.
     *
     * False only under the missile rules. A default-model kamikaze resupplies normally right up
     * until the attack that consumes it, which is the difference between a suicide aircraft waiting
     * on an airfield and a missile in flight.
     */
    fun canResupply(unit: GameUnit): Boolean = !(isKamikaze(unit) && missileRules())
}
