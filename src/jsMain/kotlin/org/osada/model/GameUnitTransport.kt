package org.osada.model

import org.osada.UnitClass

/** Transport/mount/embark actions for [GameUnit], split out to keep its function count in bounds. */
fun GameUnit.setTransport(eqid: Int) {
    transport = Transport(eqid)
}

fun GameUnit.mount() {
    isMounted = true
}

fun GameUnit.unmount() {
    isMounted = false
}

fun GameUnit.embark(carrierClass: UnitClass): Boolean {
    val eqid =
        Equipment.getCountryEquipmentByClass(carrierClass, (player?.country ?: 0) + 1).firstOrNull()
            ?: return false
    carrier = eqid
    return true
}

fun GameUnit.toggleEmbark() {
    carrier = -carrier
}
