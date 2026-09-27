package org.osada.model

import org.osada.UnitClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameMapTransportImageTest {
    @Test
    fun preloadsEveryCarrierThatEmbarkMayChoose() {
        val country = 298
        val indexKey = country + 1
        val oldIndex = Equipment.equipmentIndexes[indexKey]
        val oldFilter = Equipment.availabilityFilterEnabled
        val naval = arrayOf(888001, 888002)
        val air = arrayOf(888003, 888004)
        val cargoEqid = 888005
        try {
            Equipment.availabilityFilterEnabled = false
            val classes = js("{}")
            classes[UnitClass.NAVAL_TRANSPORT.value.toString()] = naval
            classes[UnitClass.AIR_TRANSPORT.value.toString()] = air
            val index = js("{}")
            index["unitclass"] = classes
            Equipment.equipmentIndexes[indexKey] = index
            (naval + air).forEach { eqid ->
                Equipment.putEquipment(eqid, EquipmentData().apply { icon = "sprite-$eqid.png" })
            }
            Equipment.getEquipment(naval[0])!!.navalWeight = 1
            Equipment.getEquipment(naval[1])!!.navalWeight = 2
            Equipment.putEquipment(cargoEqid, EquipmentData().apply { navalWeight = 2 })

            val map = GameMap()
            val player =
                Player().apply {
                    this.country = country
                    navalTransports = 1
                    airTransportsMax = 1 // A currently empty pool can be replenished later.
                }
            map.addPlayer(player)
            val cargo = GameUnit(cargoEqid).apply { this.player = player }
            assertTrue(cargo.embark(UnitClass.NAVAL_TRANSPORT))
            assertEquals(naval[1], cargo.carrier, "weight selection bypasses the first transport")

            val images = map.getUnitImagesList()
            (naval + air).forEach { eqid ->
                assertTrue(eqid in map.unitImages)
                assertEquals("sprite-$eqid.png", images[eqid] as String)
            }
        } finally {
            Equipment.availabilityFilterEnabled = oldFilter
            if (oldIndex ==
                null
            ) {
                Equipment.equipmentIndexes.remove(indexKey)
            } else {
                Equipment.equipmentIndexes[indexKey] = oldIndex
            }
            (naval + air).forEach { Equipment.equipmentMap.remove(it) }
            Equipment.equipmentMap.remove(cargoEqid)
        }
    }
}
