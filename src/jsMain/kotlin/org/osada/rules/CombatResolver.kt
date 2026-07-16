package org.osada.rules

import org.osada.*
import org.osada.model.*
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Combat resolution: damage rolls, the full cross-indexed attack/defence calculation,
 * support fire, retreat and the firing/range predicates.
 *
 * The heart of the rules engine, extracted from the former `GameRules` god-object.
 * Depends on [HexGeometry] (distance/rings), [UnitPredicates] (unit classification),
 * [Leaders] and [Equipment]. Faithful port of `openpanzer.js`
 * (`calculateAttackResults` ~line 2333 and the `f`/`attackValue` roll ~line 2029);
 * the cross-indexing and rounding subtleties are documented inline.
 */
object CombatResolver {

    /**
     * Core damage roll. Mirrors the legacy `f(p, r, b, m, k)` combat function:
     * the attack power has the target's defense subtracted from it, the result is
     * compressed, then either an expected value (useRandom) or per-strength dice
     * rolls are computed against a hit threshold. [attacker]'s strength and class
     * drive the roll; [defender] supplies the resilience leader check.
     */
    internal fun attackValue(attackPower: Int, defense: Int, attacker: GameUnit, defender: GameUnit, useRandom: Boolean): Int {
        val attackerClass = attacker.unitData().uclass
        var p = (attackPower - defense).toDouble()
        var target = 15
        if (p > 4) p = 4 + (2 * p - 8) / 5
        if (Leaders.unitHasLeader(attacker, LeaderType.OVERWHELMING_ATTACK)) p += 2
        if (Leaders.unitHasLeader(defender, LeaderType.RESILIENCE)) p -= 2
        if (attackerClass == UnitClass.ARTILLERY.value
            || attackerClass == UnitClass.LEVEL_BOMBER.value
            || attackerClass == UnitClass.FORTIFICATION.value
            || (UnitPredicates.isSea(attacker) && !UnitPredicates.isSea(defender))
        ) {
            target = 19
        }
        var kills = 0.0
        if (useRandom) {
            var q = p + 21 - target
            if (q < 1) q = 1.0
            if (q > 19) q = 19.0
            kills = (5 * q * attacker.strength + 50) / 100
        } else {
            for (i in 0 until attacker.strength) {
                var roll = ((Random.nextDouble() * 20).toInt() + 1).toDouble()
                if (roll > 1 && roll < 20) roll += p
                if (roll >= target) kills += 1
            }
        }
        if (Leaders.unitHasLeader(attacker, LeaderType.OVERWHELMING_ATTACK)) kills += 1
        // roundToInt() rounds ties toward +infinity, matching JS Math.round
        // (kotlin.math.round uses banker's/half-to-even rounding — wrong here).
        return kills.roundToInt()
    }

    fun calculateAttackResults(attacker: GameUnit, defender: GameUnit, useRandom: Boolean): CombatResults {
        val result = CombatResults()
        if (attacker == null || defender == null) return result

        val aPos = attacker.getPos() ?: return result
        val dPos = defender.getPos() ?: return result
        val attackerData = attacker.unitData()
        var defenderData = defender.unitData()
        val attackerTarget = attackerData.target
        val defenderTarget = defenderData.target
        val attackerHex = attacker.getHex() ?: return result
        val defenderHex = defender.getHex() ?: return result
        var distance = HexGeometry.distance(aPos.row, aPos.col, dPos.row, dPos.col)

        var aTerrain = attackerHex.terrain
        var dTerrain = defenderHex.terrain
        if (UnitPredicates.isAir(attacker)) aTerrain = TerrainType.CLEAR.value
        if (UnitPredicates.isAir(defender)) dTerrain = TerrainType.CLEAR.value

        // Mounted infantry that is not surprised dismounts and fights with its own
        // (base) stats instead of the transport's. Mirrors JS: n = Equipment.equipment[k.eqid].
        // defenderTarget above keeps the original (transport) target type, as in JS.
        if (defender.isMounted && !defender.isSurprised && defender.unitData(true).uclass == UnitClass.INFANTRY.value) {
            defenderData = defender.unitData(true)
        }

        val entrenchmentIntact = isEntrenchmentIntact(attacker, defender, dTerrain)
        val attackerEntrenchmentIntact = isEntrenchmentIntact(defender, attacker, aTerrain)

        // Base attack/defense values. These are CROSS-INDEXED (mirrors JS): the
        // defender's attack/defense are selected by the ATTACKER's target type, and
        // the attacker's by the DEFENDER's target type. (e.g. a tank attacking
        // infantry uses its softatk, not its hardatk.)
        var attackerAttack = 0
        var attackerDefense = 0
        var defenderAttack = 0
        var defenderDefense = 0
        when (attackerTarget) {
            UnitType.AIR.value -> { defenderAttack = defenderData.airatk; defenderDefense = defenderData.airdef }
            UnitType.SOFT.value -> { defenderAttack = defenderData.softatk; defenderDefense = defenderData.grounddef }
            UnitType.HARD.value -> { defenderAttack = defenderData.hardatk; defenderDefense = defenderData.grounddef }
            UnitType.SEA.value -> {
                defenderAttack = defenderData.navalatk; defenderDefense = defenderData.grounddef
                if (defenderData.uclass == UnitClass.SUBMARINE.value) attackerDefense = attackerData.closedef
            }
        }
        when (defenderTarget) {
            UnitType.AIR.value -> { attackerAttack = attackerData.airatk; attackerDefense = attackerData.airdef }
            UnitType.SOFT.value -> { attackerAttack = attackerData.softatk; attackerDefense = attackerData.grounddef }
            UnitType.HARD.value -> { attackerAttack = attackerData.hardatk; attackerDefense = attackerData.grounddef }
            UnitType.SEA.value -> {
                attackerAttack = attackerData.navalatk; attackerDefense = attackerData.grounddef
                if (attackerData.uclass == UnitClass.SUBMARINE.value) defenderDefense = defenderData.closedef
            }
        }

        val closeCombat = (UnitPredicates.isCloseCombatTerrain(dTerrain) || defenderData.uclass == UnitClass.FORTIFICATION.value)
                && attackerData.uclass == UnitClass.INFANTRY.value
        if (closeCombat) {
            defenderDefense = defenderData.closedef
            if (defenderData.uclass == UnitClass.INFANTRY.value) {
                // infantry vs infantry: attacker also defends with its close-combat value
                attackerDefense = attackerData.closedef
            } else {
                attackerAttack += 4
            }
        }

        if (Leaders.unitHasLeader(attacker, LeaderType.AGGRESSIVE_ATTACK)) {
            attackerAttack += 4
            attackerDefense += 2
        }
        if (Leaders.unitHasLeader(defender, LeaderType.AGGRESSIVE_ATTACK)) {
            defenderAttack += 2
            defenderDefense += 2
        }
        if (Leaders.unitHasLeader(attacker, LeaderType.DETERMINED_DEFENSE)) {
            attackerDefense += 2
        }
        if (Leaders.unitHasLeader(defender, LeaderType.DETERMINED_DEFENSE)) {
            defenderAttack += 2
            defenderDefense += 4
        }
        if (Leaders.unitHasLeader(attacker, LeaderType.TENACIOUS_DEFENSE) && defenderTarget != UnitType.AIR.value) {
            attackerDefense += 4
        }
        if (Leaders.unitHasLeader(defender, LeaderType.TENACIOUS_DEFENSE) && attackerTarget != UnitType.AIR.value) {
            defenderAttack += 4
        }

        if (UnitPredicates.isAir(attacker) && Leaders.unitHasLeader(attacker, LeaderType.SKILLED_GROUND_ATTACK) && UnitPredicates.isGround(defender)) {
            attackerAttack += 4
        }
        if (defenderData.uclass == UnitClass.ARTILLERY.value) defenderDefense += 3
        if (dTerrain == TerrainType.CITY.value) defenderDefense += 4
        if ((dTerrain == TerrainType.RIVER.value || dTerrain == TerrainType.STREAM.value) && defenderHex.road == RoadType.NONE.value) {
            attackerAttack += 4
            attackerDefense += 4
        }
        if ((aTerrain == TerrainType.RIVER.value || aTerrain == TerrainType.STREAM.value) && attackerHex.road == RoadType.NONE.value) {
            defenderAttack += 4
            defenderDefense += 4
        }

        var attackerEntrenchment = 0
        var defenderEntrenchment = 0
        if (attackerEntrenchmentIntact && !closeCombat) attackerEntrenchment = attacker.entrenchment
        if (entrenchmentIntact) defenderEntrenchment = defender.entrenchment

        if (Leaders.unitHasLeader(attacker, LeaderType.INFILTRATION_TACTICS)
            && !Leaders.unitHasLeader(defender, LeaderType.FEROCIOUS_DEFENSE)
        ) {
            defenderEntrenchment = 0
        }
        if (Leaders.unitHasLeader(defender, LeaderType.INFILTRATION_TACTICS)
            && !Leaders.unitHasLeader(attacker, LeaderType.FEROCIOUS_DEFENSE)
        ) {
            attackerEntrenchment = 0
        }

        attackerDefense += attackerEntrenchment
        defenderDefense += defenderEntrenchment

        if (defenderData.uclass == UnitClass.INFANTRY.value
            && UnitPredicates.isCloseCombatTerrain(dTerrain)
            && !closeCombat
            && attackerData.uclass > UnitClass.INFANTRY.value
            && attackerData.uclass < UnitClass.GROUND_TRANSPORT.value
        ) {
            // infantry in close terrain attacked by a vehicle: its entrenchment counts twice
            defenderDefense += defenderEntrenchment
        }

        attackerAttack += attacker.experience / 100
        attackerDefense += attacker.experience / 100
        defenderAttack += defender.experience / 100
        defenderDefense += defender.experience / 100

        if (attackerData.uclass != UnitClass.ARTILLERY.value
            && attackerData.uclass != UnitClass.FORTIFICATION.value
            && (attackerData.uclass < UnitClass.SUBMARINE.value || attackerData.uclass > UnitClass.LIGHT_CRUISER.value)
        ) {
            val hitsPenalty = 2 * defender.hits
            defenderDefense = if (defenderDefense <= hitsPenalty) 0 else defenderDefense - hitsPenalty
        }

        val antiTankMoved = attackerData.uclass == UnitClass.ANTI_TANK.value && attacker.hasMoved
                && !Leaders.unitHasLeader(attacker, LeaderType.TANK_KILLER)
        if (UnitPredicates.isGround(attacker) && UnitPredicates.isGround(defender) && !closeCombat) {
            if (distance <= 1 || getUnitAttackRange(defender) < distance) {
                if (!antiTankMoved) attackerDefense += attackerData.rangedefmod / 2
                defenderDefense += defenderData.rangedefmod / 2
            } else {
                if (!antiTankMoved) attackerDefense += attackerData.rangedefmod
                defenderDefense += defenderData.rangedefmod
            }
        }

        var initiativeDiff = attackerData.initiative - defenderData.initiative
        if (Leaders.unitHasLeader(attacker, LeaderType.FIRST_STRIKE)) initiativeDiff = abs(initiativeDiff)
        if (Leaders.unitHasLeader(defender, LeaderType.FIRST_STRIKE)) initiativeDiff = -abs(initiativeDiff)
        if (initiativeDiff >= 0) {
            attackerDefense += 4
            attackerAttack += minOf(4, initiativeDiff)
        } else {
            defenderDefense += 4
            defenderAttack += minOf(4, -initiativeDiff)
        }

        if (distance == 1 && entrenchmentIntact && isRuggedDefense(attacker, defender)) {
            result.isRugged = true
        }

        if (attacker.isSurprised || result.isRugged) {
            attackerDefense = 0
            attackerAttack /= 2
        }

        if (distance > 1 && !(UnitPredicates.isSea(attacker) && UnitPredicates.isSea(defender) && defenderData.gunrange >= distance)) {
            result.defcanfire = false
        }
        if (!canFire(defender, attacker)) {
            result.defcanfire = false
        }

        result.kills = attackValue(attackerAttack, defenderDefense, attacker, defender, useRandom)
        if (result.defcanfire) {
            result.losses = attackValue(defenderAttack, attackerDefense, defender, attacker, useRandom)
        }

        if (attackerData.uclass == UnitClass.TANK.value
            && result.losses <= 1
            && result.kills >= defender.strength
            && distance == 1
            && !attacker.isSurprised
        ) {
            result.isOverrun = true
        }

        val attackerKillExp = (defenderAttack + 6 - attackerDefense).coerceAtLeast(1)
        val attackerSurviveExp = (defenderDefense + 6 - attackerAttack).coerceAtLeast(1)
        val defenderKillExp = (attackerAttack + 6 - defenderDefense).coerceAtLeast(1)
        val defenderSurviveExp = (attackerDefense + 6 - defenderAttack).coerceAtLeast(1)

        result.atkExpGained = ((attackerKillExp * (defender.strength / 10.0) + attackerSurviveExp) * result.kills).toInt()
        result.defExpGained = 2 * result.kills
        if (result.defcanfire) {
            result.atkExpGained += 2 * result.losses
            result.defExpGained += ((defenderKillExp * (attacker.strength / 10.0) + defenderSurviveExp) * result.losses).toInt()
        }

        val maxAtkExp = UNIT_MAX_EXPERIENCE - attacker.experience
        if (result.atkExpGained > maxAtkExp) result.atkExpGained = maxAtkExp
        val maxDefExp = UNIT_MAX_EXPERIENCE - defender.experience
        if (result.defExpGained > maxDefExp) result.defExpGained = maxDefExp

        return result
    }

    /**
     * Aggregates the main attack with any defender support fire into a single result.
     * [full] returns the true totals; otherwise only casualties from units visible to
     * the attacking side are reported (fog of war).
     */
    fun calculateCombatResults(attacker: GameUnit, defender: GameUnit, units: List<GameUnit>, full: Boolean, useRandom: Boolean): CombatResults {
        val result = CombatResults()
        if (attacker == null || defender == null) return result
        val attackerSide = attacker.player?.side ?: return result
        val supportUnits = if (!attacker.isSurprised) getSupportFireUnits(units, attacker, defender) else emptyList()
        var totalKills = 0
        var totalLosses = 0
        var visibleKills = 0
        var visibleLosses = 0

        supportUnits.forEach { support ->
            val supportHex = support.getHex() ?: return@forEach
            val supportResult = calculateAttackResults(support, attacker, useRandom)
            if (supportHex.isSpotted(attackerSide) || support.tempSpotted) {
                visibleKills += supportResult.kills
                visibleLosses += supportResult.losses
            }
            totalKills += supportResult.kills
            totalLosses += supportResult.losses
        }

        val mainResult = calculateAttackResults(attacker, defender, useRandom)
        totalKills += mainResult.losses
        totalLosses += mainResult.kills
        visibleKills += mainResult.losses
        visibleLosses += mainResult.kills

        if (full) {
            result.losses = totalKills
            result.kills = totalLosses
        } else {
            result.losses = minOf(visibleKills, attacker.strength)
            result.kills = minOf(visibleLosses, defender.strength)
        }
        result.defcanfire = mainResult.defcanfire
        result.isOverrun = mainResult.isOverrun
        result.isRugged = mainResult.isRugged
        return result
    }

    /** Adjacent friendly units that fire in support of [defender] against [attacker]. */
    fun getSupportFireUnits(units: List<GameUnit>, attacker: GameUnit, defender: GameUnit): List<GameUnit> {
        val aPos = attacker.getPos() ?: return emptyList()
        val dPos = defender.getPos() ?: return emptyList()
        if (HexGeometry.distance(aPos.row, aPos.col, dPos.row, dPos.col) > 1) return emptyList()
        val defenderSide = defender.player?.side ?: return emptyList()
        return units.filter { support ->
            if (support.player?.side != defenderSide || support.id == defender.id) return@filter false
            val sPos = support.getPos() ?: return@filter false
            val sData = support.unitData()
            val range = if (sData.gunrange == 0) 1 else sData.gunrange
            if (HexGeometry.distance(sPos.row, sPos.col, aPos.row, aPos.col) > range) return@filter false
            if (UnitPredicates.isAir(attacker)) {
                sData.uclass == UnitClass.FLAK.value
                        || sData.uclass == UnitClass.AIR_DEFENCE.value
                        || sData.uclass == UnitClass.FIGHTER.value
            } else {
                sData.uclass == UnitClass.ARTILLERY.value
            } && canInitiateAttack(support, attacker)
        }
    }

    /** Attack range for [unit] (min 1, plus the Marksman leader bonus). */
    fun getUnitAttackRange(unit: GameUnit): Int {
        if (unit == null) return 0
        var range = unit.unitData().gunrange
        if (range == 0) range = 1
        if (Leaders.unitHasLeader(unit, LeaderType.MARKSMAN)) range += 1
        return range
    }

    /** Cells [unit] can attack this turn (ground and/or air targets within range). */
    fun getUnitAttackCells(map: Array<Array<Hex>>?, unit: GameUnit, rows: Int, cols: Int): Array<Cell> {
        val result = mutableListOf<Cell>()
        if (unit.hasFired || unit.getAmmo() <= 0) return result.toTypedArray()
        if (airGroundedByWeather(unit)) return result.toTypedArray()
        val pos = unit.getPos() ?: return result.toTypedArray()
        val range = getUnitAttackRange(unit)
        val cells = HexGeometry.getRing(pos.row, pos.col, range, rows, cols, false)
        cells.add(Cell(pos.row, pos.col))
        cells.forEach { cell ->
            val hex = map?.getOrNull(cell.row)?.getOrNull(cell.col) ?: return@forEach
            if (hex.getAttackableUnit(unit, false) != null) {
                if (UnitPredicates.isAir(unit) && range <= 1) {
                    // Air unit with range <= 1: can only ground-attack from its own hex,
                    // then fall through to the air-target check below (matches JS).
                    if (cell.row == pos.row && cell.col == pos.col) result.add(cell)
                } else {
                    result.add(cell)
                    return@forEach
                }
            }
            val airAttackable = hex.getAttackableUnit(unit, true)
            if (airAttackable != null) {
                if (UnitPredicates.isAir(unit)) {
                    if (UnitPredicates.isAir(airAttackable)) result.add(cell)
                } else {
                    result.add(cell)
                }
            }
        }
        return result.toTypedArray()
    }

    /** Air units cannot INITIATE attacks in bad weather (Overcast/Rain/Snow); they may still defend.
     *  Per the openpanzer manual: Overcast/Raining/Snowing → "Air units can't attack". Internal
     *  (not private): the UI layer needs this too, to explain a silently-empty attack range
     *  instead of leaving the player to guess why a plane "can't shoot" (surfaced via
     *  [GameRules.airGroundedByWeather]). */
    internal fun airGroundedByWeather(attacker: GameUnit): Boolean =
        UnitPredicates.isAir(attacker) && (GameHolder.instance?.scenario?.atmosferic ?: 0) != 0

    fun canInitiateAttack(attacker: GameUnit, defender: GameUnit): Boolean {
        if (attacker == null || defender == null || attacker.destroyed || defender.destroyed) return false
        if (airGroundedByWeather(attacker)) return false
        if (!UnitPredicates.isEnemy(attacker, defender)) return false
        if (!Equipment.canInitiateAttackOnUnitType(attacker.getEqid(), defender.getEqid())) return false
        return canFire(attacker, defender)
    }

    fun canFire(attacker: GameUnit, defender: GameUnit): Boolean {
        if (attacker == null || attacker.destroyed || attacker.getAmmo() <= 0) return false
        if (defender == null || defender.destroyed) return false
        if (!UnitPredicates.isEnemy(attacker, defender)) return false
        if (UnitPredicates.isAir(defender) && attacker.unitData().airatk <= 0) return false
        return true
    }

    fun isInAttackRange(attacker: GameUnit, defender: GameUnit): Boolean {
        val aPos = attacker.getPos() ?: return false
        val dPos = defender.getPos() ?: return false
        return HexGeometry.distance(aPos.row, aPos.col, dPos.row, dPos.col) <= getUnitAttackRange(attacker)
    }

    /** Whether a unit losing [current] of [original] strength is past its retreat threshold. */
    fun isLossOverRetreatThreshold(current: Int, original: Int): Boolean {
        return (original - current).toDouble() / original >= UNIT_RETREAT_THRESHOLD - (10 - original) / 2.0 / 10
    }

    /** Whether [defender] should retreat after taking losses (ground-vs-ground only). */
    fun shouldDefenderRetreat(attacker: GameUnit, defender: GameUnit, originalStrength: Int): Boolean {
        if (!UnitPredicates.isGround(attacker) || !UnitPredicates.isGround(defender)) return false
        val defenderClass = defender.unitData().uclass
        if (defenderClass == UnitClass.ARTILLERY.value
            || defenderClass == UnitClass.TACTICAL_BOMBER.value
            || defenderClass == UnitClass.FORTIFICATION.value
        ) return false
        return isLossOverRetreatThreshold(defender.strength, originalStrength)
    }

    /** First passable, empty hex [unit] can retreat into, preferring its rear facing.
     *  [hasRailData] mirrors MovementRules.getMoveRange's guard: a train may only retreat onto
     *  rail once the map actually carries rail data (tools/og-import/add_rails.py) — on any
     *  scenario not yet re-patched it falls back to the pre-existing (unrestricted) check. */
    fun getRetreatPosition(map: Array<Array<Hex>>?, unit: GameUnit, rows: Int, cols: Int, hasRailData: Boolean = false): Cell? {
        if (unit == null) return null
        val data = unit.unitData()
        if (data.movpoints == 0) return null
        val pos = unit.getPos() ?: return null
        val facingIndex = abs(unit.facing - 8)
        val adjacent = HexGeometry.getAdjacent(pos.row, pos.col)
        val preferredIndex = HexGeometry.facingToAdjacentIndex(facingIndex)
        val ordered = mutableListOf<Cell>()
        ordered.add(adjacent[preferredIndex])
        adjacent.forEachIndexed { index, cell ->
            if (index != preferredIndex) ordered.add(cell)
        }
        val isTrain = UnitPredicates.isTrain(unit)
        val enforceRail = isTrain && hasRailData
        // movTable[RAIL.value] is intentionally all-255 (Constants.kt) -- a real train must
        // resolve through WHEELED for any plain-terrain retreat check, same reasoning as
        // MovementRules.getReinforcementDeployPositions.
        val movementTable = movTable[if (isTrain) MovMethod.WHEELED.value else data.movmethod]
        fun tryRetreat(requireRail: Boolean): Cell? {
            ordered.forEach { cell ->
                if (cell.row == 0 && cell.col % 2 == 0) return@forEach
                if (cell.row == rows - 1 && cell.col % 2 == 1) return@forEach
                val hex = map?.getOrNull(cell.row)?.getOrNull(cell.col) ?: return@forEach
                val passable = if (requireRail) hex.rail > RoadType.NONE.value else movementTable[hex.terrain] < 255
                if (hex.unit == null && passable) return Cell(cell.row, cell.col)
            }
            return null
        }
        if (enforceRail) tryRetreat(requireRail = true)?.let { return it }
        return tryRetreat(requireRail = false)
    }

    /** Whether the defender's entrenchment is strong enough to trigger a rugged defense. */
    fun isRuggedDefense(attacker: GameUnit, defender: GameUnit): Boolean {
        var aExp = attacker.experience / 100 + 2
        var dExp = defender.experience / 100 + 2
        if (Leaders.unitHasLeader(attacker, LeaderType.TENACIOUS_DEFENSE)) aExp += 2
        if (Leaders.unitHasLeader(defender, LeaderType.TENACIOUS_DEFENSE)) dExp += 2
        val aRate = unitEntrenchRate[attacker.unitData().uclass] + 1
        val dRate = unitEntrenchRate[defender.unitData().uclass] + 1
        // Floating-point throughout, as in the JS reference (integer division here
        // would truncate intermediate results and change the trigger threshold).
        return 50 < 5.0 * (50.0 * dExp / aExp) * dRate * defender.entrenchment / (50 * aRate)
    }

    /** Whether [defender]'s entrenchment still applies against [attacker] in [terrain]. */
    fun isEntrenchmentIntact(attacker: GameUnit, defender: GameUnit, terrain: Int): Boolean {
        if (Leaders.unitHasLeader(defender, LeaderType.FEROCIOUS_DEFENSE)) return true
        if (Equipment.ignoresEntrenchment(attacker.eqid)) return false
        if ((Leaders.unitHasLeader(attacker, LeaderType.INFILTRATION_TACTICS)
                    || Leaders.unitHasLeader(attacker, LeaderType.STREET_FIGHTER))
            && (terrain == TerrainType.CITY.value || terrain == TerrainType.ROUGH.value || terrain == TerrainType.PORT.value)
        ) {
            return false
        }
        return true
    }
}
