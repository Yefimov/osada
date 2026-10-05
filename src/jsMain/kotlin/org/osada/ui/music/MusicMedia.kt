package org.osada.ui.music

import kotlinx.browser.window

/** Cancels obsolete streams/promises. One audible element, streaming rather than decoding a library. */
@Suppress("TooManyFunctions")
internal class MusicMedia(
    private val state: (String) -> Unit,
    private val ended: () -> Unit,
    private val failed: () -> Unit,
) {
    private var audio: dynamic = null
    private var generation = 0
    private var playRequest = 0
    private var stallTimer = 0
    private var desired = false
    private var variants = emptyList<MusicVariant>()
    private var variantIndex = 0
    private var pendingPosition = 0.0
    val position: Double get() = number(audio?.currentTime) ?: pendingPosition
    val duration: Double? get() = number(audio?.duration)?.takeIf { it > 0 }

    fun select(
        recording: MusicRecording,
        playing: Boolean,
        position: Double = 0.0,
    ) {
        dispose()
        desired = playing
        variants = recording.variants
        variantIndex = 0
        pendingPosition = position
        if (playing) load() else state("paused")
    }

    fun play() {
        desired = true
        if (audio == null) load() else requestPlay(generation)
    }

    fun pause() {
        desired = false
        playRequest++
        clearStall()
        audio?.pause()
        state("paused")
    }

    fun seek(seconds: Double) {
        if (!seconds.isFinite()) return
        pendingPosition = seconds.coerceAtLeast(0.0)
        if (audio != null && duration != null) audio.currentTime = pendingPosition.coerceAtMost(duration!!)
    }

    fun volume(
        level: Double,
        muted: Boolean,
    ) {
        if (audio != null) {
            audio.volume = level
            audio.muted = muted
        }
    }

    private fun load() {
        val variant =
            variants.getOrNull(variantIndex) ?: run {
                failed()
                return
            }
        val token = ++generation
        try {
            val element = js("new Audio()")
            audio = element
            element.preload = "none"
            element.loop = false
            element.volume = MusicPreferences.volume
            element.muted = MusicPreferences.muted
            bindEvents(element, token)
            if (element.canPlayType(variant.mime) == "") {
                fallback()
                return
            }
            element.src = variant.url
            if (desired) {
                waiting(token, "loading")
                requestPlay(token)
            }
        } catch (_: Throwable) {
            if (token == generation) fallback()
        }
    }

    private fun bindEvents(
        element: dynamic,
        token: Int,
    ) {
        element.onloadedmetadata = { _: dynamic ->
            if (token == generation && pendingPosition > 0) {
                duration?.let { element.currentTime = pendingPosition.coerceAtMost(it) }
            }
        }
        element.onplaying = { _: dynamic ->
            if (token == generation) {
                if (desired) {
                    clearStall()
                    state("playing")
                } else {
                    element.pause()
                }
            }
        }
        element.onpause = { _: dynamic ->
            if (token == generation && !element.ended.unsafeCast<Boolean>()) {
                state(if (desired) "interrupted" else "paused")
            }
        }
        element.onwaiting = { _: dynamic -> if (token == generation && desired) waiting(token) }
        element.onstalled = element.onwaiting
        element.onended = { _: dynamic ->
            if (token == generation) {
                clearStall()
                ended()
            }
        }
        element.onerror = { _: dynamic -> if (token == generation) fallback() }
    }

    private fun requestPlay(token: Int) {
        val request = ++playRequest
        state("loading")
        val promise = audio?.play()
        if (promise != null) {
            promise.catch { error: dynamic ->
                if (token == generation && request == playRequest && desired) {
                    clearStall()
                    when (error?.name as? String) {
                        "NotAllowedError" -> {
                            desired = false
                            state("blocked")
                        }
                        "AbortError" -> Unit
                        else -> fallback()
                    }
                }
            }
        }
    }

    private fun waiting(
        token: Int,
        status: String = "buffering",
    ) {
        state(status)
        if (stallTimer != 0) return
        stallTimer =
            window.setTimeout({
                stallTimer = 0
                if (token == generation && desired) {
                    audio?.pause()
                    desired = false
                    state("stalled")
                }
            }, STALL_MS)
    }

    private fun fallback() {
        val intent = desired
        dispose()
        desired = intent
        variantIndex++
        if (variantIndex < variants.size) load() else failed()
    }

    private fun dispose() {
        generation++
        clearStall()
        if (audio != null) {
            audio.onplaying = null
            audio.onpause = null
            audio.onended = null
            audio.onerror = null
            audio.onwaiting = null
            audio.onstalled = null
            audio.onloadedmetadata = null
            audio.pause()
            audio.removeAttribute("src")
            audio.load()
            audio = null
        }
    }

    private fun clearStall() {
        window.clearTimeout(stallTimer)
        stallTimer = 0
    }

    private fun number(value: dynamic): Double? = (value as? Number)?.toDouble()?.takeIf { it.isFinite() }

    companion object {
        private const val STALL_MS = 15000
    }
}
