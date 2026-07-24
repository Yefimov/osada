package org.osada.model

@JsExport
@JsName("Transport")
class Transport(
    eqid: Int,
) {
    var eqid: Int = if (Equipment.hasEquipment(eqid)) eqid else Equipment.firstEqid() ?: 0
    var ammo: Int = Equipment.equipment[this.eqid]?.ammo ?: 0
    var fuel: Int = Equipment.equipment[this.eqid]?.fuel ?: 0
    var icon: String = Equipment.equipment[this.eqid]?.icon ?: ""

    fun copy(other: Transport) {
        eqid = other.eqid
        ammo = other.ammo
        fuel = other.fuel
        icon = other.icon
    }

    fun unitData(): EquipmentData = Equipment.equipment[eqid] ?: EquipmentData()
}
