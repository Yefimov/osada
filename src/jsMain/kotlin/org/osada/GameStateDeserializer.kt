package org.osada

import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.Transport

/**
 * Pure JSON → model deserialization of the individual entities in a save file
 * ([GameUnit], [Player] and helpers). Extracted from the former `GameState` god-class.
 *
 * These build leaf objects only; wiring them into the scenario/map graph (and resolving
 * player references) is [GameStateRestore]'s job. All reads are defensive (`as? T ?: default`)
 * because the input is untyped `dynamic` from `JSON.parse`.
 */
object GameStateDeserializer {
    private const val FULL_STRENGTH = 10

    fun deserializeUnit(data: dynamic): GameUnit {
        val unit = GameUnit(data.eqid as? Int ?: 0)
        applyUnitIdentity(unit, data)
        applyUnitFlags(unit, data)
        applyUnitCombatStats(unit, data)
        applyUnitProgress(unit, data)
        applyUnitTransport(unit, data)
        return unit
    }

    private fun applyUnitIdentity(
        unit: GameUnit,
        data: dynamic,
    ) {
        unit.id = data.id as? Int ?: -1
        unit.owner = data.owner as? Int ?: -1
        unit.flag = data.flag as? Int ?: -1
        unit.isCore = data.isCore as? Boolean ?: false
        unit.isDeployed = data.isDeployed as? Boolean ?: false
    }

    private fun applyUnitFlags(
        unit: GameUnit,
        data: dynamic,
    ) {
        unit.isSurprised = data.isSurprised as? Boolean ?: false
        unit.isMounted = data.isMounted as? Boolean ?: false
        unit.hasOverstrength = data.hasOverstrength as? Boolean ?: false
        unit.hasResupplied = data.hasResupplied as? Boolean ?: false
        unit.hasFired = data.hasFired as? Boolean ?: false
        unit.hasMoved = data.hasMoved as? Boolean ?: false
    }

    private fun applyUnitCombatStats(
        unit: GameUnit,
        data: dynamic,
    ) {
        unit.strength = data.strength as? Int ?: FULL_STRENGTH
        unit.facing = data.facing as? Int ?: 2
        unit.destroyed = data.destroyed as? Boolean ?: false
        unit.carrier = data.carrier as? Int ?: 0
        unit.moveLeft = data.moveLeft as? Int ?: 0
        unit.ammo = data.ammo as? Int ?: 0
        unit.fuel = data.fuel as? Int ?: 0
    }

    private fun applyUnitProgress(
        unit: GameUnit,
        data: dynamic,
    ) {
        unit.entrenchment = data.entrenchment as? Int ?: 0
        unit.entrenchTicks = data.entrenchTicks as? Int ?: 0
        unit.experience = data.experience as? Int ?: 0
        unit.hits = data.hits as? Int ?: 0
        unit.leader = data.leader as? Int ?: -1
        unit.nodossier = data.nodossier as? Boolean ?: false
        unit.customName = data.customName as? String // optional key; absent in pre-rename saves
    }

    private fun applyUnitTransport(
        unit: GameUnit,
        data: dynamic,
    ) {
        val transportData = data.transport
        if (transportData != null) {
            val transport = Transport((transportData.eqid as? Int ?: 0))
            transport.ammo = transportData.ammo as? Int ?: transport.ammo
            transport.fuel = transportData.fuel as? Int ?: transport.fuel
            unit.transport = transport
        }
    }

    fun deserializePlayer(data: dynamic): Player {
        val player = Player()
        player.id = data.id as? Int ?: -1
        player.side = data.side as? Int ?: -1
        player.country = data.country as? Int ?: -1
        player.prestige = data.prestige as? Int ?: 0
        player.score = data.score as? Int ?: 0
        player.playedTurn = data.playedTurn as? Int ?: -1
        player.type = PlayerType.values().getOrNull(data.type as? Int ?: 0) ?: PlayerType.HUMAN_LOCAL
        player.airTransports = data.airTransports as? Int ?: 0
        player.navalTransports = data.navalTransports as? Int ?: 0
        player.supportCountries = parseIntArray(data.supportCountries)
        player.prestigePerTurn = parseIntArray(data.prestigePerTurn)
        player.dossier = data.dossier
        return player
    }

    fun parseIntArray(data: dynamic): MutableList<Int> {
        val result = mutableListOf<Int>()
        if (data == null) return result
        val length = data.length as? Int ?: 0
        for (i in 0 until length) {
            result.add(data[i] as? Int ?: 0)
        }
        return result
    }
}
