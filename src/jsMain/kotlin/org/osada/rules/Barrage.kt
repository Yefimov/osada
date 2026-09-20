package org.osada.rules

import org.osada.GameHolder
import org.osada.RoadType
import org.osada.TerrainType
import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.hit
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey

/**
 * Open General's **Barrage** optional rule (manual §9.2) — shelling a hex nobody can see.
 *
 * > *"Units with the Can bombard/barrage ability sometimes can do barrage fire. For barrage fire to
 * > work it must be enabled by the designer and the unit must have the ability. When any unit can do
 * > barrage fire, the pointer becomes a crosshair when it is over hexes that it can attack... Units
 * > present in a non-visible hex attacked by a barrage could lose strength points, and terrain could
 * > be destroyed."* — manual §9.2
 *
 * The game's own `tips1.txt` states the three outcomes, and they are what [resolve] applies:
 *
 * > *"A Successful Barrage Attack on a hidden enemy can destroy fuel and ammo as well as Strength
 * > points. A Successful Barrage Attack on an empty hex can make the terrain harder to move through.
 * > A Successful Barrage Attack can reduce a City, Airfield, Bridge, or Port to rubble, making them
 * > unusable until Repaired."*
 *
 * ### Why this was blocked, and what unblocked it
 *
 * `docs/og-fidelity-plan.md` §L.6 filed Barrage as un-buildable because the ability was *"not among
 * the decoded 52"* equipment specials. It is not a bit at all: it is the record's **Bomber Size**
 * ([org.osada.model.EquipmentData.bombsize]), the `'='` mark `tips1.txt` tells the player to look
 * for. That was settled on 2026-08-26 by the owner reading OG directly, and the population proves
 * it — every LXF Level Bomber and every Battleship carries it (§Q.2).
 *
 * ### Two gates, because OG has two
 *
 * The rule needs the ruleset key AND the scenario's own `Allow barrage fire` switch, imported with
 * the rest of the option bitfield (§O). 356 of the 457 shipped scenarios whose source is readable
 * author it. An unimported scenario follows the key alone, for the reason
 * `Engineering.authorisedByScenario` gives.
 *
 * ### The honesty rule this mechanic had to satisfy
 *
 * `tools/og-import/DEFERRED.md` §1.1 forbids damage with no visible cause. A barrage is the exact
 * shape that rule guards against: fire into a hex nobody can see. It is admissible because the
 * player ORDERS it — the damage is theirs, not a surprise — and because every outcome is reported
 * ([BarrageResult] is what `CombatLog` and the HUD banner read). The defender's side is told
 * nothing it would not otherwise know: a hidden unit that is hit stays hidden.
 */
internal object Barrage {
    /** OG states no numbers for barrage at all. These are `INFERENCE`, sized against the minefield
     *  constants they sit beside: a barrage is a real attack, so it hurts more than a mine, and it
     *  is ordered blind, so it is not a substitute for an aimed one. */
    const val STRENGTH_DAMAGE = 2

    /** *"can destroy fuel and ammo"* — one point of each, which is what a supply-denial mechanic
     *  needs to be worth ordering without making it a better attack than attacking. `INFERENCE`. */
    const val SUPPLY_DAMAGE = 1

    /** Entrenchment a barrage costs the unit it lands on. **Not an inference**: Open General School
     *  theme 5 states the figure, and it is the one number OG publishes about barrage. */
    const val ENTRENCHMENT_DAMAGE = 2

    /** Ammunition the firing unit spends. Same as an ordinary attack, and OG gives no other figure. */
    const val AMMO_COST = 1

    /** Rounds out of ten that land. OG says *"sometimes"* and *"a SUCCESSFUL barrage"* without ever
     *  giving odds, so this is an `INFERENCE` — and the reason the roll goes through
     *  [GameRandomSource], which is the stream both multiplayer peers share. */
    private const val SUCCESS_IN_TEN = 6
    private const val FULL_ROLL = 10

    /** What one barrage did, for the log, the banner and the tests. */
    data class BarrageResult(
        val hit: Boolean,
        val strengthLost: Int = 0,
        val wreckedTerrain: Boolean = false,
        val cutRail: Boolean = false,
        val leftCrater: Boolean = false,
        val blewBridge: Boolean = false,
        val leftRubble: Boolean = false,
    )

    /** Whether the rule is in force: the ruleset key, and the scenario's own switch where it has
     *  one. Off in every profile except Open General Fidelity. */
    fun enabled(): Boolean =
        ActiveRuleset.flag(RuleKey.BARRAGE, false) &&
            (GameHolder.instance?.scenario?.barrageAllowed ?: true)

    /** The [RuleKey.BARRAGE] value at which the crosshair also opens over hexes the firer CAN see,
     *  provided no ground unit holds them — the rule's own third grade, not a second rule. */
    private const val AIMED_LEVEL = 2

    /** Whether aimed interdiction is allowed: shelling visible empty ground on purpose, to crater it
     *  or to cut the line running through it. Grade 2 of the same rule, so the scenario's switch
     *  still has to allow barrage at all. */
    private fun aimedFire(): Boolean = enabled() && ActiveRuleset.intKey(RuleKey.BARRAGE, 0) >= AIMED_LEVEL

    /** Whether [unit]'s equipment carries OG's `Can bombard/barrage` — Bomber Size above zero, the
     *  `'='` mark. Reads the REAL record: the ability belongs to the gun, not to a transport. */
    fun canBarrage(unit: GameUnit): Boolean = enabled() && unit.unitData(true).bombsize > 0

    /** Whether [unit] may fire a barrage right now: it has the ability, ammunition and its shot. */
    fun ready(unit: GameUnit): Boolean =
        canBarrage(unit) && !unit.hasFired && !unit.destroyed && unit.getAmmo() >= AMMO_COST

    /**
     * Whether [unit] may shell [cell]: on the map, within reach, and either **not spotted by its own
     * side** — OG's *"target any unspotted hex"* — or spotted but with no ground unit in it.
     *
     * Reach depends on how the unit delivers its shells. A gun fires at 1..gunrange hexes around it;
     * an aircraft bombs the hex it is flying over, exactly as its ordinary attack does, so its only
     * target is its own hex (level bombers carry gunrange 0, which the old `range > 0` test read as
     * "cannot barrage anywhere"). That hex is always spotted, which is why a spotted hex has to be
     * admissible at all.
     *
     * A spotted hex is admitted only when it holds no ground unit, and only under the rule's second
     * grade ([aimedFire]) or beneath an aircraft: shelling empty visible ground is how a player digs
     * craters or cuts a line on purpose, while a VISIBLE enemy there is a target for aimed fire,
     * which does more, and a friendly one is not a target at all.
     */
    fun canTarget(
        map: GameMap,
        unit: GameUnit,
        cell: Cell,
    ): Boolean {
        val side = unit.player?.side
        val from = unit.getPos()
        val hex = map.map?.getOrNull(cell.row)?.getOrNull(cell.col)
        if (side == null || from == null || hex == null) return false
        // An aircraft's own hex is always spotted, so admitting a visible hex is the only way a
        // level bomber can fire at all -- that part is not the graded one.
        val ownHexBombing = UnitPredicates.isAir(unit) && from.row == cell.row && from.col == cell.col
        val visibleTarget = hex.unit == null && (aimedFire() || ownHexBombing)
        return ready(unit) && withinReach(unit, from, cell) && (!hex.isSpotted(side) || visibleTarget)
    }

    /** Whether [cell] is where [unit] can put shells: the hex underneath an aircraft, or 1..gunrange
     *  hexes from a gun. A level bomber's gun range is 0, which is why the two are not one test. */
    private fun withinReach(
        unit: GameUnit,
        from: Cell,
        cell: Cell,
    ): Boolean {
        val distance = HexGeometry.distance(from.row, from.col, cell.row, cell.col)
        val range = unit.unitData(true).gunrange
        return if (UnitPredicates.isAir(unit)) distance == 0 else range > 0 && distance in 1..range
    }

    /** Every hex [unit] could shell, for the targeting overlay. */
    fun targets(
        map: GameMap,
        unit: GameUnit,
    ): List<Cell> {
        val from = unit.getPos()?.takeIf { ready(unit) } ?: return emptyList()
        val candidates =
            if (UnitPredicates.isAir(unit)) {
                listOf(Cell(from.row, from.col))
            } else {
                HexGeometry
                    .getRing(from.row, from.col, unit.unitData(true).gunrange, map.rows, map.cols, true)
                    .map { Cell(it.row, it.col) }
            }
        return candidates.filter { canTarget(map, unit, it) }
    }

    /**
     * Fires the barrage: spends the shot, rolls, and applies whichever of OG's three outcomes the
     * hex calls for.
     *
     * The cost is paid whether or not the roll lands, because the shells were fired either way —
     * and because a free retry would turn barrage into a way of searching for hidden units.
     */
    fun resolve(
        map: GameMap,
        unit: GameUnit,
        cell: Cell,
    ): BarrageResult {
        val hex = map.map?.getOrNull(cell.row)?.getOrNull(cell.col)
        if (hex != null) {
            unit.ammo -= AMMO_COST
            unit.hasFired = true
        }
        // The shot is rolled only where there is a hex to shell, so `landedOn` carries BOTH the
        // hit and the smart cast -- written as a separate `hex == null` the compiler reported the
        // second test as always false.
        val landedOn = hex?.takeIf { GameRandomSource.nextInt(FULL_ROLL) < SUCCESS_IN_TEN }
        val victim = landedOn?.unit
        return when {
            landedOn == null -> BarrageResult(hit = false)
            victim != null && victim.player?.side != unit.player?.side -> hitHiddenUnit(victim)
            Engineering.isWaterCrossing(hex) && hex.road > RoadType.NONE.value -> wreckBridge(hex)
            // Track is shelled BEFORE the terrain under it, and the order is the rule rather than
            // an accident. Cutting the line is what shelling a railway is for, and a city or a
            // port on the same hex is still there to be wrecked by the next barrage, once the
            // rails are down -- so a hex with both takes two shots and loses the more useful
            // thing first.
            // `cut` is false when the rule is off or there is no track, so the hex falls through to
            // its terrain exactly as it did before that rule existed.
            RailDemolition.cut(hex) -> {
                // Torn-up track and shell holes are the same ground, so the hex is left churned
                // exactly as a wrecked facility is.
                hex.rubble = true
                BarrageResult(hit = true, cutRail = true, leftRubble = true)
            }
            hex.terrain in EngineeringWork.razeableTerrain() -> wreckTerrain(hex)
            // Nothing OG would destroy: facilities and roads are its whole list, widened only by
            // an efile's `blow_any_terrain` (the same set `Can Blow` reads). Open ground then takes
            // craters instead of nothing -- OSADA's own rule, off unless the player asks for it
            // (`rules/Craters`, and `docs/og-fidelity-plan.md` §S).
            Craters.dig(hex) -> BarrageResult(hit = true, leftCrater = true)
            else -> BarrageResult(hit = true)
        }
    }

    /** *"can destroy fuel and ammo as well as Strength points"*. The unit stays HIDDEN: being shelled
     *  is not being spotted, and revealing it would hand the firer intelligence it did not earn. */
    private fun hitHiddenUnit(victim: GameUnit): BarrageResult {
        val entrenchedBefore = victim.entrenchment
        victim.hit(STRENGTH_DAMAGE, false)
        // TWO levels in total, not two on top of one: `GameUnit.hit` already takes a level off for
        // any damage, so adding the published figure to it would cost three.
        victim.entrenchment = maxOf(0, entrenchedBefore - ENTRENCHMENT_DAMAGE)
        victim.ammo = maxOf(0, victim.ammo - SUPPLY_DAMAGE)
        victim.fuel = maxOf(0, victim.fuel - SUPPLY_DAMAGE)
        return BarrageResult(hit = true, strengthLost = STRENGTH_DAMAGE)
    }

    /** *"reduce a City, Airfield... or Port to rubble, making them unusable until Repaired"* — which
     *  is exactly what `Can Blow`'s raze already does, records included, so it is the same operation
     *  and Repair puts it back the same way (`rules/Engineering`). The hex is left [Hex.rubble] as
     *  well: a wrecked facility is harder to cross than the clear ground under it. */
    private fun wreckTerrain(hex: Hex): BarrageResult {
        hex.razedTerrain = hex.terrain
        hex.terrain = TerrainType.CLEAR.value
        hex.rubble = true
        return BarrageResult(hit = true, wreckedTerrain = true, leftRubble = true)
    }

    /** The bridge half of the same sentence, and the same operation `BLOW_BRIDGE` performs — rails
     *  on the span included, for the reason that job gives. */
    private fun wreckBridge(hex: Hex): BarrageResult {
        hex.blownRoad = hex.road
        hex.road = RoadType.NONE.value
        hex.rubble = true
        val rails = RailDemolition.cut(hex)
        return BarrageResult(hit = true, blewBridge = true, leftRubble = true, cutRail = rails)
    }

    /** Extra movement points a rubbled hex costs on entry. OG's own rubble terrain is roughly twice
     *  the cost of rough ground in the efiles that define it (`TerrainEx` index 17 in AG, Clu and
     *  SON), so one extra point is the conservative end of that. `INFERENCE`. */
    const val RUBBLE_MOVE_SURCHARGE = 1
}
