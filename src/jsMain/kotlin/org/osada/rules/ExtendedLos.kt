package org.osada.rules

import org.osada.GameHolder
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.model.ATTR2_MASK_ALLOW_LOF
import org.osada.model.ATTR2_MASK_CUT_LOS
import org.osada.model.ATTR2_MASK_ROCKET_BOMBER
import org.osada.model.Cell
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey

/**
 * Open General's **Extended LOS** optional rule (manual §9.5), plus the two equipment attributes
 * that only mean anything while it is on.
 *
 * `docs/og-fidelity-plan.md` never tracked this as work. §B.5 mentions it exactly once, in a
 * parenthetical predicting that *"Extended LOS, if ever built, is a third key and not a value of
 * either"* — of `spotting_memory` or `installation_spotting`. That prediction is honoured here:
 * [RuleKey.EXTENDED_LOS] is a third key beside those two, and it is OFF by default, so nothing in
 * the 502 shipped scenarios changes until a profile asks for it.
 *
 * ### What OG's four bullets are, and which three are here
 *
 * | # | OG §9.5 | Here |
 * |---|---|---|
 * | 1 | *"Mountains, hills forest and city hexes cut LOS to the hexes opposite them"*
 *     | [hasLineOfSight], applied in `MovementRules.setSpotRange` |
 * | 2 | *"Units in forest hexes aren't spotted by other ground units, unless adjacent"* | [isConcealedByTerrain] |
 * | 3 | *"Units in forest or city hexes aren't spotted by air units more than 2 hexes away"* | [isConcealedByTerrain] |
 * | 4 | *"Units that cannot move and are spotted, remain located, but can only be
 *     inspected if within spotting range"* | **not built** — see below |
 *
 * **Bullet 4 is deliberately not built, and this is the place that says so.** It is an INSPECTION
 * rule, not a visibility rule: the unit stays on the map and only the detail panel is withheld.
 * OSADA has no "located but not inspectable" state, and inventing one would mean a new per-unit
 * flag, its serialization, and an answer for what the enemy card shows instead — for a rule whose
 * whole effect is to hide statistics the player has already seen once. Recorded rather than
 * approximated.
 *
 * ### Why bullet 1 is safe to write into the spotting counters and the abilities are not
 *
 * `Hex.setSpotted` is a per-side REFERENCE COUNT, and the fog is only correct while every remove
 * cancels an add made over the same set of cells (`model/GameMapGrid.recomputeSpotting`). Bullet 1
 * blocks on TERRAIN, which is fixed for the whole scenario — except where `rules/Engineering` builds
 * or razes something, and that path calls `recomputeSpotting()` for exactly this reason. So the add
 * and the remove always see the same map.
 *
 * A unit carrying `Cut LOS` does NOT satisfy that: it walks around, so a spotting range computed
 * while it stood there cannot be cancelled after it leaves, and the counters would strand above
 * zero — permanently revealed hexes no toggle can put back.
 *
 * **That was solved on 2026-08-28 rather than lived with (§Z.4), and `Cut LOS` now blocks sight as
 * well as fire.** The escape is the one `rules/Engineering` already used for terrain that changes
 * mid-scenario: when a sight-blocking unit appears, moves, is undone or dies,
 * `GameMap.rebuildSpottingForSightBlocker` calls `recomputeSpotting()` and re-derives every counter
 * from the units currently on the map, so nothing depends on an add and a remove having seen the
 * same geometry. The rebuild is skipped entirely unless the moving unit is a blocker AND the key is
 * on, which is no shipped scenario by default.
 *
 * **The line-of-fire reading is KEPT.** OG's own wording puts `Cut LOS` on sight, which is why the
 * sight half is now built; whether it *also* blocks fire is not settled, and `UnitsBlockDLOF` needs
 * the machinery either way. Keeping both is the superset, so no scenario loses a shot it had.
 * `docs/og-open-questions.md` carries the question.
 *
 * ### Line of fire is behind this key even though OG §6.18 is not an optional rule
 *
 * OG states terrain-blocked ranged fire in section 6, i.e. as a CORE rule, and OSADA has never had
 * it — `AttackEligibility.isInAttackRange` was pure distance. It is gated here anyway, because
 * switching it on universally would make some authored attacks impossible in every one of the 502
 * shipped scenarios at once. That is the §5.10 hazard the whole fidelity plan is written around; a
 * rule that re-tunes shipped content belongs behind a key the player chooses.
 *
 * ### §6.18 in full, and the two scenario options that tune it (§T, 2026-08-26)
 *
 * The manual's own paragraph is quoted here because two thirds of it were missing until §T, and
 * both omissions made OSADA block MORE shots than OG does:
 *
 * > **6.18. Ranged Fire.** *"Ranged fire is different from artillery and air defense fire. Some
 * > units can attack at ranges greater than one hex, but are affected by terrain: hills, mountains,
 * > cities, forest and bocage cut the line of fire of these units, making an attack impossible."*
 *
 * 1. **Artillery and air defence are exempt, and the manual says so in its first sentence.**
 *    *"These units"* is the ranged-fire units the second sentence introduces, which that first
 *    sentence exists to separate from artillery and AD fire. Two other passages agree: §9.6 has to
 *    ADD *"submarines need direct LOF to attack"* as an extended-naval rule, which would be
 *    redundant if every attack needed one, and §9.2's barrage fires into hexes the gun cannot see
 *    at all. See [terrainCutsFireOf].
 * 2. **The reach of the check is the `TrueDLOF` scenario option.** OG's own label for it is
 *    *"Mountains,Cities && Forest blocks direct LOF even if range>2"* (string template line 575), so
 *    without it the terrain check reaches [DIRECT_LOF_RANGE] hexes and no further. 131 of the 397
 *    scenarios that carry the bitfield set it.
 *
 * And **`UnitsBlockDLOF`** (template line 576, *"Friend Units ALSO block LOF (except if light
 * special)"*) is the other half of [blocksLineOfFire]: with it, every unit in the way blocks the
 * shot, not only one carrying `Cut LOS`. 23 scenarios set it, and it is what finally gives
 * `Allow LOF` — 561 records — something to be an exception to.
 *
 * **What an ABSENT switch means here is not what it means for Build and Repair.** `Engineering`
 * reads `null` as permission because reading an unreadable author's silence as a prohibition would
 * take a player ACTION away. Neither of these two takes anything away: both are `false` when
 * absent, which is OG's own default for an unset bit, and both defaults leave the player with MORE
 * legal attacks rather than fewer. The 105 scenarios with no readable source get the widest
 * reading, exactly as they do under `Engineering`, by the same principle applied to a switch that
 * points the other way.
 */
internal object ExtendedLos {
    /** OG's own reading of "more than 2 hexes away" for an air observer (§9.5 bullet 3). */
    private const val AIR_SPOT_LIMIT_IN_COVER = 2

    /**
     * How far §6.18's terrain check reaches without the `TrueDLOF` scenario option.
     *
     * Read straight off OG's own label for the option — *"blocks direct LOF even if range>2"* — so
     * the engine's default is to stop at 2 and the option is what extends it. A range-2 shot has
     * exactly one hex in between, which is the case this bound keeps checked for everybody.
     */
    private const val DIRECT_LOF_RANGE = 2

    /**
     * Whether the rule is in force: the ruleset key AND the scenario's own switch.
     *
     * OG states this one as *"When this scenario option is activated"* (§9.5's opening line), and
     * the option is authored per scenario — imported 2026-08-26 as `extlos`, set by 321 of the 397
     * scenarios that carry the attribute. Off in every profile except Open General Fidelity either
     * way.
     *
     * **A scenario with no imported attribute (`null`) follows the key alone**, for the reason
     * `Engineering.authorisedByScenario` gives: 105 deployed scenarios have no readable source, and
     * their silence must not be read as a prohibition.
     */
    fun enabled(): Boolean =
        ActiveRuleset.flag(RuleKey.EXTENDED_LOS, false) &&
            (GameHolder.instance?.scenario?.extendedLos ?: true)

    /**
     * The terrain OG names as sight-blocking in §9.5 — mountain, hill, forest and city — plus
     * BOCAGE, which §6.18 adds for line of fire and which is the same kind of feature.
     *
     * PORT and AIRFIELD are NOT included even though `SpottingModel` groups them with CITY for
     * installation vision: those two are flat by definition, and OG's sentence names city alone.
     */
    private fun blocksSight(terrain: Int): Boolean =
        terrain == TerrainType.MOUNTAIN.value ||
            terrain == TerrainType.HILL.value ||
            terrain == TerrainType.FOREST.value ||
            terrain == TerrainType.CITY.value ||
            terrain == TerrainType.BOCAGE.value

    /**
     * Whether nothing on the map blocks sight from (`fromRow`,`fromCol`) to (`toRow`,`toCol`).
     *
     * Endpoints never block: standing in a forest does not stop you seeing out of it, and the
     * target's own cover is bullets 2 and 3's business, not this one's. Always true with the key
     * off, and always true at distance 1.
     */
    fun hasLineOfSight(
        grid: Array<Array<Hex>>?,
        fromRow: Int,
        fromCol: Int,
        toRow: Int,
        toCol: Int,
    ): Boolean {
        if (!enabled() || grid == null) return true
        return HexGeometry.lineBetween(fromRow, fromCol, toRow, toCol).none { cell ->
            val hex = grid.getOrNull(cell.row)?.getOrNull(cell.col)
            blocksSight(hex?.terrain ?: TerrainType.CLEAR.value) || isSightBlocker(hex?.unit)
        }
    }

    /**
     * Whether [attacker] has an unobstructed line of fire to [defender].
     *
     * Two independent obstructions, and each has its own OG source and its own scenario switch:
     *
     *  - **terrain**, OG §6.18 — but only for the fire that section governs ([terrainCutsFireOf]),
     *    and only as far as [DIRECT_LOF_RANGE] unless the scenario sets `TrueDLOF`;
     *  - **units in the way** ([blocksLineOfFire]) — one carrying `Cut LOS` always, every one of
     *    them where the scenario sets `UnitsBlockDLOF`, and never one carrying `Allow LOF`.
     *
     * Adjacent attacks are never blocked (there is nothing in between), which keeps every close
     * combat in the shipped scenarios untouched even with the key on.
     */
    fun hasLineOfFire(
        attacker: GameUnit,
        defender: GameUnit,
    ): Boolean = !enabled() || lineOfFireClear(attacker, defender)

    /**
     * [hasLineOfFire] WITHOUT the §9.5 gate — §6.18 asked on its own terms.
     *
     * It exists for exactly one caller: `ExtendedNaval.submarineLacksLineOfFire`, OG §9.6's fourth
     * bullet, which imposes the line-of-fire requirement on submarines whether or not Extended LOS
     * is switched on. Everything else must go through [hasLineOfFire], which answers "clear"
     * whenever the key is off — an ungated terrain check applied to all 502 shipped scenarios is
     * the §5.10 hazard this file's header is largely about.
     *
     * The two scenario options still apply here, because `TrueDLOF` and `UnitsBlockDLOF` tune
     * §6.18 rather than §9.5, and a submarine firing under §9.6 is firing under §6.18.
     */
    @Suppress("ReturnCount") // the unresolvable case, the adjacent case and the answer
    fun lineOfFireClear(
        attacker: GameUnit,
        defender: GameUnit,
    ): Boolean {
        val from = attacker.getPos()
        val to = defender.getPos()
        val grid =
            GameHolder.instance
                ?.scenario
                ?.map
                ?.map
        // Anything unknown means "not blocked": refusing an attack because the geometry could
        // not be resolved would take a legal order away with no explanation.
        if (from == null || to == null || grid == null) return true
        val between = HexGeometry.lineBetween(from.row, from.col, to.row, to.col)
        if (between.isEmpty()) return true
        // `lineBetween` returns the hexes strictly between the two, so the distance is one more.
        val terrainCuts = terrainCutsFireOf(attacker, defender, between.size + 1)
        val everyUnitBlocks = unitsBlockLineOfFire
        return between.none { cell ->
            val hex = grid.getOrNull(cell.row)?.getOrNull(cell.col)
            hex != null &&
                ((terrainCuts && blocksSight(hex.terrain)) || blocksLineOfFire(hex.unit, everyUnitBlocks))
        }
    }

    /**
     * Whether §6.18's terrain check applies to a shot [attacker] is taking over [distance] hexes.
     *
     * Both halves come from the manual rather than from OSADA's convenience, and both were missing
     * until §T — see this file's header for the quotes.
     *
     * **Artillery and air defence never have their line of fire cut by terrain.** §6.18 opens by
     * separating *"ranged fire"* from *"artillery and air defense fire"* and then applies the rule
     * to *"these units"*, meaning the former. FLAK is not listed and is not excluded here either:
     * OG §8.1.4 gives it range 1, so nothing is ever in between and the question does not arise.
     */
    private fun terrainCutsFireOf(
        attacker: GameUnit,
        defender: GameUnit,
        distance: Int,
    ): Boolean {
        val data = attacker.unitData()
        val uclass = data.uclass
        val exempt =
            uclass == UnitClass.ARTILLERY.value ||
                uclass == UnitClass.AIR_DEFENCE.value ||
                rocketBomberFreeOfTerrain(data, defender)
        return !exempt && (distance <= DIRECT_LOF_RANGE || trueDirectLineOfFire)
    }

    /**
     * OG's `Rocket bomber` (`attr2` bit 3), wired 2026-08-27 — the third exemption from the terrain
     * half of §6.18, beside artillery and air defence.
     *
     * > *"Rocket bomber: unit can attack ground units within full range."* — manual §7.2
     * >
     * > *"can attack ground units within its firing range using any hex types"* —
     * > `OG_ABILITY_AUDIT.md` §7.10
     *
     * **The audit's own sentence is the whole specification, and it names the limit as well as the
     * grant.** *"Using any hex types"* is a statement about terrain and nothing else: §7.10 says
     * outright that the ability *"does not establish that range, ammunition, target class,
     * spotting, line of fire or weather are bypassed"* and that *"implementing anything broader
     * than 'bypass the attacker/target hex-type restriction' is speculation"*. So this exempts the
     * shot from [blocksSight]'s terrain test and from nothing else — units in the way still block
     * it under `UnitsBlockDLOF`, `Cut LOS` still cuts it, and every other gate is untouched.
     *
     * **Against GROUND units only**, because the sentence says so. An aircraft engaging another
     * aircraft is not what a rocket-armed ground-attack machine is being granted here, and widening
     * it would be the invention §7.10 warns against. 999 shipped records carry the bit — the
     * `Il-2m3` / `Il-10` family and their equivalents.
     *
     * `docs/og-fidelity-plan.md` §M listed the "exact OG condition for hex types" as not well
     * established. It is now: the condition is that there ISN'T one.
     */
    private fun rocketBomberFreeOfTerrain(
        attackerData: org.osada.model.EquipmentData,
        defender: GameUnit,
    ): Boolean = attackerData.attr2 and ATTR2_MASK_ROCKET_BOMBER != 0 && UnitPredicates.isGround(defender)

    /**
     * OG's `TrueDLOF` scenario option: terrain cuts the direct line of fire at ANY range, not just
     * within [DIRECT_LOF_RANGE].
     *
     * Absent (`null`) is `false` — OG's own default for an unset bit, and the reading that leaves
     * the player the most legal attacks. See this file's header for why that differs from
     * `Engineering.authorisedByScenario`.
     */
    private val trueDirectLineOfFire: Boolean
        get() = GameHolder.instance?.scenario?.trueDirectLof ?: false

    /**
     * OG's `UnitsBlockDLOF` scenario option: *"Friend Units ALSO block LOF (except if light
     * special)"*, where the special is `Allow LOF` (*"unit doesn't cut LOF"*, string template line
     * 864).
     *
     * **The word ALSO is the one part of this that is an INFERENCE.** It reads as "friendly units
     * in addition to enemy ones", which would imply a base rule making enemy units block that no
     * OG source in this project states and no option exists for. What is built is the reading that
     * cannot overstate it: with the option on, EVERY unit in the way blocks; with it off, only a
     * `Cut LOS` unit does, exactly as before. The two are a strict superset relation, so no shipped
     * scenario can lose a shot it had. If OG turns out to block on enemy units by default, this is
     * the sentence to correct, and it is a widening rather than a rewrite.
     *
     * Absent (`null`) is `false`, for the same reason [trueDirectLineOfFire] is.
     */
    private val unitsBlockLineOfFire: Boolean
        get() = GameHolder.instance?.scenario?.unitsBlockLof ?: false

    /**
     * Whether a unit standing in an intervening hex blocks fire through it.
     *
     * `Allow LOF` beats everything, which is what makes it an ability rather than a note: it beats
     * `Cut LOS` on the same record — the more specific statement wins, and its whole purpose is to
     * stop a unit obstructing its own side's shots — and it is `UnitsBlockDLOF`'s only exception.
     */
    private fun blocksLineOfFire(
        unit: GameUnit?,
        everyUnitBlocks: Boolean,
    ): Boolean {
        val attr2 = unit?.unitData(true)?.attr2 ?: return false
        return attr2 and ATTR2_MASK_ALLOW_LOF == 0 &&
            (everyUnitBlocks || attr2 and ATTR2_MASK_CUT_LOS != 0)
    }

    /**
     * Bullets 2 and 3: whether cover hides [unit] from [observerSide] even though its hex is
     * spotted.
     *
     * Asked as *"does any enemy observer legitimately see this formation"* rather than as a
     * property of one observer, because OSADA's fog is a per-hex counter that has already merged
     * every observer by the time anything asks. A unit in cover is concealed exactly when no enemy
     * unit qualifies:
     *
     *  - a GROUND observer sees into a forest only from an adjacent hex, and into a city normally;
     *  - an AIR observer sees into either only within [AIR_SPOT_LIMIT_IN_COVER] hexes;
     *  - either way the observer must have the formation inside its own spotting range and an
     *    unobstructed [hasLineOfSight] to it.
     *
     * **Cost is paid only where the rule applies.** With the key off this returns on its first
     * comparison; with it on, only a unit actually standing in forest or city scans the unit list,
     * and `tempSpotted` (a unit that revealed itself by firing) short-circuits ahead of the scan.
     */
    fun isConcealedByTerrain(
        unit: GameUnit,
        observerSide: Int,
    ): Boolean {
        // `takeIf` rather than two guards: the hex has to be null-checked for the smart cast AND
        // the visibility terms have to short-circuit, and writing both as `return false` costs a
        // third return. Written the other way round -- `hex == null` inside `visibleAnyway` and
        // again in the guard -- the compiler reported the second as always false.
        val visibleAnyway = !enabled() || unit.tempSpotted || UnitPredicates.isAir(unit)
        val hex = unit.getHex()?.takeIf { !visibleAnyway } ?: return false
        val inForest = hex.terrain == TerrainType.FOREST.value
        val inCover = inForest || hex.terrain == TerrainType.CITY.value
        return inCover && !hasQualifiedObserver(unit, observerSide, inForest)
    }

    private fun hasQualifiedObserver(
        unit: GameUnit,
        observerSide: Int,
        inForest: Boolean,
    ): Boolean {
        val target = unit.getPos()
        val map = GameHolder.instance?.scenario?.map
        // No map or no position means nothing can be judged; erring towards VISIBLE is the
        // safe direction, because a wrongly hidden unit is a bug the player cannot see past.
        if (target == null || map == null) return true
        return map.units.any { observer ->
            observer.player?.side == observerSide &&
                !observer.destroyed &&
                qualifies(observer, target, inForest)
        }
    }

    private fun qualifies(
        observer: GameUnit,
        target: Cell,
        inForest: Boolean,
    ): Boolean {
        val pos = observer.getPos() ?: return false
        val distance = HexGeometry.distance(pos.row, pos.col, target.row, target.col)
        val closeEnough =
            if (UnitPredicates.isAir(observer)) {
                distance <= AIR_SPOT_LIMIT_IN_COVER
            } else {
                !inForest || distance <= 1
            }
        val inSpotRange = distance <= MovementRules.getUnitSpotRange(observer)
        val grid =
            GameHolder.instance
                ?.scenario
                ?.map
                ?.map
        return inSpotRange &&
            closeEnough &&
            hasLineOfSight(grid, pos.row, pos.col, target.row, target.col)
    }
}
