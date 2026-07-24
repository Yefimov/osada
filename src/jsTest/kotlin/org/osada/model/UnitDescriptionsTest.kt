package org.osada.model

import org.osada.ui.equipmentDescriptionOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UnitDescriptionsTest {
    @Test
    fun getReturnsTextForExactName() {
        val map =
            mapOf(
                "T-34" to "A Soviet medium tank",
                "Panzer IV" to "German medium tank",
            )
        UnitDescriptions.setForTest(map)

        assertEquals("A Soviet medium tank", UnitDescriptions.get("T-34"))
        assertEquals("German medium tank", UnitDescriptions.get("Panzer IV"))
    }

    @Test
    fun getTrimsWhitespaceFromLookupKey() {
        val map = mapOf("T-34" to "Soviet tank")
        UnitDescriptions.setForTest(map)

        assertEquals("Soviet tank", UnitDescriptions.get("  T-34  "))
        assertEquals("Soviet tank", UnitDescriptions.get("\tT-34\n"))
    }

    @Test
    fun getReturnsNullForUnknownName() {
        val map = mapOf("T-34" to "Soviet tank")
        UnitDescriptions.setForTest(map)

        assertNull(UnitDescriptions.get("Panzer VI"))
    }

    @Test
    fun getReturnsNullForBlankValue() {
        val map = mapOf("T-34" to "")
        UnitDescriptions.setForTest(map)

        assertNull(UnitDescriptions.get("T-34"))
    }

    @Test
    fun getReturnsNullWhenMapNotLoaded() {
        UnitDescriptions.setForTest(null)
        assertNull(UnitDescriptions.get("T-34"))
    }

    @Test
    fun equipmentDescriptionOrNullReturnsRealText() {
        val map = mapOf("T-34/43" to "Soviet medium tank with 85mm gun")
        UnitDescriptions.setForTest(map)

        val eq = EquipmentData()
        eq.name = "T-34/43"

        assertEquals("Soviet medium tank with 85mm gun", equipmentDescriptionOrNull(eq))
    }

    @Test
    fun equipmentSpecificDescriptionWinsOverSharedName() {
        UnitDescriptions.setForTest(
            names = mapOf("Cavalry" to "Generic mounted troops"),
            ids = mapOf(1918 to "Civil War horsemen armed with rifles and sabres"),
        )

        val eq = EquipmentData()
        eq.eqid = 1918
        eq.name = "Cavalry"

        assertEquals("Civil War horsemen armed with rifles and sabres", equipmentDescriptionOrNull(eq))
    }

    @Test
    fun versionTwoResourceParsesNameAndEquipmentMaps() {
        UnitDescriptions.parseForTest(
            """{"version":2,"byName":{"Cavalry":"Generic mounted troops"},"byId":{"1918":"Civil War horsemen"}}""",
        )
        val exact =
            EquipmentData().apply {
                eqid = 1918
                name = "Cavalry"
            }
        val fallback =
            EquipmentData().apply {
                eqid = 9999
                name = "Cavalry"
            }

        assertEquals("Civil War horsemen", equipmentDescriptionOrNull(exact))
        assertEquals("Generic mounted troops", equipmentDescriptionOrNull(fallback))
    }

    @Test
    fun legacyFlatResourceStillParses() {
        UnitDescriptions.parseForTest("""{"T-34":"Legacy description"}""")

        assertEquals("Legacy description", UnitDescriptions.get("T-34"))
    }

    @Test
    fun equipmentDescriptionOrNullReturnsNullWhenMissing() {
        val map = mapOf("T-34" to "Soviet tank")
        UnitDescriptions.setForTest(map)

        val eq = EquipmentData()
        eq.name = ".30 M1919"

        assertNull(equipmentDescriptionOrNull(eq))
    }

    @Test
    fun equipmentDescriptionOrNullReturnsNullWhenUnloaded() {
        UnitDescriptions.setForTest(null)

        val eq = EquipmentData()
        eq.name = "Any Unit"

        assertNull(equipmentDescriptionOrNull(eq))
    }
}
