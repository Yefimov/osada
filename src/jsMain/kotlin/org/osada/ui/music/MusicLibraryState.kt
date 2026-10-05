package org.osada.ui.music

import kotlinx.browser.document
import org.osada.ui.byId

/** Changes labels/transport without replacing focused controls or ticking hidden lists. */
internal object MusicLibraryState {
    fun refresh() {
        byId("osadaMusicNow")?.textContent = MusicSession.recording?.displayTitle() ?: musicText("enable")
        byId("osadaMusicCredit")?.textContent = MusicSession.recording?.performer ?: ""
        byId("osadaMusicToggle")?.apply {
            musicIcon(this, if (MusicSession.desiredPlaying) "pause" else "play")
            asDynamic().disabled = !musicCanPlay()
        }
        byId("osadaMusicNext")?.asDynamic()?.disabled = !musicCanNext()
        byId("osadaMusicRetry")?.style?.display =
            if (MusicSession.catalogFailed ||
                MusicSession.status in setOf("error", "stalled")
            ) {
                "inline-block"
            } else {
                "none"
            }
        byId("osadaMusicPrevious")?.asDynamic()?.disabled = MusicSession.current == null
        byId("osadaMusicVolume")?.asDynamic()?.value = MusicPreferences.volume.toString()
        val silent = MusicPreferences.muted || MusicPreferences.volume == 0.0
        byId("osadaMusicMute")?.apply {
            setAttribute("aria-pressed", silent.toString())
            musicIcon(this, if (silent) "unmute" else "mute", "speaker")
        }
        val percent = "${(MusicPreferences.volume * 100).toInt()}%"
        byId("osadaMusicPercentage")?.textContent = percent
        byId("osadaMusicVolume")?.setAttribute("aria-valuetext", percent)
        byId("osadaMusicShuffle")?.asDynamic()?.checked = MusicPreferences.shuffle
        byId("osadaMusicInstrumental")?.asDynamic()?.checked = MusicPreferences.instrumental
        byId("osadaMusicResume")?.asDynamic()?.checked = MusicPreferences.autoResume
        updateStatus()
        updateProgress()
    }

    private fun updateStatus() {
        val status =
            listOf(
                musicStatus(),
                MusicSession.contextNotice.takeIf(String::isNotBlank)?.let(::musicText) ?: "",
                if (MusicPreferences.storageFailed) musicText("storage_error") else "",
                if (!MusicTabCoordinator.available) musicText("coordination_unavailable") else "",
            ).filter(String::isNotBlank).joinToString(" · ")
        byId("osadaMusicStatus")?.let { if (it.textContent != status) it.textContent = status }
        byId("osadaMusicCount")?.textContent =
            org.osada.i18n.I18n
                .t(
                    "music.selection_count",
                    mapOf(
                        "selected" to MusicPreferences.selected.size,
                        "eligible" to MusicSession.eligible().size,
                    ),
                )
    }

    fun updateProgress() {
        byId("osadaMusicElapsed")?.textContent = musicTime(MusicSession.position)
        byId("osadaMusicDuration")?.textContent = musicTime(MusicSession.duration)
        byId("osadaMusicSeek")?.apply {
            asDynamic().disabled = MusicSession.duration == null
            asDynamic().max = (MusicSession.duration ?: 1.0).toString()
            if (document.activeElement != this) asDynamic().value = MusicSession.position.toString()
        }
    }
}
