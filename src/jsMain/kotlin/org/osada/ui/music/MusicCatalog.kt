package org.osada.ui.music

import org.osada.i18n.I18n
import org.w3c.xhr.XMLHttpRequest

internal data class MusicVariant(
    val url: String,
    val mime: String,
)

internal data class MusicRecording(
    val id: String,
    val title: String,
    val performer: String,
    val instrumental: Boolean,
    val variants: List<MusicVariant>,
    val workId: String = id,
    val titleRu: String? = null,
    val duration: Double? = null,
    val workDate: String? = null,
    val lyricDate: String? = null,
    val datePrecision: String? = null,
    val dateSource: String? = null,
    val recordingDate: String? = null,
    val recordingDatePrecision: String? = null,
    val recordingDateSource: String? = null,
    val workDatePrecision: String? = null,
    val workDateSource: String? = null,
    val lyricDatePrecision: String? = null,
    val lyricDateSource: String? = null,
    val originalTitle: String? = null,
) {
    fun displayTitle(): String {
        val localized = if (I18n.language.code == "ru") titleRu ?: title else title
        return originalTitle?.takeIf { it != localized && it.isNotBlank() }?.let { "$localized · $it" } ?: localized
    }
}

internal data class MusicCollection(
    val id: String,
    val title: String,
    val titleRu: String?,
    val recordings: List<String>,
    val artwork: String?,
) {
    fun displayTitle(): String = if (I18n.language.code == "ru") titleRu ?: title else title
}

/** Metadata only; no audio requests until a playback action. */
internal object MusicCatalog {
    private const val METADATA_TIMEOUT_MS = 15000
    var recordings = emptyMap<String, MusicRecording>()
        private set
    var collections = emptyList<MusicCollection>()
        private set
    var intro: String? = null
        private set
    var version = 1
        private set

    fun load(done: (Boolean) -> Unit) {
        loadJson("resources/sounds/music/catalog.json") { data ->
            try {
                require(data != null && data.schemaVersion == 1)
                recordings = array(data.recordings).mapNotNull(::recording).associateBy { it.id }
                collections = array(data.collections).mapNotNull(::collection).distinctBy { it.id }
                intro = (data.introRecordingId as? String)?.takeIf { it in recordings }
                version = (data.catalogVersion as? Int) ?: 1
                done(true)
            } catch (_: Throwable) {
                done(false)
            }
        }
    }

    private fun recording(raw: dynamic): MusicRecording? {
        val id = raw.id as? String ?: ""
        val title = raw.title as? String ?: ""
        val variants =
            array(raw.variants).mapNotNull {
                val url = it.url as? String
                val mime = it.mime as? String
                if (url != null && mime != null && localUrl(url)) MusicVariant(url, mime) else null
            }
        if (variants.isEmpty() || id.isBlank() || title.isBlank()) return null
        return MusicRecording(
            id,
            title,
            raw.performer as? String ?: "",
            raw.instrumental == true,
            variants,
            raw.workId as? String ?: id,
            raw.titleRu as? String,
            (raw.duration as? Number)?.toDouble()?.takeIf { it.isFinite() && it > 0 },
            raw.workDate as? String,
            raw.lyricDate as? String,
            raw.datePrecision as? String,
            raw.dateSource as? String,
            raw.recordingDate as? String,
            raw.recordingDatePrecision as? String,
            raw.recordingDateSource as? String,
            raw.workDatePrecision as? String,
            raw.workDateSource as? String,
            raw.lyricDatePrecision as? String,
            raw.lyricDateSource as? String,
            raw.originalTitle as? String,
        )
    }

    private fun collection(raw: dynamic): MusicCollection? {
        val id = raw.id as? String ?: ""
        val title = raw.title as? String ?: ""
        val ids = strings(raw.recordingIds).filter { it in recordings }.distinct()
        if (id.isBlank() || ids.isEmpty()) return null
        return MusicCollection(
            id,
            title,
            raw.titleRu as? String,
            ids,
            (raw.artwork as? String)?.takeIf(::localUrl),
        )
    }

    private fun localUrl(url: String): Boolean =
        url.startsWith("resources/sounds/music/") && !url.contains("..") && !url.contains(":")

    internal fun array(raw: dynamic): List<dynamic> {
        if (raw == null || !js("Array.isArray(raw)").unsafeCast<Boolean>()) return emptyList()
        val length = (raw.length as? Int ?: 0).coerceIn(0, 2000)
        return (0 until length).map { raw[it] }
    }

    internal fun strings(raw: dynamic): List<String> = array(raw).mapNotNull { it as? String }

    internal fun loadJson(
        url: String,
        done: (dynamic) -> Unit,
    ) {
        val request = XMLHttpRequest()
        var completed = false

        fun finish(value: dynamic) {
            if (completed) return
            completed = true
            done(value)
        }
        try {
            request.open("GET", url, true)
            request.timeout = METADATA_TIMEOUT_MS
            request.onload = {
                val value =
                    try {
                        if (request.status.toInt() == 200) JSON.parse<dynamic>(request.responseText) else null
                    } catch (_: Throwable) {
                        null
                    }
                finish(value)
            }
            request.onerror = { finish(null) }
            request.ontimeout = { finish(null) }
            request.send()
        } catch (_: Throwable) {
            finish(null)
        }
    }
}
