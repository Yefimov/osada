package org.osada.model

import org.osada.rules.AirborneLanding

/**
 * OG's airborne misdrop (`rules/AirborneLanding`): rolls a paradrop and returns the setup the walk
 * should follow -- the ordered one, or a one-step path to where the unit actually comes down. Null
 * when the formation is lost; the unit is then already removed and its transport back in the pool.
 *
 * Outside [MoveExecutor] only to keep that class inside detekt's function budget.
 */
internal fun MoveExecutor.paradropLanding(
    unit: GameUnit,
    setup: MoveExecutor.MoveSetup,
    target: Cell,
    result: MovementResults,
): MoveExecutor.MoveSetup? =
    if (AirborneLanding.applies(gameMap, unit, target)) landAfterRoll(unit, setup, target, result) else setup

private fun MoveExecutor.landAfterRoll(
    unit: GameUnit,
    setup: MoveExecutor.MoveSetup,
    target: Cell,
    result: MovementResults,
): MoveExecutor.MoveSetup? {
    val outcome = AirborneLanding.resolve(gameMap, unit, target)
    result.airborneLanding = outcome
    val landing = outcome.landing
    if (landing == null) {
        loseInDrop(unit)
        return null
    }
    if (outcome.losses > 0) unit.hit(outcome.losses)
    outcome.enemyCell?.let { markSurprise(unit, it, result) }
    val onTarget = landing.row == target.row && landing.col == target.col
    return if (onTarget) {
        setup
    } else {
        MoveExecutor.MoveSetup(setup.map, setup.from, setup.fromHex, setup.side, listOf(setup.from, landing))
    }
}

/** A drop with no land within reach: the formation is gone, the aircraft that carried it is not. */
private fun MoveExecutor.loseInDrop(unit: GameUnit) {
    Equipment.getEquipment(-unit.carrier)?.let { unit.player?.returnTransportToPool(it.uclass) }
    unit.carrier = 0
    unit.destroyed = true
    gameMap.undoState.clear()
    gameMap.updateUnitList()
}
