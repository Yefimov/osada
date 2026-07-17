package org.osada.model

import org.osada.model.Equipment.getCountryEquipmentByClass
import org.w3c.xhr.XMLHttpRequest
import kotlin.js.Json

object Equipment {
    private val _equipment: MutableMap<Int, EquipmentData> = mutableMapOf()
    val equipment: dynamic
        get() {
            val result = js("{}")
            _equipment.forEach { (k, v) -> result[k] = v }
            return result
        }
    val equipmentIndexes: MutableMap<Int, dynamic> = mutableMapOf()
    const val equipmentPath = "resources/equipment/"
    const val filePrefix = "equipment-country-"
    const val defaultName = "eqp-adlerkorps"

    // All equipment now lives in one merged DB (see tools/eqp-merge/); [name] is no longer a
    // folder selector, it's the pre-merge efile identifier used only as the availability-set
    // key (which merged eqids a given campaign/scenario may purchase -- see [availabilityMap]).
    const val unitedName = "eqp-united"
    var name: String = defaultName
    var equipmentToLoad: Int = 0
    val equipmentToLoadSet: MutableSet<Int> = mutableSetOf()

    // Per-campaign purchase/upgrade/prototype allowlist (tools/eqp-merge/.../availability.json).
    // Placed units (scenario-defined eqids) are never filtered -- only these list-building calls.
    var availabilityFilterEnabled: Boolean = true
    private var availabilityMap: Map<String, Set<Int>>? = null
    private var availabilityLoadAttempted: Boolean = false

    internal var asyncLoad: Boolean = true
    private var loadCallback: (() -> Unit)? = null

    fun hasEquipment(eqid: Int): Boolean = _equipment.containsKey(eqid)
    fun getEquipment(eqid: Int): EquipmentData? = _equipment[eqid]
    fun firstEqid(): Int? = _equipment.keys.firstOrNull()
    fun putEquipment(eqid: Int, data: EquipmentData) {
        _equipment[eqid] = data
    }

    val countryNames = listOf(
        "New Zealand", "Irregular Forces", "British India", "Australia", "Hungary", "Japan",
        "France", "Germany", "Bulgaria", "USA", "Canada", "Turkey",
        "Italy", "Romania", "Thailand", "Nationalist China", "Philippines", "Netherlands",
        "South Korea", "Soviet Union", "Poland", "Communist China", "United Kingdom", "Mongolia",
        "Manchukuo", "North Korea", "Spanish Republic", "Captured Equipment", "Spain", "Captured Equipment",
        "Iraq", "Afghanistan", "Iran", "Free French Forces", "Italian Social Republic", "Belgium",
        "Portugal", "Slovakia", "Finland", "Greece", "Norway", "Croatia",
        "Kingdom of Yugoslavia", "Yugoslavia", "Switzerland", "Denmark", "Ethiopia", "Czechoslovakia",
        "South Africa", "Sweden", "Brazil", "Austria", "Albania", "Mengjiang",
        "Puppet Regime China", "Nanjing Regime China", "State of Burma", "Free India", "Estonia", "Latvia",
        "Lithuania", "USSR", "Communist Forces", "Belarus", "Ukraine", "Indonesia",
        "Chetniks", "Serbian State", "Peru", "Colombia", "Bolivia", "Paraguay",
        "Ecuador", "India", "Pakistan", "Saudi Arabia", "Kingdom of Yemen", "Viet Minh",
        "State of Vietnam", "Ireland", "Kingdom of Egypt", "Mexico", "Communist Greece", "Cuba",
        "Warlord China", "Tibet", "Germany", "Argentina", "Senusiyya", "USSR",
        "Nationalist Spain", "Republican Spain", "Republic China", "Germany", "Euzko Gudarostea", "Don Republic",
        "Peoples Republic China", "Red Finland", "White Finland", "Ethiopian Empire", "White Russia", "Communists",
        "Chinese Warlords", "Red Russia", "Baltische Landeswehr", "Kuban People's Republic", "Rif Republic", "26th of July Movement",
        "Imperial State of Iran", "Kingdom of Iraq", "Kingdom of Spain", "Serbia", "Azad Hind", "Kingdom of Afghanistan",
        "Arab Countries", "Detachement von Randow", "Freikorps", "German Empire", "Syrian Republic", "Israel",
        "West Germany", "East Germany", "Jordan", "Lebanon", "Ukrainian People's Republic", "Italian Co-belligerent Army",
        "Chile", "Ottoman Empire", "Luxembourg", "International Brigades", "Division Azul", "Senussi Order",
        "Ukrainian Insurgent Army", "Axis Russia", "British Ceylon", "Nanjing National Government", "Black Army", "Algerian Nationalists",
        "Free City of Danzig", "Dominion of Newfoundland", "East Turkestan Republic", "Kaiju", "Tanganyika Territory", "Austro-Hungarian Empire",
        "Czechoslovak Legion", "United States", "Arab Rebels", "Boer States", "Rebels/Revolutionaries", "Qing China",
        "Confederate States", "British Empire", "Japanese Empire", "Russian Empire", "Indigenous", "Rif",
        "Kingdom of Morocco", "Prussia", "Austrian Empire", "French Empire", "Republic of China", "German Freikorps",
        "Union States", "Carlist Spain", "Indian Nations", "Afganistan", "Sudan", "Egypt",
        "Mexican Rebels", "Cuban Rebels", "Philippine Rebels", "Anarchist Ukraine", "Montenegro", "Bavaria",
        "Saxony", "Württemberg", "Baden", "Hannover", "Piedmont-Sardinia", "Empire of China",
        "Garibaldines", "Red China", "Abyssinia", "Armenia", "Azerbaijan", "Georgia",
        "Chinese Revolutionaries", "Red Hungary", "Red Germany", "Russian Green Armies", "Mexican Empire", "Cossack Hosts",
        "Papal States", "Central Lithuania", "Kingdom of The Two Sicilies", "Republic of San Marco", "German Revolutionaries", "Kingdom of Hungary",
        "Empire of Vietnam", "Sultanate of Zanzibar", "Allied Yugoslavia", "SS", "Japanese Navy", "CNT-FAI",
        "POUM", "Milicias Socialistas", "Brigadas Internacionales", "Tercios Requetés", "Milicias Falange", "Legion Condor",
        "Milicias Comunistas", "Sin Usar", "Sin Usar", "Sin Usar", "Corpo Truppe Volontarie", "Sin Usar",
        "Sin Usar", "Sin Usar", "Sin Usar", "Sin Usar", "Sin Usar", "Sin Usar",
        "Sin Usar", "Francia", "Gran Bretaña", "Ejército Nacional", "Ejército Popular", "Sin Usar",
        "Sin Usar", "Sin Usar", "Sin Usar", "Sin Usar", "Vichy France", "P.R. of China",
        "National Spain", "Persia", "Free France", "RSI", "Communist Poland", "Communist Yugoslavia",
        "Fengtian clique", "Mandate for Palestine", "Spanish Revolutionaries", "Nanjing China", "Communist Bulgaria", "Socialist Cuba",
        "D.R. of Vietnam", "Syrian Arab Republic", "Kampuchea", "Angola", "Rebels/Misc.", "United Nations",
        "NATO", "Republic of Vietnam", "Germany F.R.", "Germany D.R.", "Warsaw Pact", "Kuwait",
        "Communist Afghanistan", "Taliban", "PLO", "Communist Czechoslovakia", "Communist Romania", "Serbian & Montenegro",
        "Kosovo", "Slovenia", "Bosnia & Herzegovina", "Singapore", "Morocco", "Algeria",
        "Libya", "Panama", "Rhodesia", "Kazakhstan", "Malaysia", "Chechnya",
        "Viet Cong", "Zimbabwe", "SWAPO", "UNITA", "Afghan Mujahideen", "Cambodia",
        "Bangladesh", "Kurdish Factions", "Grenada", "Communist Laos", "Laos", "S.R. of Vietnam",
        "Arab Liberation Army", "Cyprus", "Northern Alliance", "Chad", "Biafra", "Nigeria",
        "Katanga", "Congo", "Uganda", "Tanzania", "Abkhasia", "Zaire",
        "Montoneros", "Rebel Libya", "South Ossetia", "Waffen SS", "China", "U.S. Army",
        "U.S. Marines", "Romans", "Celts", "Huns", "rebellious Slaves", "Cilician Pirates",
        "Germans", "Carthages", "Trojans", "Achaians", "Egyptians", "Hittites",
        "Assyrians", "Babylonians", "Spartans", "Athenian Confederacy", "Macedonians", "Persians",
        "Indian Lords", "Numidians", "Armenians", "Parthians", "Skythians", "Etruscans",
        "Chinese Empire", "Natives", "Majas", "Polynesians", "Franconians", "Roman Rebels",
        "Nubians", "Goths", "Jews", "Thrakians", "Illyrians", "Britons",
        "Picts", "celtish Opponents", "germanic Opponents", "Western Roman Empire", "Byzantine Empire", "Sarmatians",
        "Scoti", "Thebes", "Panzerzug", "Nationalist Chinese", "Afrika Korps", "Communist Chinese",
        "Republican", "Allied Forces", "Axis Forces", "Poland (unmapped basekorp roster)", "Unassigned (basekorp)", "Russian Cossack Forces (Axis-aligned)",
        "Russian Federation", "Unassigned Flag (lxf)",
    )

    fun getCountryName(country: Int): String = if (country in countryNames.indices) countryNames[country] else "Unknown"

    // eqpName is unused since the merge (one shared countryNames list for every campaign) --
    // kept as a parameter so every existing call site (which passes the scenario's own eqp,
    // now just its availability-set key) doesn't need to change.
    fun getCountryNameByEqp(country: Int, eqpName: String): String = getCountryName(country)

    fun resetEquipment() {
        _equipment.clear()
        equipmentIndexes.clear()
        equipmentToLoadSet.clear()
        equipmentToLoad = 0
    }

    fun addPlayersEquipment(players: List<Player>, onComplete: () -> Unit) {
        resetEquipment()
        loadCallback = onComplete
        equipmentToLoadSet.add(-1)
        players.forEach { player ->
            equipmentToLoadSet.add(player.country)
            player.supportCountries.forEach { sc ->
                if (sc > 0) equipmentToLoadSet.add(sc - 1)
            }
        }
        equipmentToLoad = equipmentToLoadSet.size
        equipmentToLoadSet.forEach { country ->
            addCountryEquipment(country) { checkComplete() }
        }
    }

    fun addCountryEquipment(country: Int, onComplete: (() -> Unit)? = null) {
        loadCountryEquipment(country + 1, onComplete)
    }

    private fun loadCountryEquipment(country: Int, onComplete: (() -> Unit)?) {
        val path = "$equipmentPath$unitedName/$filePrefix$country.json"
        if (asyncLoad) {
            val request = XMLHttpRequest()
            request.onload = {
                if (request.readyState == 4.toShort() &&
                    (request.status == 200.toShort() || request.status == 0.toShort())
                ) {
                    parseCountryEquipment(country, JSON.parse(request.responseText))
                    onComplete?.invoke()
                }
            }
            request.open("GET", path, true)
            request.send(null)
        } else {
            val request = XMLHttpRequest()
            request.open("GET", path, false)
            request.send(null)
            val status = request.status.toInt()
            if (status in 200..299 || status == 0) {
                val text = request.responseText
                if (!text.isNullOrBlank()) {
                    parseCountryEquipment(country, JSON.parse(text))
                }
            }
            onComplete?.invoke()
        }
    }

    fun parseCountryEquipment(country: Int, data: Json) {
        val indexes = data["indexes"]
        val units = data["units"]
        val parseHints = data["parsehints"].unsafeCast<Array<String>>()
        equipmentIndexes[country] = indexes
        val unitKeys = js("Object.keys")(units).unsafeCast<Array<String>>()
        val unitsDynamic = units.asDynamic()
        var loaded = 0
        unitKeys.forEach { key ->
            val eqid = key.toIntOrNull()
            val unitJson = unitsDynamic[key]
            if (eqid != null && unitJson != undefined) {
                _equipment[eqid] = unitJson.unsafeCast<Json>().toEquipmentData(parseHints.toList())
                loaded++
            }
        }
        console.log(
            "[osada] parseCountryEquipment country=$country unitsLoaded=$loaded totalEquipment=${_equipment.size}",
        )
    }

    private fun checkComplete() {
        equipmentToLoad--
        if (equipmentToLoad <= 0) {
            equipmentToLoad = 0
            loadCallback?.invoke()
            loadCallback = null
        }
    }

    // Loaded once ever (not per scenario): a single availability.json holds every efile's
    // allowlist. Synchronous like the non-async equipment load path -- it's a small file and
    // this only runs once per session.
    private fun loadAvailabilityIfNeeded() {
        if (availabilityLoadAttempted) return
        availabilityLoadAttempted = true
        val path = "$equipmentPath$unitedName/availability.json"
        val request = XMLHttpRequest()
        request.open("GET", path, false)
        request.send(null)
        val status = request.status.toInt()
        if (status !in 200..299 && status != 0) return
        val text = request.responseText
        if (text.isNullOrBlank()) return
        val json = JSON.parse<Json>(text)
        val keys = js("Object.keys")(json).unsafeCast<Array<String>>()
        val map = mutableMapOf<String, Set<Int>>()
        keys.forEach { key ->
            val ids = json.asDynamic()[key].unsafeCast<Array<Int>>()
            map[key] = ids.toSet()
        }
        availabilityMap = map
    }

    /** The current campaign's purchase/upgrade/prototype allowlist, or null if filtering is
     *  off/unavailable (fail-open: an efile with no availability.json entry, e.g. a future
     *  standalone scenario, sees everything rather than an empty buy list). */
    private fun currentAllowlist(): Set<Int>? {
        if (!availabilityFilterEnabled) return null
        loadAvailabilityIfNeeded()
        val allowlist = availabilityMap?.get(name)
        if (allowlist == null) {
            console.warn("[osada] no availability entry for '$name' -- purchase list is unfiltered")
        }
        return allowlist
    }

    private fun applyAvailabilityFilter(ids: List<Int>): List<Int> {
        val allowlist = currentAllowlist() ?: return ids
        return ids.filter { it in allowlist }
    }

    fun getCountryEquipmentByClass(
        unitClass: org.osada.UnitClass,
        country: Int,
        sortProperty: String? = null,
        descending: Boolean = false,
    ): List<Int> {
        val classValue = unitClass.value
        val index = equipmentIndexes[country] ?: return emptyList()
        val classIndex = index["unitclass"]
        if (classIndex == undefined || classIndex == null) return emptyList()
        val list = classIndex[classValue.toString()].unsafeCast<Array<Int>?>()?.toList() ?: emptyList()
        return sortEquipmentIds(applyAvailabilityFilter(list), sortProperty, descending)
    }

    /** Union of every purchasable class for a country. Reachable by re-clicking
     *  the already-active class tab, a hidden toggle [EquipmentWindowBuilder] drives
     *  via UnitClass.NONE=0 as the sentinel class value. */
    fun getCountryEquipmentAll(country: Int, sortProperty: String? = null, descending: Boolean = false): List<Int> {
        val index = equipmentIndexes[country] ?: return emptyList()
        val classIndex = index["unitclass"]
        if (classIndex == undefined || classIndex == null) return emptyList()
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
    fun getCountriesEquipmentByClass(
        unitClass: org.osada.UnitClass,
        countries: List<Int>,
        sortProperty: String? = null,
        descending: Boolean = false,
    ): List<Int> {
        val classValue = unitClass.value
        val all = mutableListOf<Int>()
        countries.forEach { country ->
            val index = equipmentIndexes[country] ?: return@forEach
            val classIndex = index["unitclass"]
            if (classIndex == undefined || classIndex == null) return@forEach
            classIndex[classValue.toString()].unsafeCast<Array<Int>?>()?.let { all.addAll(it) }
        }
        return sortEquipmentIds(applyAvailabilityFilter(all), sortProperty, descending)
    }

    /** Union across MULTIPLE countries of every purchasable class — the "All countries" + "All
     *  classes" combination. */
    fun getCountriesEquipmentAll(
        countries: List<Int>,
        sortProperty: String? = null,
        descending: Boolean = false,
    ): List<Int> {
        val all = mutableListOf<Int>()
        countries.forEach { all.addAll(getCountryEquipmentAll(it)) }
        return sortEquipmentIds(all, sortProperty, descending)
    }

    private fun sortEquipmentIds(list: List<Int>, sortProperty: String?, descending: Boolean): List<Int> {
        if (sortProperty == null) return list
        // Primary key = the chosen property; ties break by cost then eqid so an all-equal
        // property (e.g. every unit has firing range 1) still yields a stable, meaningful
        // order instead of arbitrary index order.
        return list.sortedWith(
            compareByDescending<Int> {
                val value = _equipment[it]?.let { eq -> getProperty(eq, sortProperty) } ?: 0
                if (descending) value else -value
            }
                .thenBy { _equipment[it]?.cost ?: 0 }
                .thenBy { it },
        )
    }

    fun getCountryEquipmentByYearRange(start: Int, end: Int, country: Int): List<Int> {
        val ids = _equipment.entries
            .filter { (_, eq) -> eq.country == country && eq.yearavailable in start..end }
            .map { it.key }
        return applyAvailabilityFilter(ids)
    }

    private fun getProperty(eq: EquipmentData, property: String): Int = when (property) {
        "cost" -> eq.cost
        "initiative" -> eq.initiative
        "gunrange" -> eq.gunrange
        "movpoints" -> eq.movpoints
        "spotrange" -> eq.spotrange
        "hardatk" -> eq.hardatk
        "softatk" -> eq.softatk
        "airatk" -> eq.airatk
        "navalatk" -> eq.navalatk
        "grounddef" -> eq.grounddef
        "airdef" -> eq.airdef
        "closedef" -> eq.closedef
        "rangedefmod" -> eq.rangedefmod
        "yearavailable" -> eq.yearavailable
        "ammo" -> eq.ammo // was missing: "Sort: Unit Ammo" silently returned 0 for all
        else -> 0
    }

    fun isBridge(eqid: Int): Boolean = (_equipment[eqid]?.attr?.and(8) ?: 0) != 0

    fun ignoresEntrenchment(eqid: Int): Boolean = (_equipment[eqid]?.attr?.and(4) ?: 0) != 0

    fun isPurchasable(eqid: Int): Boolean = (_equipment[eqid]?.attr?.and(262144) ?: 0) != 0

    fun canInitiateAttackOnUnitType(attackerEqid: Int, defenderEqid: Int): Boolean {
        val attacker = _equipment[attackerEqid] ?: return false
        val defender = _equipment[defenderEqid] ?: return false
        val attackerClass = attacker.uclass
        val target = defender.target

        if (defender.uclass == org.osada.UnitClass.SUBMARINE.value &&
            attackerClass != org.osada.UnitClass.DESTROYER.value &&
            attackerClass != org.osada.UnitClass.TACTICAL_BOMBER.value
        ) {
            return false
        }

        if (target == org.osada.UnitType.SOFT.value && (attacker.attr.and(16) != 0)) return false
        if (target == org.osada.UnitType.HARD.value && (attacker.attr.and(32) != 0)) return false
        if (target == org.osada.UnitType.AIR.value) {
            if (attacker.attr.and(64) != 0) return false
            if (attackerClass != org.osada.UnitClass.AIR_DEFENCE.value &&
                attackerClass != org.osada.UnitClass.FIGHTER.value &&
                attackerClass != org.osada.UnitClass.LEVEL_BOMBER.value &&
                attackerClass != org.osada.UnitClass.TACTICAL_BOMBER.value &&
                attackerClass != org.osada.UnitClass.BATTLESHIP.value &&
                attackerClass != org.osada.UnitClass.BATTLE_CRUISER.value &&
                attackerClass != org.osada.UnitClass.LIGHT_CRUISER.value &&
                attackerClass != org.osada.UnitClass.AIR_TRANSPORT.value &&
                attacker.attr.and(32768) == 0
            ) {
                return false
            }
        }
        return true
    }
}
