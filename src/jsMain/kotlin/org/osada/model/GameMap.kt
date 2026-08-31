package org.osada.model

import org.osada.CombatLog
import org.osada.GameHolder
import org.osada.PlayerType
import org.osada.addAttritionLoss
import org.osada.addResupply
import org.osada.rules.AirOperations
import org.osada.rules.CarrierHangars
import org.osada.rules.GameRules
import org.osada.rules.Minefields
import org.osada.rules.SpottingModel
import org.osada.rules.SupplyContextRules
import org.osada.rules.getResupplyValue

/**
 * Hex-grid model and unit/player registry. Owns the grid state, selection ranges, and turn
 * sequencing, and delegates specialized behaviour to focused collaborators:
 * - [CombatApplication] — damage, capture, retreat
 * - [MoveExecutor] — path movement and undo
 * - [UnitMountOperations], [UnitDeployOperations] and [CoreUnitListOperations] —
 *   mount/embark/upgrade/deploy/supply lifecycle
 *
 * Split (Single Responsibility) into cohesive sibling files to stay within the project's
 * function-count limits: grid allocation/hex access ([GameMapGrid]), the unit/player registries
 * ([GameMapUnitRegistry]/[GameMapPlayerRegistry]), selection/range management
 * ([GameMapSelection]), and the pure one-line delegations to the collaborators above
 * ([GameMapCombatMoveDelegation]/[GameMapUnitOpsDelegation]/[GameMapDeployDelegation]). This
 * class keeps only turn sequencing and bulk copy/cleanup as members.
 */
@JsExport
@JsName("Map")
class GameMap {
    var rows: Int = 0
    var cols: Int = 0
    var isLastRowPartial: Boolean = false
    var isLastColPartial: Boolean = false
    var name: String = ""
    var terrainImage: String = ""
    var map: Array<Array<Hex>>? = null
    var victoryTurns: MutableList<Int> = mutableListOf()
    var turn: Int = 1
    var maxTurns: Int = 1
    var currentUnit: GameUnit? = null
    var sidesVictoryHexes: MutableList<MutableList<Cell>> = mutableListOf(mutableListOf(), mutableListOf())
    var currentPlayer: Player? = null

    internal val units: MutableList<GameUnit> = mutableListOf()
    internal val players: MutableList<Player> = mutableListOf()
    internal var nextUnitId: Int = 0
    internal val unitImages: MutableSet<Int> = mutableSetOf()
    internal val currentMoveRange: MutableList<Cell> = mutableListOf()
    internal val currentAttackRange: MutableList<Cell> = mutableListOf()

    /** Hexes the Barrage targeting mode is currently offering (OG 9.2, `model/BarrageOperations`).
     *  Empty whenever the mode is closed, which is most of the time. */
    internal val currentBarrageTargets: MutableList<Cell> = mutableListOf()
    internal val currentRailTargets: MutableList<Cell> = mutableListOf()
    internal val undoState = UndoState()

    /**
     * Counterbattery fire drawn by the combat that resolved most recently (`rules/CounterBatteryFire`).
     *
     * A transient hand-off, not state: [CombatApplication] fills it during `attackUnit` and the
     * animation layer drains it immediately afterwards to raise the same banner and HUD line an AA
     * interception gets. It lives here rather than on [CombatResults] because that class is
     * `@JsExport`ed and a list of a non-exported type cannot cross that boundary; and it is
     * cleared at the START of every attack, so a combat that draws no counterbattery can never
     * report the previous one's.
     *
     * Never serialized. The events describe something that has already been applied to the units
     * themselves, so a save carries the outcome and has nothing to say about the announcement.
     */
    internal var lastCounterBattery: List<InterceptionEvent> = emptyList()

    internal val combatApplication: CombatApplication by lazy { CombatApplication(this) }
    internal val moveExecutor: MoveExecutor by lazy { MoveExecutor(this) }
    internal val unitMountOperations: UnitMountOperations by lazy { UnitMountOperations(this) }
    internal val unitDeployOperations: UnitDeployOperations by lazy { UnitDeployOperations(this) }
    internal val coreUnitListOperations: CoreUnitListOperations by lazy { CoreUnitListOperations(this) }

    internal var hasRailDataCache: Boolean? = null
    internal var hasWaterAccessCache: Boolean? = null
    internal var hasOpenWaterAccessCache: Boolean? = null

    /** Per-side deploy zones, cached because the renderer asks per hex per frame. Unlike the three
     *  caches above this one is NOT load-time-constant: capturing a port opens a new zone
     *  mid-scenario, so [invalidateDeployZones] drops it on every ownership change. */
    internal var deployZoneCache: MutableMap<Int, Set<Int>> = mutableMapOf()

    internal fun invalidateDeployZones() = deployZoneCache.clear()

    fun endTurn() {
        delMoveSel()
        delAttackSel()
        delCurrentUnit()
        undoState.clear()
        // Both of these belong to the player whose turn is ENDING, so they run before the hand-over.
        // OG's turn-scoped spotting memory is dropped on the way out rather than on the way in, so
        // it cannot survive the opponent's turn and hand the player free vision of hexes the enemy
        // moved through (`rules/SpottingModel.forgetTurnMemory`). The aircraft sweep is OG 6.23's
        // crash rule; both are no-ops unless their key is on.
        currentPlayer?.side?.let { SpottingModel.forgetTurnMemory(this, it) }
        currentPlayer?.side?.let { crashStrandedAircraft(it) }
        // OG 9.3's multi-turn construction advances for the PLAYER whose turn is ending -- their
        // own jobs only, and the ones finishing now take their flag (`Hex.constructionPlayer`).
        //
        // A job STARTED this turn does tick at this same turn end, and the earlier comment here
        // claimed the opposite (corrected on review, 2026-08-25). That is the intended reading of
        // the help text rather than an accident: "takes 2 of your turns" means the formation spends
        // the turn it starts on and one more, so a 2-turn bridge begun on turn 5 is standing when
        // turn 7 opens. Suppressing the first tick would make every job take one turn longer than
        // its own tooltip promises.
        //
        // A no-op unless `build_and_repair` is on.
        currentPlayer?.side?.let { advanceEngineering(it) }
        currentPlayer?.endTurn(turn)
        val currentIndex = players.indexOf(currentPlayer)
        currentPlayer = if (currentIndex + 1 < players.size) players[currentIndex + 1] else players[0]
        // The trains the incoming player used last turn are idle again. OSADA's railway move is
        // atomic, so a slot has no journey to be held for and the turn is the shortest span that
        // still makes the pool mean *"how many trains can be used at any time"*
        // (`model/TransportPools`). Air and naval are NOT refreshed here: their cargo is still in
        // the air or at sea, and they come back one at a time as it lands.
        currentPlayer?.refreshRailPool()
        if (currentPlayer?.id == 0) beginNewRound()
        // Minefield detection is refreshed for the side about to play, from where its units now
        // stand. Doing it here rather than during movement means the player sees every field their
        // sappers are next to BEFORE planning a route, which is the half of the mechanic that keeps
        // an undetected field an ambush rather than a trap with no counterplay
        // (`rules/Minefields.revealAdjacent`). A no-op unless `minefields` is on.
        currentPlayer?.side?.let { Minefields.revealAdjacent(this, it) }
        // Installation vision is rebuilt wholesale rather than reference-counted, so a city taken
        // during the turn that just ended sees for its new owner immediately and its old owner stops
        // seeing at the same instant. A no-op unless `installation_spotting` is on.
        SpottingModel.recomputeInstallations(this)
        if (currentPlayer?.type == PlayerType.AI_LOCAL || currentPlayer?.type == PlayerType.AI_SCRIPTED) {
            currentPlayer?.handler?.buildActions()
        }
    }

    /**
     * The once-a-ROUND pass: the turn counter, automatic resupply of every idle formation, and each
     * unit's own end-of-turn reset.
     *
     * Extracted from [endTurn] rather than inlined, and the boundary is a real one: everything in
     * [endTurn] happens on every hand-over, while everything here happens only when the turn wraps
     * back to player 0. That distinction is a byte-faithful port of `openpanzer.js:3565-3587` and is
     * the reason suppression and resupply are round-scoped rather than turn-scoped
     * (`docs/og-fidelity-plan.md` §0.1.1).
     */
    private fun beginNewRound() {
        turn++
        // A container is a working depot: a formation inside one refuels, rearms and gets its
        // per-turn flags back with the round (`rules/CarrierHangars`). The flag reset matters as
        // much as the supply now that `ground_carrier` bit 2 lets a passenger fire in support --
        // nothing else in this sweep ever touches a unit that is not on the map.
        units.forEach { CarrierHangars.endRoundForContained(this, it, GameHolder.instance?.spotSide ?: 0) }
        units.forEach { unit ->
            if (unit.isMounted) unmountUnitHandler(unit)
            val supply = GameRules.getResupplyValue(this, unit, true)
            val needsAmmoOrFuel = supply.ammo > 0 || supply.fuel > 0
            val needsTransportSupply = supply.transportAmmo > 0 || supply.transportFuel > 0
            if (needsAmmoOrFuel || needsTransportSupply) {
                unit.resupply(supply)
                val context = SupplyContextRules.getSupplyContext(this, unit)
                CombatLog.addResupply(unit, SupplyContextRules.logToken(context), context.adjacentEnemies)
            }
            unit.unitEndTurn(GameHolder.instance?.spotSide ?: 0)
        }
    }

    /**
     * OG 6.23's crash rule (`docs/og-fidelity-plan.md` B.3), behind `air_fuel`: an aircraft that
     * ends its owner's turn out of fuel and with no airfield or friendly carrier within reach is
     * destroyed rather than merely immobilised.
     *
     * Every loss gets its own Turn Report row before the sweep removes it. A formation that
     * disappears between turns with no explanation is the failure `DEFERRED.md` 1.1 forbids, and it
     * is less visible here than anywhere else in the engine, so the reporting is not optional.
     */
    private fun crashStrandedAircraft(side: Int) {
        val lost = AirOperations.strandedAircraft(this, side)
        if (lost.isEmpty()) return
        lost.forEach { unit ->
            CombatLog.addAttritionLoss(unit, AirOperations.lossPosition(unit), AirOperations.LOSS_OUT_OF_FUEL)
            unit.destroyed = true
        }
        updateUnitList()
    }

    fun copy(other: GameMap) {
        rows = other.rows
        cols = other.cols
        terrainImage = other.terrainImage
        name = other.name
        turn = other.turn
        maxTurns = other.maxTurns
        allocMap()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val otherHex = other.map?.getOrNull(r)?.getOrNull(c) ?: continue
                val hex = map!![r][c]
                hex.copy(otherHex)
                otherHex.unit?.let { unit ->
                    val copy = GameUnit(unit.eqid).apply { copy(unit) }
                    hex.setUnit(copy)
                }
                otherHex.airunit?.let { airunit ->
                    val copy = GameUnit(airunit.eqid).apply { copy(airunit) }
                    hex.setUnit(copy)
                }
                setHex(r, c)
            }
        }
        sidesVictoryHexes = mutableListOf(mutableListOf(), mutableListOf())
        other.sidesVictoryHexes[0].forEach { sidesVictoryHexes[0].add(Cell(it.row, it.col)) }
        other.sidesVictoryHexes[1].forEach { sidesVictoryHexes[1].add(Cell(it.row, it.col)) }
        other.victoryTurns.forEach { victoryTurns.add(it) }
    }

    fun cleanup() {
        map?.forEach { row -> row.forEach { hex -> hex.cleanup() } }
        map = null
    }
}
