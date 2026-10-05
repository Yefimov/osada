package org.osada.ui.music

import kotlinx.browser.window

/** Page lifetime, user-owned playback. Scenario teardown publishes context, never stops My mix. */
@Suppress("TooManyFunctions")
internal object MusicSession {
    val queue = MusicQueue()
    val unavailable = mutableSetOf<String>()
    private val listeners = mutableSetOf<() -> Unit>()
    private val media = MusicMedia(::mediaState, ::onEnded, ::onFailed)
    private var initialized = false
    private var campaignFile: String? = null
    private var scenarioFile: String? = null
    private var scenarioTrack: String? = null
    var contextNotice = ""
        private set
    var undoSelection: Set<String>? = null
        private set
    private var undoContext: String? = null
    private var writeTimer = 0
    var current: String? = null
        private set
    var status = "idle"
        private set
    var desiredPlaying = false
        private set
    var loaded = false
        private set
    var catalogFailed = false
        private set
    var playingOnce = false
        private set
    val position: Double get() = media.position
    val duration: Double? get() = media.duration
    val recording: MusicRecording? get() = MusicCatalog.recordings[current]

    fun initialize() {
        if (initialized) return
        initialized = true
        MusicPreferences.load()
        // Registered before the coordinator's release handler: only the owner may checkpoint.
        window.addEventListener("pagehide", { checkpoint() })
        MusicTabCoordinator.install {
            desiredPlaying = false
            media.pause()
            status = "transferred"
            notifyViews()
        }
        MusicCatalog.load { success ->
            loaded = true
            catalogFailed = !success
            if (success) {
                MusicPreferences.reconcileCatalog()
                restoreCheckpoint()
                reconcile()
                context(campaignFile, scenarioFile, scenarioTrack)
            }
            notifyViews()
            val resumeWanted = MusicPreferences.autoResume && MusicPreferences.userPlaying && current != null
            val resumeAllowed =
                MusicTabCoordinator.available &&
                    kotlinx.browser.document
                        .asDynamic()
                        .visibilityState == "visible"
            if (resumeWanted && resumeAllowed) {
                play(explicit = false)
            }
        }
        window.setInterval({ if (MusicTabCoordinator.owner) checkpoint() }, CHECKPOINT_MS)
    }

    fun subscribe(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun notifyViews() {
        listeners.toList().forEach { it() }
    }

    fun toggle() {
        if (desiredPlaying) pause() else play()
    }

    fun play(explicit: Boolean = true) {
        if (!loaded || catalogFailed) return
        if (current !in validIds()) {
            val first =
                MusicCatalog.intro?.takeIf { current == null && it in eligible() && it !in queue.played }
                    ?: queue.next(eligible(), current)
            if (first == null) {
                status = "empty"
                notifyViews()
                return
            }
            if (first !in queue.played) queue.direct(first)
            select(first, false)
        }
        desiredPlaying = true
        if (explicit) {
            MusicPreferences.userPlaying = true
            MusicPreferences.save(updateIntent = true)
        }
        status = "loading"
        notifyViews()
        MusicTabCoordinator.claim(explicit) {
            if (desiredPlaying) {
                media.play()
                checkpoint()
                notifyViews()
            }
        }
    }

    fun pause() {
        desiredPlaying = false
        MusicPreferences.userPlaying = false
        MusicTabCoordinator.cancelClaim()
        media.pause()
        checkpoint()
        MusicPreferences.save(updateIntent = true)
        notifyViews()
    }

    fun next() {
        val intent = desiredPlaying
        val id = queue.next(eligible(), current)
        if (id == null) {
            desiredPlaying = false
            media.pause()
            status = "empty"
            notifyViews()
            checkpoint()
            return
        }
        select(id, intent)
    }

    fun previous() {
        if (position > PREVIOUS_RESTART_SECONDS) {
            seek(0.0)
            return
        }
        val id = queue.previous(validIds())
        if (id == null) seek(0.0) else select(id, desiredPlaying)
    }

    fun direct(id: String) {
        if (id !in validIds()) return
        queue.direct(id)
        playingOnce = id !in eligible()
        select(id, false, once = playingOnce)
        MusicPreferences.save()
        play()
    }

    fun seek(seconds: Double) {
        media.seek(seconds)
        scheduleSave()
        notifyViews()
    }

    fun setVolume(value: Double) {
        if (!value.isFinite()) return
        MusicPreferences.load()
        MusicPreferences.volume = value.coerceIn(0.0, 1.0)
        if (value > 0) MusicPreferences.lastNonzero = MusicPreferences.volume
        media.volume(MusicPreferences.volume, MusicPreferences.muted)
        MusicPreferences.save("volume", "lastNonzero")
        notifyViews()
    }

    fun mute() {
        MusicPreferences.muted = !(MusicPreferences.muted || MusicPreferences.volume == 0.0)
        if (!MusicPreferences.muted && MusicPreferences.volume == 0.0) setVolume(MusicPreferences.lastNonzero)
        media.volume(MusicPreferences.volume, MusicPreferences.muted)
        MusicPreferences.save("muted", "volume", "lastNonzero")
        notifyViews()
    }

    /** Stable context keys; automatic changes never claim audio ownership or reverse a pause. */
    fun context(
        campaign: String?,
        scenario: String?,
        track: String? = null,
    ) {
        campaignFile = campaign
        scenarioFile = scenario
        scenarioTrack = track
        if (!loaded) return
        val key = campaign?.let { "campaign:$it" } ?: scenario?.let { "scenario:$it" }
        if (key == null) {
            MusicPreferences.contextKey = null
            contextNotice = "no_preset"
            return
        }
        val changed = key != MusicPreferences.contextKey
        MusicPreferences.contextKey = key
        if (changed) restoreContextMix(key, campaign, scenario)
        if (MusicPreferences.legacyScenario && track?.lowercase() == "africa2.mp3") {
            MusicPreferences.selected = mutableSetOf("og-african-drums")
        }
        MusicPreferences.legacyScenario = false
        reconcile(persistSelection = true)
    }

    /** A picker edits its highlighted campaign, while the loaded game's identity stays intact. */
    fun beginEditing(target: Pair<String?, String?>?) {
        val key =
            target?.first?.let { "campaign:$it" } ?: target?.second?.let { "scenario:$it" }
                ?: campaignFile?.let { "campaign:$it" } ?: scenarioFile?.let { "scenario:$it" }
        if (key != MusicPreferences.contextKey) undoSelection = null
        MusicPreferences.contextKey = key
    }

    fun endEditing() = beginEditing(null)

    private fun restoreContextMix(
        key: String,
        campaign: String?,
        scenario: String?,
    ) {
        val saved = MusicPreferences.campaignMixes[key]
        val preset = MusicPresets.ids(campaign, scenario)?.takeIf { it.isNotEmpty() }
        val upgrade =
            MusicPreferences.pendingPresetUpgrades.remove(key) &&
                saved != null &&
                MusicPresets.wasLegacyDefault(saved, campaign, scenario = scenario)
        val restored = if (upgrade) preset ?: saved else saved ?: preset
        restored?.let { MusicPreferences.selected = it.toMutableSet() }
        contextNotice = if (preset == null) "no_preset" else ""
    }

    fun matchCampaign(
        campaign: String? = campaignFile,
        scenario: String? = scenarioFile,
    ) {
        val preset = MusicPresets.ids(campaign, scenario)
        if (preset == null || preset.isEmpty()) {
            contextNotice = "no_preset"
            notifyViews()
            return
        }
        undoSelection = MusicPreferences.selected.toSet()
        MusicPreferences.contextKey = campaign?.let { "campaign:$it" } ?: scenario?.let { "scenario:$it" }
        MusicPreferences.contextKey?.let { MusicPreferences.pendingPresetUpgrades.remove(it) }
        undoContext = MusicPreferences.contextKey
        MusicPreferences.selected = preset.toMutableSet()
        contextNotice = "preset_applied"
        reconcile(persistSelection = true)
    }

    fun undo() {
        if (undoContext ==
            MusicPreferences.contextKey
        ) {
            undoSelection?.let { MusicPreferences.selected = it.toMutableSet() }
        }
        undoSelection = null
        contextNotice = ""
        reconcile(persistSelection = true)
    }

    fun setSelected(
        id: String,
        selected: Boolean,
    ) {
        MusicPreferences.contextKey?.let { MusicPreferences.pendingPresetUpgrades.remove(it) }
        if (selected) MusicPreferences.selected.add(id) else MusicPreferences.selected.remove(id)
        reconcile(persistSelection = true)
    }

    fun eligible(): Set<String> =
        MusicPreferences.selected
            .intersect(validIds())
            .filter { !MusicPreferences.instrumental || MusicCatalog.recordings[it]?.instrumental == true }
            .toSet()

    fun validIds(): Set<String> = MusicCatalog.recordings.keys - unavailable

    fun reconcile(persistSelection: Boolean = false) {
        queue.reconcile(eligible(), MusicCatalog.recordings.mapValues { it.value.workId }, MusicPreferences.shuffle)
        if (current != null) playingOnce = current !in eligible()
        if (persistSelection) {
            MusicPreferences.contextKey?.let { MusicPreferences.campaignMixes[it] = MusicPreferences.selected.toSet() }
            MusicPreferences.save("selected")
        } else {
            MusicPreferences.save()
        }
        notifyViews()
    }

    fun exclude(id: String) = setSelected(id, false)

    fun restore(id: String) = setSelected(id, true)

    fun retry() {
        unavailable.clear()
        if (catalogFailed) {
            loaded = false
            MusicCatalog.load { success ->
                loaded = true
                catalogFailed = !success
                if (success) {
                    MusicPreferences.reconcileCatalog()
                    reconcile()
                    context(campaignFile, scenarioFile, scenarioTrack)
                }
                notifyViews()
            }
        } else {
            reconcile()
            current?.let { select(it, false) }
            play()
        }
    }

    fun upcoming(): List<String> = queue.scheduled(eligible())

    private fun select(
        id: String,
        playing: Boolean,
        once: Boolean = false,
        seconds: Double = 0.0,
    ) {
        val track = MusicCatalog.recordings[id] ?: return
        current = id
        playingOnce = once
        desiredPlaying = playing
        media.select(track, playing && MusicTabCoordinator.owner, seconds)
        checkpoint()
        notifyViews()
    }

    private fun onEnded() {
        next()
        if (!desiredPlaying) {
            status = "ended"
            notifyViews()
        }
    }

    private fun onFailed() {
        current?.let { unavailable.add(it) }
        reconcile()
        val hasReplacement = eligible().isNotEmpty()
        if (desiredPlaying && hasReplacement) {
            next()
        } else {
            desiredPlaying = false
            status = "error"
            notifyViews()
        }
    }

    private fun mediaState(value: String) {
        status = value
        if (value in setOf("blocked", "stalled", "interrupted")) desiredPlaying = false
        notifyViews()
    }

    private fun scheduleSave() {
        window.clearTimeout(writeTimer)
        writeTimer =
            window.setTimeout({
                checkpoint()
            }, PREFERENCE_DEBOUNCE_MS)
    }

    private fun checkpoint() {
        if (!MusicTabCoordinator.owner) return
        val data = js("({})")
        data.current = current
        data.position = position
        data.once = playingOnce
        data.catalogVersion = MusicCatalog.version
        data.history = queue.history.toTypedArray()
        data.cursor = queue.cursor
        data.upcoming = queue.upcoming.toTypedArray()
        data.played = queue.played.toTypedArray()
        MusicPreferences.saveCheckpoint(data)
    }

    private fun restoreCheckpoint() {
        val data = MusicPreferences.checkpoint ?: return
        val ids = validIds()
        queue.history.addAll(MusicCatalog.strings(data.history).filter { it in ids }.takeLast(MusicQueue.HISTORY_LIMIT))
        queue.cursor =
            (
                (data.cursor as? Number)
                    ?.toDouble()
                    ?.takeIf {
                        it.isFinite()
                    }?.toInt() ?: queue.history.lastIndex
            ).coerceIn(-1, queue.history.lastIndex)
        if (data.catalogVersion == MusicCatalog.version) {
            queue.upcoming.addAll(MusicCatalog.strings(data.upcoming).filter { it in ids }.distinct())
            queue.played.addAll(MusicCatalog.strings(data.played).filter { it in ids })
        }
        val seconds = (data.position as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 } ?: 0.0
        val currentId = (data.current as? String)?.takeIf { it in ids }
        currentId?.let { select(it, false, once = data.once == true, seconds = seconds) }
    }

    private const val PREFERENCE_DEBOUNCE_MS = 350
    private const val PREVIOUS_RESTART_SECONDS = 3.0
    private const val CHECKPOINT_MS = 15000
}
