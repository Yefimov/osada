package org.osada

/**
 * Every equipment id a SAVE references, collected before its equipment is fetched.
 *
 * The scenario-XML twin of this is [org.osada.scenario.ScenarioEquipmentScan]; the reason both
 * exist is [org.osada.model.EquipmentCountryIndex]. A restore cannot derive the country files it
 * needs from the saved player list either -- and it has strictly more places to look than a
 * scenario file does, because a battle in progress carries reinforcement waves still to arrive,
 * fired and unfired events, both core rosters and whatever is riding inside a container.
 *
 * Everything here reads an untrusted JSON blob, so every field is probed rather than assumed: a
 * key that is absent, null or not a number simply contributes nothing, which leaves that save on
 * the player-list behaviour it already had.
 */
internal object SavedEquipmentScan {
    fun collect(
        scenarioData: dynamic,
        playersData: dynamic,
        campaignData: dynamic?,
    ): Set<Int> {
        val ids = mutableSetOf<Int>()
        if (isPresent(scenarioData)) {
            collectFromMap(scenarioData.map, ids)
            collectFromReinforcements(scenarioData.reinforcements, ids)
            collectFromEvents(scenarioData.events, ids)
        }
        // Every saved player's own roster, plus the campaign block's copy of the campaign
        // player's. They are two serializations of the same units and either may be the one a
        // given save carries, so both are read.
        for (i in 0 until lengthOf(playersData)) {
            val player = playersData[i]
            if (isPresent(player)) collectFromCoreUnits(player.coreUnits, ids)
        }
        if (isPresent(campaignData)) {
            collectFromCoreUnits(campaignData.coreUnits, ids)
        }
        return ids
    }

    /** Placed formations, the aircraft parked on top of them, and OG's trigger-hex gift ids. */
    private fun collectFromMap(
        mapData: dynamic,
        into: MutableSet<Int>,
    ) {
        val hexes = resolveHexRows(mapData)
        for (r in 0 until lengthOf(hexes)) {
            val row = hexes[r]
            for (c in 0 until lengthOf(row)) {
                val hex = row[c]
                if (!isPresent(hex)) continue
                addUnit(hex.unit, into)
                addUnit(hex.airunit, into)
                addId(hex.triggerEquip, into)
            }
        }
    }

    /**
     * Both stored shapes: the modern `[{turn, units:[{row,col,unit}]}]` array and the legacy
     * `{turn: [{row,col,unit}]}` map that `GameStateRestore` still reads.
     *
     * A wave that has not landed yet is exactly the case the player-list guess cannot see at all --
     * the unit is nowhere on the map to be flagged.
     */
    private fun collectFromReinforcements(
        data: dynamic,
        into: MutableSet<Int>,
    ) {
        if (!isPresent(data)) return
        if (js("Array.isArray(data)") as Boolean) {
            for (i in 0 until lengthOf(data)) {
                val wave = data[i]
                if (isPresent(wave)) addReinforcementEntries(wave.units, into)
            }
        } else {
            (js("Object.keys(data)") as Array<String>).forEach { key ->
                addReinforcementEntries(data[key], into)
            }
        }
    }

    private fun addReinforcementEntries(
        entries: dynamic,
        into: MutableSet<Int>,
    ) {
        for (i in 0 until lengthOf(entries)) {
            val entry = entries[i]
            if (isPresent(entry)) addUnit(entry.unit, into)
        }
    }

    /** Authored `<events><spawn>` formations, which a save carries whether or not they have fired. */
    private fun collectFromEvents(
        data: dynamic,
        into: MutableSet<Int>,
    ) {
        for (i in 0 until lengthOf(data)) {
            val event = data[i]
            if (!isPresent(event)) continue
            val spawns = event.spawns
            for (j in 0 until lengthOf(spawns)) {
                val spawn = spawns[j]
                if (isPresent(spawn)) addUnit(spawn.unit, into)
            }
        }
    }

    /**
     * One saved core roster.
     *
     * Core units are restored AFTER equipment loading and an undeployed one sits on no hex, so
     * neither the map scan nor a unit `flag` anywhere can reach them -- this is the only thing that
     * gets a campaign's reserve tray its records.
     */
    private fun collectFromCoreUnits(
        coreUnits: dynamic,
        into: MutableSet<Int>,
    ) {
        for (i in 0 until lengthOf(coreUnits)) {
            addUnit(coreUnits[i], into)
        }
    }

    /** A serialized unit's own record, its organic transport, its container, and its passengers. */
    private fun addUnit(
        unit: dynamic,
        into: MutableSet<Int>,
    ) {
        if (!isPresent(unit)) return
        addId(unit.eqid, into)
        addId(unit.carrier, into)
        val transport = unit.transport
        if (isPresent(transport)) addId(transport.eqid, into)
        val hangar = unit.hangar
        for (i in 0 until lengthOf(hangar)) addUnit(hangar[i], into)
    }

    private fun addId(
        raw: dynamic,
        into: MutableSet<Int>,
    ) {
        (raw as? Int)?.let { if (it > 0) into.add(it) }
    }

    private fun isPresent(raw: dynamic): Boolean = raw != null && raw != undefined

    private fun lengthOf(raw: dynamic): Int = if (isPresent(raw)) raw.length as? Int ?: 0 else 0

    /** Saves written before the rename store the grid under `map`; current ones under `hexes`. */
    private fun resolveHexRows(mapData: dynamic): dynamic {
        if (!isPresent(mapData)) return null
        return if (js("typeof mapData.hexes !== 'undefined'") as Boolean) mapData.hexes else mapData.map
    }
}
