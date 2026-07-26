package org.osada.model

private val propertyAccessors: Map<String, (EquipmentData) -> Int> =
    mapOf(
        "cost" to { eq: EquipmentData -> eq.cost },
        "initiative" to { eq: EquipmentData -> eq.initiative },
        "gunrange" to { eq: EquipmentData -> eq.gunrange },
        "movpoints" to { eq: EquipmentData -> eq.movpoints },
        "spotrange" to { eq: EquipmentData -> eq.spotrange },
        "hardatk" to { eq: EquipmentData -> eq.hardatk },
        "softatk" to { eq: EquipmentData -> eq.softatk },
        "airatk" to { eq: EquipmentData -> eq.airatk },
        "navalatk" to { eq: EquipmentData -> eq.navalatk },
        "grounddef" to { eq: EquipmentData -> eq.grounddef },
        "airdef" to { eq: EquipmentData -> eq.airdef },
        "closedef" to { eq: EquipmentData -> eq.closedef },
        "rangedefmod" to { eq: EquipmentData -> eq.rangedefmod },
        "yearavailable" to { eq: EquipmentData -> eq.yearavailable },
        // "ammo" was missing here once: "Sort: Unit Ammo" silently returned 0 for all.
        "ammo" to { eq: EquipmentData -> eq.ammo },
    )

/** Equipment-list lookup/filter/sort queries, split out of [Equipment] to keep its function count in bounds. */
fun Equipment.getCountryEquipmentByClass(
    unitClass: org.osada.UnitClass,
    country: Int,
    sortProperty: String? = null,
    descending: Boolean = false,
): List<Int> {
    val list = classIndexList(country, unitClass.value.toString())
    return sortEquipmentIds(applyAvailabilityFilter(list), sortProperty, descending)
}

private fun Equipment.classIndexList(
    country: Int,
    classKey: String,
): List<Int> {
    val index = equipmentIndexes[country]
    val classIndex = if (index == null) null else index["unitclass"]
    if (index == null || classIndex == undefined || classIndex == null) return emptyList()
    return classIndex[classKey].unsafeCast<Array<Int>?>()?.toList() ?: emptyList()
}

/** Union of every purchasable class for a country — what the leftmost "All" class tab shows.
 *  UnitClass.NONE=0 is the sentinel class value that selects it. */
fun Equipment.getCountryEquipmentAll(
    country: Int,
    sortProperty: String? = null,
    descending: Boolean = false,
): List<Int> {
    val index = equipmentIndexes[country]
    val classIndex = if (index == null) null else index["unitclass"]
    if (index == null || classIndex == undefined || classIndex == null) return emptyList()
    val keys = js("Object.keys")(classIndex).unsafeCast<Array<String>>()
    val all = mutableListOf<Int>()
    keys.forEach { key ->
        classIndex[key].unsafeCast<Array<Int>?>()?.let { all.addAll(it) }
    }
    return sortEquipmentIds(applyAvailabilityFilter(all), sortProperty, descending)
}

/** Union across MULTIPLE countries (the "All countries" equipment-window option) of a single
 *  class — same building blocks as [getCountryEquipmentByClass], just per-country then merged
 *  and sorted once. */
fun Equipment.getCountriesEquipmentByClass(
    unitClass: org.osada.UnitClass,
    countries: List<Int>,
    sortProperty: String? = null,
    descending: Boolean = false,
): List<Int> {
    val classKey = unitClass.value.toString()
    val all = mutableListOf<Int>()
    countries.forEach { all.addAll(classIndexList(it, classKey)) }
    return sortEquipmentIds(applyAvailabilityFilter(all), sortProperty, descending)
}

/** Union of SEVERAL classes for one country — an equipment tab that covers more than its own class
 *  (`UIBuilder.eqClassTabGroups`, e.g. Infantry also listing Fortification). */
fun Equipment.getCountryEquipmentByClasses(
    unitClasses: List<org.osada.UnitClass>,
    country: Int,
    sortProperty: String? = null,
    descending: Boolean = false,
): List<Int> {
    val all = mutableListOf<Int>()
    unitClasses.forEach { all.addAll(classIndexList(country, it.value.toString())) }
    return sortEquipmentIds(applyAvailabilityFilter(all), sortProperty, descending)
}

/** Union of SEVERAL classes across MULTIPLE countries — [getCountryEquipmentByClasses] combined
 *  with the "All countries" option. */
fun Equipment.getCountriesEquipmentByClasses(
    unitClasses: List<org.osada.UnitClass>,
    countries: List<Int>,
    sortProperty: String? = null,
    descending: Boolean = false,
): List<Int> {
    val all = mutableListOf<Int>()
    countries.forEach { country ->
        unitClasses.forEach { all.addAll(classIndexList(country, it.value.toString())) }
    }
    return sortEquipmentIds(applyAvailabilityFilter(all), sortProperty, descending)
}

/** Union across MULTIPLE countries of every purchasable class — the "All countries" + "All
 *  classes" combination. */
fun Equipment.getCountriesEquipmentAll(
    countries: List<Int>,
    sortProperty: String? = null,
    descending: Boolean = false,
): List<Int> {
    val all = mutableListOf<Int>()
    countries.forEach { all.addAll(getCountryEquipmentAll(it)) }
    return sortEquipmentIds(all, sortProperty, descending)
}

private fun Equipment.sortEquipmentIds(
    list: List<Int>,
    sortProperty: String?,
    descending: Boolean,
): List<Int> {
    if (sortProperty == null) return list
    // Primary key = the chosen property; ties break by cost then eqid so an all-equal
    // property (e.g. every unit has firing range 1) still yields a stable, meaningful
    // order instead of arbitrary index order.
    return list.sortedWith(
        compareByDescending<Int> {
            val value = equipmentMap[it]?.let { eq -> getProperty(eq, sortProperty) } ?: 0
            if (descending) value else -value
        }.thenBy { equipmentMap[it]?.cost ?: 0 }
            .thenBy { it },
    )
}

fun Equipment.getCountryEquipmentByYearRange(
    start: Int,
    end: Int,
    country: Int,
): List<Int> {
    val ids =
        equipmentMap.entries
            .filter { (_, eq) -> eq.country == country && eq.yearavailable in start..end }
            .map { it.key }
    return applyAvailabilityFilter(ids)
}

private fun getProperty(
    eq: EquipmentData,
    property: String,
): Int = propertyAccessors[property]?.invoke(eq) ?: 0
