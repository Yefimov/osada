package org.osada.ui.music

import kotlinx.browser.localStorage
import org.osada.VERSION

/** Stable JSON fields; deliberately separate from game saves and room snapshots. */
internal object MusicPreferences {
    private const val SCHEMA_VERSION = 3
    private const val DEFAULT_VOLUME = 0.2
    const val KEY = "osada-music-v1"
    private const val CHECKPOINT_KEY = "$KEY-checkpoint"
    private var savedSettings: dynamic = js("({})")
    var enabled = mutableSetOf<String>()
    var excluded = mutableSetOf<String>()
    var selected = mutableSetOf<String>()
    var shuffle = true
    val campaignMixes = mutableMapOf<String, Set<String>>()
    val pendingPresetUpgrades = mutableSetOf<String>()
    var contextKey: String? = null
    var legacyScenario = false
    var positions: dynamic = js("({})")
    private var migrateSelection = false
    var instrumental = false
    var volume = DEFAULT_VOLUME
    var muted = false
    var lastNonzero = DEFAULT_VOLUME
    var autoResume = false
    var userPlaying = false
    var inspected = "instrumental"
    var storageFailed = false
    private var loaded = false
    private var fresh = true
    var checkpoint: dynamic = null
        private set

    fun load() {
        if (loaded) return
        loaded = true
        val raw = read()
        savedSettings = raw ?: js("({})")
        if (raw != null && raw.schemaVersion in arrayOf(1, 2, SCHEMA_VERSION)) {
            fresh = false
            migrateSelection = raw.schemaVersion == 1
            selected = MusicCatalog.strings(raw.selected).toMutableSet()
            shuffle = raw.shuffle != false
            loadCampaignMixes(raw)
            pendingPresetUpgrades.addAll(
                if (raw.schemaVersion ==
                    SCHEMA_VERSION
                ) {
                    MusicCatalog.strings(raw.pendingPresetUpgrades)
                } else {
                    campaignMixes.keys
                },
            )
            loadPositions(raw.positions)
            enabled = MusicCatalog.strings(raw.enabled).toMutableSet()
            excluded = MusicCatalog.strings(raw.excluded).toMutableSet()
            instrumental = raw.instrumental == true
            volume = level(raw.volume) ?: volume
            muted = raw.muted == true
            lastNonzero = level(raw.lastNonzero)?.takeIf { it > 0 } ?: DEFAULT_VOLUME
            legacyScenario = migrateSelection && raw.source == "scenario"
            autoResume = raw.autoResume == true
            userPlaying = raw.userPlaying == true
            inspected = raw.inspected as? String ?: inspected
            loadCheckpoint(raw)
        } else {
            // UiSettings historically omitted this key. Only a real persisted number migrates.
            try {
                val legacy = localStorage.getItem("osada-settings-${VERSION.substringBefore('.')}")
                if (legacy != null) level(JSON.parse<dynamic>(legacy).musicVolume)?.let { volume = it }
            } catch (_: Throwable) {
                storageFailed = true
            }
        }
    }

    private fun loadCampaignMixes(raw: dynamic) {
        MusicCatalog.array(raw.campaignMixes).forEach { entry ->
            (entry.key as? String)?.let { campaignMixes[it] = MusicCatalog.strings(entry.selected).toSet() }
        }
    }

    private fun loadCheckpoint(raw: dynamic) {
        checkpoint = read(CHECKPOINT_KEY) ?: raw.checkpoint
        if (checkpoint?.current == "og-park-ambience") {
            checkpoint = null
            userPlaying = false
        }
    }

    private fun loadPositions(raw: dynamic) {
        positions = js("({})")
        listOf("menu", "picker").forEach { surface ->
            val candidate = if (raw != null) raw[surface] else null
            val x = level(candidate?.x)
            val y = level(candidate?.y)
            if (x != null && y != null) {
                val entry = js("({})")
                entry.x = x
                entry.y = y
                positions[surface] = entry
            }
        }
    }

    fun reconcileCatalog() {
        val collections = MusicCatalog.collections.map { it.id }.toSet()
        if (fresh && "instrumental" in collections) enabled.add("instrumental")
        if (fresh || migrateSelection) {
            selected =
                MusicCatalog.collections
                    .filter { it.id in enabled }
                    .flatMap { it.recordings }
                    .filter { it !in excluded }
                    .toMutableSet()
        }
        if (migrateSelection &&
            "eastern-front" in enabled &&
            "osada-roads" !in excluded
        ) {
            selected.add("osada-roads")
        }
        migrateSelection = false
        fresh = false
        selected.retainAll(MusicCatalog.recordings.keys)
        campaignMixes.keys.toList().forEach { key ->
            campaignMixes[key] =
                campaignMixes.getValue(key).intersect(MusicCatalog.recordings.keys)
        }
        enabled.retainAll(collections)
        excluded.retainAll(MusicCatalog.recordings.keys)
        if (inspected !in collections) {
            inspected = MusicCatalog.collections.firstOrNull()?.id ?: "instrumental"
        }
        save()
    }

    /** Write only local edits. Unrelated settings may have been changed in another tab. */
    fun save(
        vararg fields: String,
        updateIntent: Boolean = false,
    ) {
        try {
            val latest = read()
            val current = settingsSnapshot()
            val data = mergeMusicSettings(latest, savedSettings, current, fields.toSet(), contextKey)
            val oldCheckpoint = latest?.checkpoint
            if (updateIntent || latest == null || oldCheckpoint?.current == "og-park-ambience") {
                data.userPlaying = userPlaying
            }
            if (data.checkpoint?.current == "og-park-ambience") data.checkpoint = null
            localStorage.setItem(KEY, JSON.stringify(data))
            savedSettings = JSON.parse<dynamic>(JSON.stringify(current))
        } catch (_: Throwable) {
            storageFailed = true
        }
    }

    /** Playback writes a separate key, so a timer/pagehide cannot race with settings edits. */
    fun saveCheckpoint(data: dynamic) {
        try {
            localStorage.setItem(CHECKPOINT_KEY, JSON.stringify(data))
        } catch (_: Throwable) {
            storageFailed = true
        }
    }

    private fun settingsSnapshot(): dynamic {
        val data = js("({})")
        data.schemaVersion = SCHEMA_VERSION
        data.pendingPresetUpgrades = pendingPresetUpgrades.toTypedArray()
        data.selected = selected.toTypedArray()
        data.shuffle = shuffle
        data.campaignMixes =
            campaignMixes
                .map { (key, ids) ->
                    val entry = js("({})")
                    entry.key = key
                    entry.selected = ids.toTypedArray()
                    entry
                }.toTypedArray()
        data.positions = positions
        data.instrumental = instrumental
        data.volume = volume
        data.muted = muted
        data.lastNonzero = lastNonzero
        data.autoResume = autoResume
        data.inspected = inspected
        return data
    }

    private fun read(key: String = KEY): dynamic =
        try {
            localStorage.getItem(key)?.let { JSON.parse<dynamic>(it) }
        } catch (_: Throwable) {
            storageFailed = true
            null
        }

    internal fun level(raw: dynamic): Double? = (raw as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..1.0 }
}

/** Changed fields merge independently; campaign edits affect only the context being edited. */
internal fun mergeMusicSettings(
    latest: dynamic,
    baseline: dynamic,
    current: dynamic,
    fields: Set<String> = emptySet(),
    context: String? = null,
): dynamic {
    val merged = if (latest == null) js("({})") else JSON.parse<dynamic>(JSON.stringify(latest))
    MusicCatalog.strings(js("Object").keys(current)).forEach { field ->
        val changed =
            latest == null || field in fields || JSON.stringify(current[field]) != JSON.stringify(baseline[field])
        if (field !in setOf("campaignMixes", "pendingPresetUpgrades") &&
            changed
        ) {
            merged[field] = current[field]
        }
    }
    val mixes = musicMixEntries(latest?.campaignMixes).toMutableMap()
    val oldMixes = musicMixEntries(baseline.campaignMixes)
    musicMixEntries(current.campaignMixes).forEach { (key, entry) ->
        val explicit = key == context && "selected" in fields
        if (latest == null ||
            explicit ||
            JSON.stringify(entry) != JSON.stringify(oldMixes[key])
        ) {
            mixes[key] = entry
        }
    }
    merged.campaignMixes = mixes.values.toTypedArray()
    val oldPending = MusicCatalog.strings(baseline.pendingPresetUpgrades).toSet()
    val pending = MusicCatalog.strings(current.pendingPresetUpgrades).toSet()
    merged.pendingPresetUpgrades =
        (
            (MusicCatalog.strings((latest ?: current).pendingPresetUpgrades).toSet() - (oldPending - pending)) +
                (pending - oldPending)
        ).toTypedArray()
    return merged
}

private fun musicMixEntries(raw: dynamic): Map<String, dynamic> =
    MusicCatalog.array(raw).mapNotNull { entry -> (entry.key as? String)?.let { it to entry } }.toMap()

/** Owner-reviewed campaign themes select complete collections, without chronology exclusions. */
internal object MusicPresets {
    private val civilWar = listOf("revolution-1917", "civil-war-red-army")
    private val sovietWar = listOf("eastern-front", "instrumental")
    private val campaigns =
        mapOf(
            "camp6bn9.json" to sovietWar + "spanish-republic",
            "camp6bn5.json" to sovietWar + "soviet-partisans",
            "camp6bn8.json" to listOf("yugoslav-partisans", "instrumental"),
            "reddestiny.json" to sovietWar + "ww2-remembrance",
            "ncampdfn.json" to listOf("korean-war-dprk", "instrumental"),
            "aljf.json" to listOf("instrumental"),
            "rhu.json" to listOf("revolution-1917", "instrumental"),
            "ccampdfc.json" to civilWar,
            "novemberrevolution.json" to listOf("german-communists", "revolution-1917", "instrumental"),
            "volarm.json" to civilWar,
            "acampdf2.json" to civilWar,
            "polsov.json" to civilWar,
            "gce.json" to listOf("spanish-republic", "antifascist-resistance", "instrumental"),
            "ga4.json" to sovietWar,
            "spa.json" to listOf("instrumental"),
            "nvc.json" to listOf("vietnam-revolutionary", "instrumental"),
            "rcampdfr.json" to sovietWar,
            "rsoc.json" to listOf("chinese-revolution", "instrumental"),
            "062d.json" to sovietWar,
            "camp6.json" to sovietWar,
            "camp6bn4.json" to listOf("greek-resistance", "greek-democratic-army", "instrumental"),
            "forward.json" to sovietWar,
            "simpob.json" to civilWar,
        )

    fun collectionsFor(
        campaign: String?,
        scenario: String?,
    ): List<String>? =
        if (campaign != null) {
            campaigns[campaign]
        } else {
            MusicScenarioPresets.collections[scenario]
                ?: MusicScenarioPresets.campaigns[scenario]?.let { campaigns[it] }
        }

    fun ids(
        campaign: String?,
        scenario: String?,
    ): Set<String>? {
        val collections = collectionsFor(campaign, scenario) ?: return null
        return MusicCatalog.collections
            .filter { it.id in collections }
            .flatMap { it.recordings }
            .toSet()
    }

    /** Schema 2 recorded generated mixes without provenance. Recognize its exact default sets only. */
    fun wasLegacyDefault(
        selected: Set<String>,
        campaign: String?,
        instrumentalIds: Set<String> =
            MusicCatalog.collections
                .firstOrNull {
                    it.id == "instrumental"
                }?.recordings
                ?.toSet()
                .orEmpty(),
        scenario: String? = null,
    ): Boolean {
        // Regional albums and the standalone mappings arrived after schema 3. An
        // older instrumental mix may be deliberate; only explicit Match replaces it.
        if (campaign in setOf("ncampdfn.json", "rsoc.json", "nvc.json") ||
            (campaign == null && scenario != null)
        ) {
            return false
        }
        val revolution = setOf("osada-workers-marseillaise", "osada-smelo-tovarishchi")
        val wartime = setOf("osada-katyusha", "osada-sacred-war")
        val legacy =
            when (campaign) {
                "volarm.json", "simpob.json", "polsov.json", "acampdf2.json", "ccampdfc.json" ->
                    listOf(revolution, revolution + "osada-red-army-strongest")
                "camp6bn9.json", "camp6bn5.json" -> listOf(wartime, setOf("osada-katyusha"))
                else -> emptyList()
            }
        return selected.isNotEmpty() && (selected == instrumentalIds || selected in legacy)
    }
}

/** Reviewed scenario filenames from scenariolist.js, independent of the loaded campaign.
 * Individual battles inherit their authored campaign theme; historical specials have explicit themes.
 * No title keywords, filename prefixes or song-date filtering are used at runtime. */
internal object MusicScenarioPresets {
    val campaigns =
        buildMap {
            // Red Army Campaign (1936-1945)
            listOf(
                "bn9s01.xml",
                "bn9s02.xml",
                "bn9s03.xml",
                "bn9s04.xml",
                "bn9s05.xml",
                "bn9s06.xml",
                "bn9s07.xml",
                "bn9s08.xml",
                "bn9s09.xml",
                "bn9s10.xml",
                "bn9s11.xml",
                "bn9s12.xml",
                "bn9s13.xml",
                "bn9s14.xml",
                "bn9s15.xml",
                "bn9s16.xml",
                "bn9s17.xml",
                "bn9s18.xml",
                "bn9s19.xml",
                "bn9s20.xml",
            ).forEach { put(it, "camp6bn9.json") }
            // Stay Alive - Red Partisans (1941-1945)
            listOf(
                "bn5s01.xml",
                "bn5s02.xml",
                "bn5s09.xml",
                "bn5s03.xml",
                "bn5s08.xml",
                "bn5s06.xml",
                "bn5s04.xml",
                "bn5s07.xml",
                "bn5s05.xml",
                "bn5s10.xml",
            ).forEach { put(it, "camp6bn5.json") }
            // Yugoslav Front (1941-1945)
            listOf(
                "bn8s00.xml",
                "bn8s01.xml",
                "bn8s02.xml",
                "bn8s03.xml",
                "bn8s04.xml",
                "bn8s05.xml",
                "bn8s06.xml",
                "bn8s07.xml",
                "bn8s08.xml",
                "bn8s09.xml",
                "bn8s10.xml",
                "bn8s11.xml",
                "bn8s12.xml",
                "bn8s13.xml",
                "bn8s14.xml",
                "bn8s15.xml",
                "bn8s16.xml",
                "bn8s17.xml",
                "bn8s18.xml",
            ).forEach { put(it, "camp6bn8.json") }
            // Ejercito Popular (1936-1939)
            listOf(
                "gce1.xml",
                "gce2.xml",
                "gce3.xml",
                "gce4.xml",
                "gce5.xml",
                "gce6.xml",
                "gce7.xml",
                "gce8.xml",
                "gce9.xml",
                "vgce0.xml",
                "vgce1.xml",
                "vgce4.xml",
            ).forEach { put(it, "gce.json") }
            // Zhukov: Red God of War (1939-1945)
            listOf(
                "ga4_0.xml",
                "ga4_1.xml",
                "ga4_2.xml",
                "ga4_3.xml",
                "ga4_4.xml",
                "ga4_5.xml",
                "ga4_6.xml",
                "ga4_7.xml",
                "ga4_8.xml",
                "ga4_9.xml",
                "ga4_10.xml",
                "ga4_11.xml",
                "ga4_12.xml",
                "ga4_13.xml",
                "ga4_14.xml",
                "ga4_15.xml",
                "ga4_16.xml",
            ).forEach { put(it, "ga4.json") }
            // Viet Cong (1964-1975)
            listOf(
                "nvc00.xml",
                "nvc01.xml",
                "nvc02.xml",
                "nvc03.xml",
                "nvc04.xml",
                "nvc05.xml",
                "nvc05b.xml",
                "nvc06.xml",
                "nvc07.xml",
                "nvc08.xml",
                "nvc09.xml",
                "nvc10.xml",
            ).forEach { put(it, "nvc.json") }
            // Soviet Black Sea Fleet (1941-1944)
            listOf(
                "rcampfa1.xml",
                "rcampfa2.xml",
                "rcampki1.xml",
                "rcampode.xml",
                "rcampper.xml",
                "rcampro1.xml",
                "rcampker.xml",
                "rcampfeo.xml",
                "rcampcho.xml",
                "rcampsev.xml",
                "rcampno1.xml",
                "rcampsuc.xml",
                "rcampor1.xml",
                "rcampor2.xml",
                "rcampmai.xml",
                "rcampro2.xml",
                "rcampno2.xml",
                "rcampkry.xml",
                "rcampno3.xml",
                "rcamptam.xml",
                "rcampod2.xml",
                "rcampse2.xml",
                "rcampjas.xml",
                "rcampfa3.xml",
                "rcampbuc.xml",
                "rcamprus.xml",
                "rcampsof.xml",
            ).forEach { put(it, "rcampdfr.json") }
            // The Great Patriotic War USSR Campaign (1942-1945)
            listOf(
                "ruscam00.xml",
                "ruscam01.xml",
                "ruscam02.xml",
                "ruscam03.xml",
                "ruscam04.xml",
                "ruscam05.xml",
                "ruscam06.xml",
                "ruscam07.xml",
                "ruscam08.xml",
                "ruscam09.xml",
                "ruscam10.xml",
                "ruscam11.xml",
                "ruscam12.xml",
                "ruscam13.xml",
                "ruscam14.xml",
                "ruscam15.xml",
                "ruscam16.xml",
                "ruscam17.xml",
                "ruscam18.xml",
                "ruscam19.xml",
            ).forEach { put(it, "062d.json") }
            // Soviet Counter-Offensive Campaign (1942-1946)
            listOf(
                "rcampx01.xml",
                "rcampx02.xml",
                "rcampx03.xml",
                "rcampx04.xml",
                "rcampx05.xml",
                "rcampx06.xml",
                "rcampx07.xml",
                "rcampx08.xml",
                "rcampx09.xml",
                "rcampx10.xml",
                "rcampx11.xml",
                "rcampx12.xml",
                "rcampx13.xml",
                "rcampx14.xml",
                "rcampx15.xml",
                "rcampx16.xml",
                "rcampx17.xml",
                "rcampx18.xml",
                "rcampx19.xml",
                "rcampx20.xml",
                "rcampx21.xml",
                "rcampx22.xml",
                "rcampx23.xml",
                "rcampx24.xml",
                "rcampx25.xml",
                "rcampx26.xml",
                "rcampx27.xml",
            ).forEach { put(it, "camp6.json") }
            // Greece: Resistance and Civil War (1940-1949)
            listOf(
                "bn4s00.xml",
                "bn4s01.xml",
                "bn4s02.xml",
                "bn4s03.xml",
                "bn4s04.xml",
                "bn4s05.xml",
                "bn4s06.xml",
                "bn4s07.xml",
                "bn4s08.xml",
                "bn4s09.xml",
                "bn4s10.xml",
                "bn4s11.xml",
                "bn4s12.xml",
                "bn4s13.xml",
                "bn4s14.xml",
                "bn4s15.xml",
                "bn4s16.xml",
                "bn4s17.xml",
                "bn4s18.xml",
                "bn4s19.xml",
                "bn4s20.xml",
                "bn4s21.xml",
                "bn4s22.xml",
                "bn4s23.xml",
                "bn4s24.xml",
                "bn4s25.xml",
                "bn4s26.xml",
            ).forEach { put(it, "camp6bn4.json") }
            // Forward, Comrade! (1939-1945)
            listOf(
                "forward0.xml",
                "forward1.xml",
                "forward2.xml",
                "forward3.xml",
                "forward4.xml",
                "forward5.xml",
                "forward6.xml",
                "forward7.xml",
                "forward8.xml",
                "forward9.xml",
                "forward10.xml",
                "forward11.xml",
                "forward12.xml",
                "forward13.xml",
                "forward14.xml",
                "forward15.xml",
                "forward16.xml",
                "forward18.xml",
                "forward19.xml",
                "forward20.xml",
                "forward21.xml",
                "forward22.xml",
                "forward23.xml",
                "forward24.xml",
                "forward25.xml",
                "forward26.xml",
                "forward27.xml",
                "forward28.xml",
                "forward29.xml",
                "forward30.xml",
                "forward31.xml",
                "forward32.xml",
                "forward33.xml",
                "forward34.xml",
                "forward35.xml",
                "forward36.xml",
                "forward37.xml",
                "forward38.xml",
                "forward39.xml",
                "forward40.xml",
                "forward41.xml",
                "forward42.xml",
                "forward43.xml",
            ).forEach { put(it, "forward.json") }
            // Sesena is the Spanish battle in the otherwise Soviet Red Army sequence.
            put("bn9s00.xml", "gce.json")
            put("khalkin1.xml", "ga4.json")
            put("khalkin2.xml", "ga4.json")
            put("sparta.xml", "novemberrevolution.json")
            put("partisanen.xml", "camp6bn8.json")
            put("1920_perekop.xml", "ccampdfc.json")
            listOf("madrid_36.xml", "mallorca.xml", "teruel.xml", "ebro.xml", "aran.xml")
                .forEach { put(it, "gce.json") }
        }

    val collections =
        mapOf(
            "cubali01.xml" to listOf("cuban-revolution", "instrumental"),
            "op_hooper.xml" to listOf("cuban-revolution", "instrumental"),
            "1954_dienbienphu.xml" to listOf("vietnam-revolutionary", "instrumental"),
            // Preserve the existing authored African Drums presets.
            "battle_cuito.xml" to listOf("og-scores"),
            "vkampala_sfb.xml" to listOf("og-scores"),
        )
}
