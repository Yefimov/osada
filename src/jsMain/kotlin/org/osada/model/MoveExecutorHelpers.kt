package org.osada.model

internal fun MoveExecutor.resolveUndoContext(): MoveExecutor.UndoContext? {
    val unit = gameMap.undoState.unit
    val saved = gameMap.undoState.savedUnit
    return if (unit != null && saved != null) resolveUndoPositions(unit, saved) else null
}

internal fun MoveExecutor.resolveUndoPositions(
    unit: GameUnit,
    saved: GameUnit,
): MoveExecutor.UndoContext? {
    val from = unit.getPos()
    val savedPos = saved.getPos()
    return if (from != null && savedPos != null) resolveUndoHexes(unit, saved, from, savedPos) else null
}

internal fun MoveExecutor.resolveUndoHexes(
    unit: GameUnit,
    saved: GameUnit,
    from: Cell,
    savedPos: Cell,
): MoveExecutor.UndoContext? {
    val fromHex = gameMap.map?.getOrNull(from.row)?.getOrNull(from.col)
    val savedHex = gameMap.map?.getOrNull(savedPos.row)?.getOrNull(savedPos.col)
    return if (fromHex != null && savedHex != null) {
        MoveExecutor.UndoContext(unit, saved, fromHex, savedHex)
    } else {
        null
    }
}
