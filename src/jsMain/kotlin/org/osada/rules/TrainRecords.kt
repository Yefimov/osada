package org.osada.rules

import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.applyAvailabilityFilter
import org.osada.model.getCountryEquipmentByClass

/**
 * Which equipment records are OG's rail TRANSPORTS, and which one a formation boards
 * ([RailTransport]). Split out of that object for its function budget: this half is about the data,
 * that half about the move.
 */
object TrainRecords {
    /**
     * Whether [data] is one of OG's rail TRANSPORTS: a Ground Transport that moves by rail, can
     * move at all, and carries no weapon. The last test is what keeps armoured trains — Ground
     * Transport on RAIL too, in some efiles — from being picked as a troop train.
     */
    fun isTrainRecord(data: EquipmentData?): Boolean =
        data != null &&
            data.uclass == UnitClass.GROUND_TRANSPORT.value &&
            data.movmethod == MovMethod.RAIL.value &&
            data.movpoints > 0 &&
            data.hardatk == 0 &&
            data.softatk == 0

    /** Whether [carrier] (a [GameUnit.carrier] value) is a train rather than a ship or an aircraft. */
    fun isTrainCarrier(carrier: Int): Boolean = carrier > 0 && isTrainRecord(Equipment.getEquipment(carrier))

    /**
     * The [GameUnit.carrier] a formation keeps once its battle is over: none, if it was on a train.
     * The train belongs to that scenario's pool; air and naval carriers are left as they were.
     */
    fun carrierAfterBattle(carrier: Int): Int = if (isTrainCarrier(carrier)) 0 else carrier

    /**
     * The train record [unit] would board: its own country's first, then the country-0 file's (which
     * `Equipment.addPlayersEquipment` always loads, and where LXF keeps its trains for every
     * nation), then any loaded train the efile allows. Null when the scenario has none at all.
     */
    fun trainFor(unit: GameUnit): Int? {
        val fromRoster = { file: Int ->
            Equipment
                .getCountryEquipmentByClass(UnitClass.GROUND_TRANSPORT, file)
                .firstOrNull { isTrainRecord(Equipment.getEquipment(it)) }
        }
        val anyLoaded = {
            Equipment
                .applyAvailabilityFilter(Equipment.equipmentMap.keys.sorted())
                .firstOrNull { isTrainRecord(Equipment.getEquipment(it)) }
        }
        return fromRoster((unit.player?.country ?: 0) + 1) ?: fromRoster(0) ?: anyLoaded()
    }
}
