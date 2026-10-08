package org.osada

import org.osada.model.awardPrestige
import org.osada.model.getPlayers
import org.osada.rules.RailTransport

/**
 * OG's **"skip first player turn"** (`Scenario.skipFirstTurn`, 17 deployed scenarios): the second
 * player opens the battle on turn 1 and the first player's first turn is turn 2. The skipped turn
 * is consumed, not replaced -- no extra preparation round and no shift of the victory schedule
 * (`tools/og-import/OG_REGISTER.md` §5 Q7.1).
 *
 * Runs once, on a NEW battle only, after the battle's players, prestige and core are in place so an
 * AI opening the battle plans with its real budget and the real enemy army. A restore never comes
 * here: the save already names the player whose turn it is.
 *
 * Deliberately NOT an ordinary end of turn for the first player. Nothing of theirs ends: no
 * engineering tick, no unit reset, and normally no turn-1 prestige income
 * ([org.osada.model.Player.endTurn] pays it at the end of a turn the player actually played).
 * **Owner's rule (2026-10-08):** a first player who starts the battle with NO prestige would then
 * reach turn 2 with nothing to spend, which is unfair, so that player alone is paid the turn-1
 * income now. A player who starts with prestige gets nothing extra. What the hand-over owes the
 * INCOMING player is the same as at any other turn ([org.osada.model.GameMap.beginPlayerTurn]).
 *
 * Reinforcements: the load already placed the first player's turn-1 wave (it was their turn then),
 * and with `reinfwhenactive` off it placed every side's. Only the opening player's own wave can
 * still be pending, and it lands here as it would at any turn start.
 */
internal fun Game.skipOpeningTurnIfAuthored() {
    val scenario = scenario ?: return
    val map = scenario.map
    val players = map.getPlayers()
    val first = players.firstOrNull()
    val opening =
        scenario.skipFirstTurn == true &&
            map.turn == 1 &&
            first != null &&
            players.size > 1 &&
            map.currentPlayer?.id == first.id
    if (!opening) return
    if (first!!.prestige == 0) first.awardPrestige(first.prestigePerTurn.getOrElse(1) { 0 })
    val next = players[1]
    map.currentPlayer = next
    RailTransport.recountPool(map, next)
    if (scenario.reinforcementsWhenActive != false) deployReinforcements(map.turn, next.id)
    map.beginPlayerTurn()
    setCurrentSide()
}
