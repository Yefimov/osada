package org.osada

import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.Transport
import org.osada.model.applySerializedScenarioProperties

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
        // A container's passengers, restored as full units -- they are off the map, so nothing else
        // would bring them back. `containedIn` is the derived back-reference and is rebuilt HERE
        // rather than stored: the hangar list is the one stored form, and a second copy in the save
        // could disagree with it (`rules/CarrierHangars`, `ground_carrier` bit 2 reads it).
        (data.hangar as? Array<dynamic>)?.forEach {
            val passenger = deserializeUnit(it)
            passenger.containedIn = unit
            unit.hangar.add(passenger)
        }
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
        unit.sabotaged = (data.sabotaged as? Boolean) == true
        unit.entrenchment = data.entrenchment as? Int ?: 0
        unit.entrenchTicks = data.entrenchTicks as? Int ?: 0
        unit.experience = data.experience as? Int ?: 0
        unit.hits = data.hits as? Int ?: 0
        // Absent in every pre-2026-08-18 save, which is exactly the state a formation with no
        // Devastating Fire / Fire Discipline / Shock Tactics history has.
        unit.shotsThisTurn = data.shotsThisTurn as? Int ?: 0
        unit.halfShotPending = data.halfShotPending as? Boolean ?: false
        unit.lastingHits = data.lastingHits as? Int ?: 0
        unit.leader = data.leader as? Int ?: -1
        unit.nodossier = data.nodossier as? Boolean ?: false
        unit.isTemporaryBorrowed = data.temporaryBorrowed as? Boolean ?: false
        unit.applySerializedScenarioProperties(data)
        unit.stalinRegimeBoosted = data.stalinRegimeBoosted as? Boolean ?: false
        unit.customName = data.customName as? String // optional key; absent in pre-rename saves
        unit.formationId = data.formationId as? String // optional key; absent in pre-hero saves
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
        player.type = PlayerType.entries.getOrNull(data.type as? Int ?: 0) ?: PlayerType.HUMAN_LOCAL
        applyPlayerTransportPools(player, data)
        player.supportCountries = parseIntArray(data.supportCountries)
        player.prestigePerTurn = parseIntArray(data.prestigePerTurn)
        player.dossier = data.dossier
        return player
    }

    /** The three non-organic transport pools, each as a live count plus the SIZE that caps it.
     *
     *  A save written before the ceilings existed carries no `*Max`, and falls back to the count it
     *  did store: an old save then keeps exactly the pool it had, instead of losing it to a zero
     *  ceiling that would refuse every release (`model/TransportPools`). */
    private fun applyPlayerTransportPools(
        player: Player,
        data: dynamic,
    ) {
        player.airTransports = data.airTransports as? Int ?: 0
        player.navalTransports = data.navalTransports as? Int ?: 0
        player.railTransports = data.railTransports as? Int ?: 0
        player.airTransportsMax = data.airTransportsMax as? Int ?: player.airTransports
        player.navalTransportsMax = data.navalTransportsMax as? Int ?: player.navalTransports
        player.railTransportsMax = data.railTransportsMax as? Int ?: player.railTransports
        player.defaultExperience = data.defaultExperience as? Int ?: 0
        player.defaultStrength = data.defaultStrength as? Int ?: 0
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
