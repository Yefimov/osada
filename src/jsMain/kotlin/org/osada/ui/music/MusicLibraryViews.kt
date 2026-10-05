package org.osada.ui.music

import kotlinx.browser.document
import org.osada.i18n.I18n
import org.osada.ui.addTag
import org.osada.ui.byId
import org.w3c.dom.HTMLElement

/** Library content only: browsing, inclusion and transport are intentionally distinct controls. */
internal object MusicLibraryViews {
    fun renderLists() {
        val focused = document.activeElement as? HTMLElement
        val focusKey = focused?.getAttribute("data-music-focus")
        val list = byId("osadaMusicList") ?: return
        val scroll = list.scrollTop
        val collectionRoot = byId("osadaMusicCollections") ?: return
        val collectionScroll = collectionRoot.scrollTop
        // Keep checkbox nodes across selection updates so a native double click
        // reaches the same control after its first click changes the selection.
        val collectionKey = I18n.language.code + MusicCatalog.collections.map { it.id }.joinToString()
        if (collectionRoot.getAttribute("data-music-collections") != collectionKey) {
            collectionRoot.textContent = ""
            renderCollections(collectionRoot)
            collectionRoot.setAttribute("data-music-collections", collectionKey)
        }
        updateCollections()
        list.textContent = ""
        val ids = visibleIds()
        ids.mapNotNull { MusicCatalog.recordings[it] }.forEach { track ->
            if (matchesSearch(track)) renderTrack(list, track)
        }
        if (list.childElementCount == 0) musicLabel(list, if (!MusicSession.loaded) "loading_catalog" else "empty_list")
        list.scrollTop = scroll
        collectionRoot.scrollTop = collectionScroll
        byId("osadaMusicSearchScope")?.textContent =
            if (MusicLibraryPresenter.search.isNotBlank()) musicText("all_tracks") else inspectedTitle()
        byId("osadaMusicBrowser")?.classList?.toggle("show-collections", MusicLibraryPresenter.collectionsVisible)
        renderUndo()
        if (focused?.isConnected == false) restoreListFocus(focusKey)
    }

    private fun restoreListFocus(key: String?) {
        val controls = document.querySelectorAll("[data-music-focus]")
        (0 until controls.length)
            .mapNotNull { controls.item(it) as? HTMLElement }
            .firstOrNull { it.getAttribute("data-music-focus") == key }
            ?.focus()
    }

    private fun renderCollections(parent: HTMLElement) {
        MusicCatalog.collections.forEach { collection ->
            val row = addTag(parent, "div").apply { className = "osada-music-collection" }
            row.classList.toggle("selected", MusicPreferences.inspected == collection.id)
            musicCheckbox(
                row,
                "musicInclude-${collection.id}",
                null,
                false,
            ) { _ ->
                val ids = collection.recordings.toSet().intersect(MusicSession.validIds())
                if (MusicPreferences.selected.containsAll(ids)) {
                    MusicPreferences.selected.removeAll(ids)
                } else {
                    MusicPreferences.selected.addAll(ids)
                }
                MusicSession.reconcile(persistSelection = true)
            }.apply {
                setAttribute("aria-label", I18n.t("music.include", mapOf("collection" to collection.displayTitle())))
                setAttribute("data-music-focus", "include:${collection.id}")
                if (collection.id == "instrumental") {
                    title = musicText("include_all_hint")
                    asDynamic().ondblclick = { _: dynamic ->
                        MusicPreferences.selected.addAll(
                            MusicCatalog.collections.flatMap { it.recordings }.intersect(MusicSession.validIds()),
                        )
                        MusicSession.reconcile(persistSelection = true)
                    }
                }
            }
            val button = inspectButton(row, collection.id, collection.displayTitle())
            collection.artwork?.let { path ->
                val art = addTag(button, "img")
                art.setAttribute("src", path)
                art.setAttribute("alt", "")
                art.setAttribute("loading", "lazy")
                art.asDynamic().onerror = { _: dynamic -> art.style.display = "none" }
            }
        }
    }

    private fun updateCollections() {
        MusicCatalog.collections.forEach { collection ->
            val checkbox = byId("musicInclude-${collection.id}") ?: return@forEach
            val ids = collection.recordings.toSet().intersect(MusicSession.validIds())
            val selected = ids.intersect(MusicPreferences.selected).size
            val checked = ids.isNotEmpty() && selected == ids.size
            val mixed = selected > 0 && selected < ids.size
            checkbox.asDynamic().checked = checked
            checkbox.asDynamic().indeterminate = mixed
            checkbox.asDynamic().disabled = ids.isEmpty()
            checkbox.setAttribute("aria-checked", if (mixed) "mixed" else checked.toString())
            val inspected = MusicPreferences.inspected == collection.id
            checkbox.parentElement?.classList?.toggle("selected", inspected)
            checkbox.parentElement?.querySelector("button")?.setAttribute("aria-pressed", inspected.toString())
        }
    }

    private fun inspectButton(
        parent: HTMLElement,
        id: String,
        title: String,
    ): HTMLElement =
        musicButton(parent, "tracks") {
            MusicPreferences.inspected = id
            MusicPreferences.save("inspected")
            MusicLibraryPresenter.collectionsVisible = false
            MusicLibraryPresenter.refresh(force = true)
        }.apply {
            textContent = title
            setAttribute("data-music-focus", "inspect:$id")
            setAttribute("aria-pressed", (MusicPreferences.inspected == id).toString())
        }

    private fun renderTrack(
        parent: HTMLElement,
        track: MusicRecording,
    ) {
        val row = addTag(parent, "div").apply { className = "osada-music-track" }
        val selected = track.id in MusicPreferences.selected
        musicCheckbox(row, "musicTrack-${track.id}", null, selected) { checked ->
            MusicSession.setSelected(track.id, checked)
        }.apply {
            setAttribute(
                "aria-label",
                musicText(if (selected) "remove_from_mix" else "add_to_mix") + ": " + track.displayTitle(),
            )
            setAttribute("data-music-focus", "select:${track.id}")
        }
        musicButton(row, "play") { MusicSession.direct(track.id) }.apply {
            setAttribute("data-music-focus", "play:${track.id}")
            musicIcon(this, "play")
            title += ": " + track.displayTitle()
            setAttribute("aria-label", title)
            asDynamic().disabled = track.id in MusicSession.unavailable
        }
        val info = addTag(row, "div").apply { className = "osada-music-track-info" }
        addTag(info, "strong").textContent = track.displayTitle()
        addTag(info, "div").textContent = track.performer
        trackLabels(info, track)
        addTag(row, "span").apply {
            className = "osada-music-track-time"
            textContent = musicTime(if (track.id == MusicSession.current) MusicSession.duration else track.duration)
        }
    }

    private fun trackLabels(
        info: HTMLElement,
        track: MusicRecording,
    ) {
        if (track.id ==
            MusicSession.current
        ) {
            musicLabel(info, if (MusicSession.status == "playing") "now_playing" else "selected")
        }
        if (track.id == MusicSession.current && MusicSession.playingOnce) musicLabel(info, "once")
        if (track.id in MusicSession.unavailable) musicLabel(info, "unavailable")
    }

    private fun renderUndo() {
        val row = byId("osadaMusicUndo") ?: return
        row.textContent = ""
        if (MusicSession.undoSelection != null) {
            musicLabel(row, "preset_applied")
            musicButton(row, "undo", MusicSession::undo)
        }
    }

    private fun matchesSearch(track: MusicRecording): Boolean =
        "${track.title} ${track.titleRu.orEmpty()} ${track.originalTitle.orEmpty()} ${track.performer}"
            .lowercase()
            .contains(
                MusicLibraryPresenter.search.lowercase().trim(),
            )

    private fun visibleIds(): List<String> =
        if (MusicLibraryPresenter.search.isNotBlank()) {
            MusicCatalog.recordings.keys.toList()
        } else {
            MusicCatalog.collections
                .firstOrNull { it.id == MusicPreferences.inspected }
                ?.recordings ?: emptyList()
        }

    private fun inspectedTitle(): String =
        MusicCatalog.collections
            .firstOrNull { it.id == MusicPreferences.inspected }
            ?.displayTitle() ?: musicText("tracks")
}
