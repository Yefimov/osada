package org.osada.ui

import kotlinx.browser.document
import org.osada.UnitClass
import org.osada.model.EquipmentData
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EquipmentMarkingsTest {
    @Test
    fun reconAndTankMarksExplainTheirRealRules() {
        val parent = document.createElement("div") as HTMLElement
        EquipmentMarkings.render(parent, EquipmentData().apply { uclass = UnitClass.RECON.value })
        assertEquals("RCN", parent.firstElementChild?.textContent)
        assertTrue(parent.firstElementChild?.getAttribute("title")?.contains("move again") == true)

        EquipmentMarkings.render(parent, EquipmentData().apply { uclass = UnitClass.TANK.value })
        assertEquals("OVR", parent.firstElementChild?.textContent)
        assertTrue(parent.firstElementChild?.getAttribute("title")?.contains("restores 1 movement point") == true)
    }

    /**
     * A logistics-sounding name is not a capability. The old LOG mark inferred one from the name
     * and then spent its tooltip explaining the port's own import status to the player; both the
     * inference and the explanation are gone. What is actually known about OG Depots lives in
     * DEFERRED.md §2.10, not in the UI.
     */
    @Test
    fun aLogisticsSoundingNameEarnsNoMark() {
        val parent = document.createElement("div") as HTMLElement
        EquipmentMarkings.render(parent, EquipmentData().apply { name = "Supply Depot" })

        assertEquals(null, parent.firstElementChild)
    }
}
