package org.osada.ui

import org.osada.MovMethod
import org.osada.UNIT_MAX_EXPERIENCE
import org.osada.UnitClass
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.getCountryName
import org.osada.movMethodNames
import org.osada.uiSettings
import org.osada.unitClassNames
import org.osada.unitTypeNames

/**
 * [UnitInfoPanel]'s equipment-catalogue (browse-only, no live unit) stat card. Split out purely
 * to keep [UnitInfoPanel] within the project's function-count/class-size limits -- not expected
 * to be called from elsewhere. The live-unit variant lives in [UnitStatCard].
 */
internal object EquipmentStatCard {
    private const val FLAG_SPRITE_WIDTH = 21

    fun showEquipmentInfo(eq: EquipmentData?) {
        if (eq == null) return
        if (!equipmentWindowOpen() && !uiSettings.unitInfoVisibility) return
        makeVisible("unit-info")
        byId("inspectunit")?.let { toggleButton(it, true) }

        clearTag("uTransport")
        clearTag("uCarrier")
        delTag(byId("leaderInfo"))
        byId("uLeader")?.className = "uc-leader-slot"
        byId("uLeader")?.textContent = ""
        byId("uLeader")?.title = "Leader slot — empty"
        byId("uTransport")?.className = ""
        byId("uCarrier")?.className = ""
        byId("statsRow")?.let { makeVisible(it.id) }

        if (eq.gunrange == 0) eq.gunrange = 1
        fillEquipmentHeader(eq)
        fillEquipmentStatFields(eq)
        fillEquipmentStatBars(eq)
    }

    private fun fillEquipmentHeader(data: EquipmentData) {
        byId("uImage")?.style?.backgroundImage = "url(${data.icon})"
        byId("uSmallFlag")?.style?.backgroundPosition = "${-FLAG_SPRITE_WIDTH * (data.country - 1)}px 0px"
        byId("uFlag")?.style?.backgroundImage =
            "url('resources/ui/flags/${Equipment.UNITED_NAME}/flag_big_${data.country}.png')"
        byId("uFlag")?.textContent = Equipment.getCountryName(data.country - 1)
        byId("uName")?.textContent = "${data.name} ${unitClassNames[data.uclass]}"
        EquipmentMarkings.render(byId("osadaUcMarkings"), data)
        byId("ucRename")?.style?.display = "none" // catalogue entries aren't renamable
    }

    private fun fillEquipmentStatFields(data: EquipmentData) {
        var fuelStr = "-"
        if (data.fuel > 0) fuelStr = data.fuel.toString()

        byId("uTarget")?.textContent = unitTypeNames[data.target]
        byId("uMoveType")?.textContent =
            if (data.uclass <= UnitClass.AIR_DEFENCE.value &&
                data.movmethod == MovMethod.DEEP_NAVAL.value
            ) {
                "Rail Road"
            } else {
                movMethodNames[data.movmethod]
            }
        byId("uStr")?.textContent = "10/10"
        byId("uFuel")?.innerHTML = fuelStr
        byId("uAmmo")?.innerHTML = data.ammo.toString()
        byId("uGunRange")?.textContent = data.gunrange.toString()
        byId("uMovement")?.textContent = data.movpoints.toString()
        byId("uExp")?.textContent = "0"
        byId("uEnt")?.textContent = "0"
        byId("uIni")?.textContent = data.initiative.toString()
        byId("uSpot")?.textContent = data.spotrange.toString()
        byId("uAHard")?.textContent = data.hardatk.toString()
        byId("uASoft")?.textContent = data.softatk.toString()
        byId("uAAir")?.textContent = data.airatk.toString()
        byId("uANaval")?.textContent = data.navalatk.toString()
        byId("uDHard")?.textContent = data.grounddef.toString()
        byId("uDAir")?.textContent = data.airdef.toString()
        byId("uDClose")?.textContent = data.closedef.toString()
        byId("uDRange")?.textContent = data.rangedefmod.toString()
    }

    // Bars: a browsed equipment entry has no live unit state, so show full strength/ammo
    // (matches the "10/10" text above) and hide fuel unless this equipment type uses it.
    private fun fillEquipmentStatBars(data: EquipmentData) {
        byId("uStrBarFill")?.style?.width = "100%"
        byId("uStrBarFillValue")?.textContent = "10/10"
        byId("uAmmoBarFill")?.style?.width = "100%"
        byId("uAmmoBarFillValue")?.textContent = "${data.ammo}/${data.ammo}"
        byId("uFuelBarFillRow")?.style?.display = if (data.fuel > 0) "flex" else "none"
        if (data.fuel > 0) {
            byId("uFuelBarFill")?.style?.width = "100%"
            byId("uFuelBarFillValue")?.textContent = "${data.fuel}/${data.fuel}"
        }
        byId("osadaUcStars")?.textContent = "☆☆☆☆☆"
        byId("osadaUcStars")?.title = "Experience: 0/$UNIT_MAX_EXPERIENCE"
        byId("osadaUcEnt")?.textContent = ""
    }
}
