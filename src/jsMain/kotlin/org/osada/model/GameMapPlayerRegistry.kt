package org.osada.model

import org.osada.UnitClass

/** Player registry for [GameMap], split out to keep its function count in bounds. */
fun GameMap.addPlayer(player: Player) {
    players.add(player)
    if (currentPlayer == null) currentPlayer = player
    if (player.airTransports > 0) {
        Equipment
            .getCountryEquipmentByClass(UnitClass.AIR_TRANSPORT, player.country + 1)
            .firstOrNull()
            ?.let { unitImages.add(it) }
    }
    if (player.navalTransports > 0) {
        Equipment
            .getCountryEquipmentByClass(UnitClass.NAVAL_TRANSPORT, player.country + 1)
            .firstOrNull()
            ?.let { unitImages.add(it) }
    }
}

fun GameMap.getPlayers(): Array<Player> = players.toTypedArray()

fun GameMap.getPlayer(id: Int): Player = if (id in players.indices) players[id] else players[0]

fun GameMap.getPlayersByCountry(country: Int): Array<Player> = players.filter { it.country == country }.toTypedArray()

fun GameMap.getCountriesBySide(side: Int): Array<Int> {
    val result = mutableListOf<Int>()
    players.filter { it.side == side }.forEach { player ->
        result.add(player.country)
        player.supportCountries.forEach { sc -> if (sc > 0) result.add(sc - 1) }
    }
    return result.distinct().toTypedArray()
}
