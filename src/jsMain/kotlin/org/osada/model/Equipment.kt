package org.osada.model

object Equipment {
    // XMLHttpRequest.status: 2xx is success; 0 is the local-file (file://) "no HTTP status" case.
    internal val httpSuccessRange = 200..299

    internal val equipmentMap: MutableMap<Int, EquipmentData> = mutableMapOf()
    val equipment: dynamic
        get() {
            val result = js("{}")
            equipmentMap.forEach { (k, v) -> result[k] = v }
            return result
        }
    val equipmentIndexes: MutableMap<Int, dynamic> = mutableMapOf()
    const val EQUIPMENT_PATH = "resources/equipment/"
    const val FILE_PREFIX = "equipment-country-"
    const val DEFAULT_NAME = "eqp-adlerkorps"

    // All equipment now lives in one merged DB (see tools/eqp-merge/); [name] is no longer a
    // folder selector, it's the pre-merge efile identifier used only as the availability-set
    // key (which merged eqids a given campaign/scenario may purchase -- see [availabilityMap]).
    const val UNITED_NAME = "eqp-united"
    var name: String = DEFAULT_NAME
    var equipmentToLoad: Int = 0
    val equipmentToLoadSet: MutableSet<Int> = mutableSetOf()

    // Per-campaign purchase/upgrade/prototype allowlist (tools/eqp-merge/.../availability.json).
    // Placed units (scenario-defined eqids) are never filtered -- only these list-building calls.
    var availabilityFilterEnabled: Boolean = true
    internal var availabilityMap: Map<String, Set<Int>>? = null
    internal var availabilityLoadAttempted: Boolean = false

    internal var asyncLoad: Boolean = true
    internal var loadCallback: (() -> Unit)? = null

    fun hasEquipment(eqid: Int): Boolean = equipmentMap.containsKey(eqid)

    fun getEquipment(eqid: Int): EquipmentData? = equipmentMap[eqid]

    fun firstEqid(): Int? = equipmentMap.keys.firstOrNull()

    fun putEquipment(
        eqid: Int,
        data: EquipmentData,
    ) {
        equipmentMap[eqid] = data
    }
}
