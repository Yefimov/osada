package org.osada.model

import org.osada.CombatLog
import org.osada.LeaderType
import org.osada.addObjectiveCapture
import org.osada.hero.HeroCampaign
import org.osada.prestigeGains
import org.osada.rules.CombatPositioning
import org.osada.rules.CounterBatteryFire
import org.osada.rules.CriticalHit
import org.osada.rules.Evade
import org.osada.rules.GameRules
import org.osada.rules.HexGeometry
import org.osada.rules.Kamikaze
import org.osada.rules.Sabotage
import org.osada.rules.SupplyContextRules
import org.osada.rules.UnitCapabilities
import org.osada.rules.calculateAttackResults
import org.osada.rules.getDirection
import org.osada.rules.isBridgeForSide
import org.osada.scoreGains
import kotlin.js.json

/**
 * Applies combat outcomes to the live game state: damage, experience, leader generation,
 * hex capture, and unit retreat. Extracted from the former [GameMap] god-class (SRP).
 *
 * All mutations flow through the [GameMap] facade so the exported surface is unchanged.
 *
 * `TooManyFunctions` is suppressed because every function here is one NAMED STEP of
 * [resolveCombat], and that sequence -- facing, dismount, overrun, damage, counterbattery,
 * scores, leaders -- is the readable statement of what an attack does. Splitting it to satisfy
 * a count would put half an attack in another file.
 */
@Suppress("TooManyFunctions")
internal class CombatApplication(
    private val gameMap: GameMap,
) {
    fun attackUnit(
        attacker: GameUnit,
        defender: GameUnit,
        supportFire: Boolean,
        isOverrun: Boolean = false,
    ): CombatResults {
        gameMap.undoState.invalidate(attacker, UndoInvalidation.COMBAT)
        // Cleared here rather than after reporting, so a combat that draws no counterbattery can
        // never republish the previous combat's events.
        gameMap.lastCounterBattery = emptyList()
        val from = attacker.getPos()
        val to = defender.getPos()
        return if (from != null && to != null) {
            resolveCombat(attacker, defender, from, to, supportFire, isOverrun)
        } else {
            CombatResults()
        }
    }

    private fun resolveCombat(
        attacker: GameUnit,
        defender: GameUnit,
        from: Cell,
        to: Cell,
        supportFire: Boolean,
        isOverrun: Boolean,
    ): CombatResults {
        // committed: this is the call whose result is applied to the map, so it is the one allowed
        // to draw from the shared random stream (`rules/GameRandomSource`).
        val combatResult =
            GameRules.calculateAttackResults(attacker, defender, true, gameMap.getUnits().toList(), committed = true)
        // OG's combat order puts sabotage at step 3 and evasion at step 4, so a sabotaged defender
        // never gets to slip away -- one of the penalties OG names is that it cannot evade.
        val sabotaged = applySabotage(attacker, defender, combatResult, supportFire)
        if (!sabotaged) applyEvade(attacker, defender, from, to, combatResult)
        val logId = CombatLog.addCombatStart(attacker, defender, gameMap.turn)
        applyCombatFacing(attacker, defender, from, to)
        unmountDefenderIfNeeded(defender)
        if (isOverrun) applyOverrun(attacker, defender, combatResult)
        applyCombatDamage(attacker, defender, combatResult, supportFire, isOverrun)
        applyExploitSuccess(attacker, defender, combatResult, supportFire, isOverrun)
        // OG's `Kamikaze`, default model: "dies after... engaged in combat". Applied once the
        // damage has landed, so the attack it died making still counts (`rules/Kamikaze`).
        if (!supportFire && Kamikaze.expendedAfterCombat(attacker)) attacker.destroyed = true
        resolveCounterBattery(attacker, defender, supportFire, isOverrun)
        updateCombatScores(attacker, defender, combatResult)
        generateCombatLeaders(attacker, defender, combatResult)
        CombatLog.addCombatEnd(attacker, defender, logId, supportFire)
        return combatResult
    }

    /**
     * OG 9.4: enemy batteries in range answer artillery that has just fired on one of their own.
     *
     * Runs AFTER the exchange is applied, so a battery that the attack itself destroyed does not
     * answer from the grave, and so the counterbattery result is computed against the attacker's
     * real post-combat strength. Deliberately skipped for support fire and for an overrun: OG's
     * sentence is about an artillery unit *attacking*, and both of those are something else. See
     * [org.osada.rules.CounterBatteryFire] for why the narrowing also matters to multiplayer.
     */
    private fun resolveCounterBattery(
        attacker: GameUnit,
        defender: GameUnit,
        supportFire: Boolean,
        isOverrun: Boolean,
    ) {
        if (supportFire || isOverrun) return
        val responders = CounterBatteryFire.respondersTo(gameMap, attacker, defender)
        if (responders.isEmpty()) return
        gameMap.lastCounterBattery = CounterBatteryFire.applyCounterBattery(gameMap, attacker, responders)
    }

    private fun applyCombatFacing(
        attacker: GameUnit,
        defender: GameUnit,
        from: Cell,
        to: Cell,
    ) {
        if (!GameRules.isBridgeForSide(attacker.getHex(), attacker.player?.side ?: -1)) {
            attacker.facing = GameRules.getDirection(from.row, from.col, to.row, to.col) ?: attacker.facing
        }
        if (!GameRules.isBridgeForSide(defender.getHex(), defender.player?.side ?: -1)) {
            defender.facing = GameRules.getDirection(to.row, to.col, from.row, from.col) ?: defender.facing
        }
    }

    /** The same test `AttackCalculation.resolveCombatContext` uses to swap in the passengers'
     *  own statistics, so the unit that FIGHTS dismounted is exactly the unit that IS dismounted.
     *  Both read OG's `Dismount` toggle rather than the bare Infantry class (wired 2026-08-25). */
    private fun unmountDefenderIfNeeded(defender: GameUnit) {
        if (defender.isMounted &&
            !defender.isSurprised &&
            UnitCapabilities.dismountsWhenAttacked(defender.unitData(true))
        ) {
            gameMap.unmountUnitHandler(defender)
        }
    }

    /**
     * OG's `Evade` — the defender slips the attack and RETREATS out of contact (`rules/Evade`).
     *
     * **Rolled here and nowhere else**, because this is the committed call — the one allowed to
     * draw from the shared random stream (`GameRandomSource`'s first contract rule). A forecast
     * rolling an evade would desynchronise a multiplayer battle.
     *
     * **It is a retreat, not a cancelled attack**, which is the correction of 2026-08-27: the
     * author's combat page makes a retreat hex a PRECONDITION — *"only works if Defender can find a
     * position (hex) to retreat, otherwise is skipped"* — so a unit that evades is one that got
     * away, and leaving it standing on the contested hex was the first build's mistake. The hex is
     * found before the roll for exactly that reason: no hex, no attempt, and no draw on the stream.
     *
     * An evaded attack does no damage **in either direction**. OG's own combat order puts evasion
     * after interception and support fire and before the main exchange, so anything the attacker
     * has already taken from a fighter or a supporting battery stands — this only cancels step 5.
     * The attacker's shot is still spent; `applyCombatDamage` fires it.
     *
     * A surprised attacker or a rugged defence cancels the attempt outright (OpenGen 0.70.0). Both
     * already favour the defender, so the rule is anti-stacking.
     */
    private fun applyEvade(
        attacker: GameUnit,
        defender: GameUnit,
        from: Cell,
        to: Cell,
        combatResult: CombatResults,
    ) {
        val retreat =
            CombatPositioning.getRetreatPosition(gameMap.map, defender, gameMap.rows, gameMap.hasRailData())
        val distance = HexGeometry.distance(from.row, from.col, to.row, to.col)
        val adjacentEnemies = SupplyContextRules.countAdjacentEnemies(gameMap, defender, to)
        val surprisedOrRugged = attacker.isSurprised || combatResult.isRugged
        val evades =
            Evade.rolls(
                attacker,
                defender,
                distance,
                adjacentEnemies,
                hasRetreatHex = retreat != null,
                surprisedOrRugged = surprisedOrRugged,
            )
        if (!evades || retreat == null) return
        combatResult.isEvaded = true
        combatResult.kills = 0
        combatResult.losses = 0
        combatResult.defcanfire = false
        retreatUnit(defender, retreat)
    }

    /**
     * OG's `Exploit Success` (`attrEx` bit 11) — *"after an ordinary attack, if the defender is
     * killed or retreats, the attacker may use its remaining movement"*. Wired 2026-08-27.
     *
     * **It is not Overrun, and the difference is the whole point of having both.** Overrun
     * ([applyOverrun]) predicts a zero-loss kill, skips the normal combat, and hands back the
     * movement AND the shot. This fights the combat, keeps the shot spent, and returns only the
     * movement — so a formation that exploits can walk into the gap it just made, and cannot shoot
     * again from it.
     *
     * Applied AFTER [applyCombatDamage], which is what spends the attack, so the order is: fight,
     * pay for the attack, then give the movement back. It skips a support shot and an overrun,
     * because neither is *"an ordinary attack"*.
     *
     * **Whether the defender retreats is decided outside this class** (`CombatResolver`'s threshold
     * plus the orchestrator's own sequencing), so the condition read here is the half that is
     * settled by the time damage lands: the defender is dead. A defender that survives but is
     * pushed off its hex is the case this does not yet catch, and it is recorded rather than
     * guessed at — `applyExploitSuccess` would have to run after the retreat, which happens in the
     * UI layer.
     */
    private fun applyExploitSuccess(
        attacker: GameUnit,
        defender: GameUnit,
        combatResult: CombatResults,
        supportFire: Boolean,
        isOverrun: Boolean,
    ) {
        val ordinaryAttack = !supportFire && !isOverrun && !combatResult.isEvaded
        val exploited =
            ordinaryAttack &&
                defender.destroyed &&
                UnitCapabilities.exploitsSuccess(attacker.unitData(true))
        if (!exploited) return
        if (attacker.moveLeft > 0) attacker.hasMoved = false
        combatResult.isExploit = true
    }

    /**
     * OG's `Saboteur` — step 3 of the combat order, before evasion and before the main exchange.
     *
     * On success the normal combat does not happen and the defender is left sabotaged; on failure
     * the attacker takes two suppression and the combat proceeds. Either way one ammunition point
     * is spent. `rules/Sabotage` owns the chance and the state; this owns only the sequencing.
     *
     * **Rolled here and nowhere else**, because this is the committed call — the one allowed to
     * draw from the shared random stream.
     *
     * Returns whether the defender was sabotaged, so the caller can skip the evade: a sabotaged
     * unit cannot evade, which is one of the penalties OG lists.
     */
    private fun applySabotage(
        attacker: GameUnit,
        defender: GameUnit,
        combatResult: CombatResults,
        supportFire: Boolean,
    ): Boolean {
        val succeeded =
            !supportFire &&
                Sabotage.canAttempt(attacker, defender) &&
                Sabotage.attempt(attacker, defender)
        if (!succeeded) return false
        combatResult.isSabotage = true
        combatResult.kills = 0
        combatResult.losses = 0
        combatResult.defcanfire = false
        return true
    }

    private fun applyOverrun(
        attacker: GameUnit,
        defender: GameUnit,
        combatResult: CombatResults,
    ) {
        combatResult.kills = defender.strength
        combatResult.isOverrun = true
        combatResult.defcanfire = false
        if (attacker.moveLeft > 0) attacker.hasMoved = false
        attacker.moveLeft += 1
    }

    private fun applyCombatDamage(
        attacker: GameUnit,
        defender: GameUnit,
        combatResult: CombatResults,
        supportFire: Boolean,
        isOverrun: Boolean,
    ) {
        attacker.experience = kotlin.math.round(attacker.experience + combatResult.atkExpGained.toDouble()).toInt()
        defender.experience = kotlin.math.round(defender.experience + combatResult.defExpGained.toDouble()).toInt()
        if (supportFire || isOverrun) attacker.fire(false) else attacker.fire(true)
        // OG's `SingleFireSup.` spends the supporting gun for the turn. Set on the COMMITTED shot
        // only -- this function runs from `attackUnit`, which is the call whose result is applied
        // to the map, so a forecast can never spend a battery that has not fired
        // (`UnitCapabilities.supportsOnlyOncePerTurn`).
        if (supportFire) attacker.hasSupportedThisTurn = true
        // OG's `Shock Tactics` leader trait / `Lasting Suppression` equipment special: suppression
        // this side inflicts outlives the round wrap that clears everybody else's. Resolved here,
        // on the side that DEALT the damage -- `hit` runs on the victim and cannot see who hit it
        // (`docs/og-fidelity-plan.md` A.4, and the equipment half added 2026-08-19 per §0.1).
        // OG's `critical_hit`: a naval shot that sinks its target outright, on a die roll. Applied
        // on the COMMITTED shot only -- like `SingleFireSup.` above -- so it never reaches the
        // attack forecast, which promises the player what will actually happen (`rules/CriticalHit`).
        if (CriticalHit.sinks(attacker, defender)) {
            combatResult.kills = defender.strength
            combatResult.isCriticalHit = true
            combatResult.defcanfire = false
        }
        defender.hit(combatResult.kills, UnitCapabilities.hasLastingSuppression(attacker))
        if (combatResult.defcanfire && !supportFire) {
            defender.fire(false)
            // The defender's return fire can sink the attacker in exactly the same way -- OG's
            // "either attacking or defending" is why the formula is written from the FIRING unit's
            // side rather than the attacker's.
            if (CriticalHit.sinks(defender, attacker)) combatResult.losses = attacker.strength
            attacker.hit(combatResult.losses, UnitCapabilities.hasLastingSuppression(defender))
        }
        if (!supportFire) gameMap.delAttackSel()
    }

    private fun updateCombatScores(
        attacker: GameUnit,
        defender: GameUnit,
        combatResult: CombatResults,
    ) {
        attacker.player?.updateScore(scoreGains["damage"] ?: 0, combatResult.kills)
        attacker.player?.updateScore(
            if (attacker.isCore) {
                scoreGains["casualtyCore"] ?: 0
            } else {
                scoreGains["casualty"]
                    ?: 0
            },
            combatResult.losses,
        )
        defender.player?.updateScore(scoreGains["damage"] ?: 0, combatResult.losses)
        defender.player?.updateScore(
            if (defender.isCore) {
                scoreGains["casualtyCore"] ?: 0
            } else {
                scoreGains["casualty"]
                    ?: 0
            },
            combatResult.kills,
        )
    }

    /** Leader acquisition for both combatants — delegated to [CombatLeaderAcquisition]. */
    private fun generateCombatLeaders(
        attacker: GameUnit,
        defender: GameUnit,
        combatResult: CombatResults,
    ) {
        combatResult.atkLeaderGain =
            CombatLeaderAcquisition.acquire(attacker, defender, combatResult, isAttacker = true, turn = gameMap.turn)
        combatResult.defLeaderGain =
            CombatLeaderAcquisition.acquire(defender, attacker, combatResult, isAttacker = false, turn = gameMap.turn)
    }

    fun retreatUnit(
        unit: GameUnit,
        to: Cell,
    ): MovementResults {
        gameMap.currentMoveRange.add(to)
        val moveLeft = unit.moveLeft
        val hasMoved = unit.hasMoved
        val hasOverstrength = unit.hasOverstrength
        val prevCurrent = gameMap.currentUnit
        val result = gameMap.moveUnit(unit, to.row, to.col)
        unit.moveLeft = moveLeft
        unit.hasMoved = hasMoved
        unit.hasOverstrength = hasOverstrength
        prevCurrent?.let { gameMap.selectUnit(it) }
        return result
    }

    fun captureHex(
        hex: Hex,
        unit: GameUnit,
    ): dynamic {
        val result = json(Pair("isWin", false), Pair("isCapture", false), Pair("prestigeGain", 0))
        val player = unit.player ?: return result
        applyHexCapture(hex, unit, player, result)
        return result
    }

    // TODO(detekt): CyclomaticComplexMethod (16) — victory-side/owner/flag/prestige/liberator
    // branches for a hex capture; deliberately deferred rather than rushed (combat code, locked by
    // CombatTest — a hasty split risks a subtle behavior change here of all places).
    @Suppress("CyclomaticComplexMethod")
    private fun applyHexCapture(
        hex: Hex,
        unit: GameUnit,
        player: Player,
        result: dynamic,
    ) {
        // OG restricts hex capture to ground combat units; PM has no class check at all. Reversed
        // in OG's favour 2026-07-26 (DEFERRED.md §5.4). `unitData(true)` reads the REAL unit, not
        // its transport, so infantry riding a truck still takes the hex it drives into.
        if (!UnitCapabilities.canCaptureHex(unit.unitData(true))) return
        val side = player.side
        val notCapturable = hex.owner == -1 && hex.flag == -1
        val oldOwnerSide = if (hex.owner != -1) gameMap.getPlayer(hex.owner).side else -1
        if (notCapturable || oldOwnerSide == side) return

        gameMap.undoState.oldOwner = hex.owner
        hex.owner = player.id
        // Ownership decides the supply-hex half of the deploy zone, so the cached zones are stale
        // the moment a hex changes hands — capturing a port has to open deployment on it this turn.
        gameMap.invalidateDeployZones()
        val multiplier = if (Leaders.unitHasLeader(unit, LeaderType.LIBERATOR)) 2 else 1
        var prestigeGain = 0
        var scoreGain = 0
        var loggedObjective = false

        if (hex.flag != -1) {
            gameMap.undoState.oldFlag = hex.flag
            hex.flag = player.country
            if (hex.victorySide == -1) {
                prestigeGain += (prestigeGains["flagCapture"] ?: 0) * multiplier
                scoreGain += (scoreGains["flagCapture"] ?: 0) * multiplier
                result["isCapture"] = true
            }
        }

        if (hex.victorySide != -1) {
            gameMap.undoState.oldVictorySide = hex.victorySide
            hex.victorySide = 1 - side
            val isWin = gameMap.updateVictorySides(side, hex.getPos())
            result["isWin"] = isWin
            prestigeGain += (prestigeGains["objectiveCapture"] ?: 0) * multiplier
            scoreGain += (scoreGains["objectiveCapture"] ?: 0) * multiplier
            result["isCapture"] = true
            loggedObjective = true
        }

        prestigeGain = player.awardPrestige(prestigeGain)
        gameMap.undoState.prestigeGain = prestigeGain
        gameMap.undoState.scoreGain = scoreGain
        player.updateScore(scoreGain)
        result["prestigeGain"] = prestigeGain
        // Logged only once the total is final, so the Turn Report reports the prestige actually
        // awarded (Liberator doubles it; a flagged victory hex contributes twice) rather than the
        // bare objectiveCapture constant it used to print.
        if (loggedObjective) CombatLog.addObjectiveCapture(hex.getPos(), side, prestigeGain)
        if (result["isCapture"] as? Boolean == true) {
            HeroCampaign.recordFormationEvent(
                unit = unit,
                eventId = if (loggedObjective) "objective_captured" else "flag_captured",
                turn = gameMap.turn,
                location = hex.name.takeIf { it.isNotBlank() },
            )
        }
    }
}
