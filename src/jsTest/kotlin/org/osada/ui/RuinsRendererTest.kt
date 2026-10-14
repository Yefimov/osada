package org.osada.ui

import org.osada.RoadType
import org.osada.TerrainType
import org.osada.model.Hex
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which hexes show Open General's rubble terrain over the painted map (`ui/RuinsRenderer`). */
class RuinsRendererTest {
    private fun hex(block: Hex.() -> Unit = {}) = Hex(3, 4).apply(block)

    @Test
    fun anIntactHexHasNoWreckage() {
        assertFalse(RuinsRenderer.hasWreckage(hex()))
    }

    @Test
    fun aDestroyedFacilityShowsRubble() {
        listOf(TerrainType.CITY, TerrainType.AIRFIELD, TerrainType.PORT, TerrainType.FORTIFICATION).forEach { built ->
            val wrecked =
                hex {
                    razedTerrain = built.value
                    rubble = true
                }
            assertTrue(RuinsRenderer.hasWreckage(wrecked), built.name)
        }
        assertTrue(
            RuinsRenderer.hasWreckage(hex { razedTerrain = TerrainType.AIRFIELD.value }),
            "a sapper's raze records no rubble, and the airfield is gone all the same",
        )
    }

    @Test
    fun otherWreckageShowsRubbleToo() {
        assertTrue(RuinsRenderer.hasWreckage(hex { razedTerrain = TerrainType.FOREST.value }))
        assertTrue(RuinsRenderer.hasWreckage(hex { rubble = true }))
        assertTrue(RuinsRenderer.hasWreckage(hex { blownRail = 1 }))
    }

    @Test
    fun aBlownBridgeKeepsItsOwnSprite() {
        val bridge =
            hex {
                terrain = TerrainType.RIVER.value
                blownRoad = RoadType.NORTH.value or RoadType.SOUTH.value
                rubble = true
            }
        assertFalse(RuinsRenderer.hasWreckage(bridge))
    }

    @Test
    fun aRepairedHexLosesItsWreckage() {
        val repaired =
            hex {
                razedTerrain = -1
                rubble = false
            }
        assertFalse(RuinsRenderer.hasWreckage(repaired))
    }
}
