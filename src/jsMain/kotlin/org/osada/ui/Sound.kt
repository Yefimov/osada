package org.osada.ui

import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.model.GameUnit
import org.osada.rules.GameRules
import org.osada.rules.isTrain
import org.osada.uiSettings
import kotlin.random.Random

/**
 * Self-contained sound layer ported from the legacy `soundData` / `soundSprite`
 * implementation.  The public object mirrors the legacy `soundData` namespace so
 * that callers can write `Sound.track.play()` just like the original JS.
 */
class SoundSprite(
    urls: List<String>,
) {
    private val clips: MutableList<dynamic> = mutableListOf()

    init {
        for (url in urls) {
            if (url.isBlank()) continue
            try {
                val audio = js("new Audio()")
                audio.src = url
                audio.load()
                clips.add(audio)
            } catch (_: Throwable) {
                // Audio constructor may fail in headless/node test environments.
            }
        }
    }

    fun play() {
        if (uiSettings.muteUnitSounds || clips.isEmpty()) return
        val clip = clips[Random.nextInt(clips.size)]
        try {
            clip.currentTime = 0
        } catch (_: Throwable) {
            // ignore
        }
        try {
            clip.volume = uiSettings.soundVolume
        } catch (_: Throwable) {
            // ignore
        }

        val promise: dynamic =
            try {
                clip.play()
            } catch (_: Throwable) {
                clips.remove(clip)
                null
            }
        // play() rejects asynchronously; try/catch cannot catch NotSupportedError from the Promise.
        if (promise != null && promise != undefined) {
            promise.catch { _: dynamic ->
                clips.remove(clip)
                null
            }
        }
    }
}

object Sound {
    val track =
        SoundSprite(
            listOf(
                "resources/sounds/move/track.wav",
                "resources/sounds/move/track2.mp3",
                "resources/sounds/move/track3.mp3",
            ),
        )
    val htrack =
        SoundSprite(
            listOf(
                "resources/sounds/move/htrack.wav",
                "resources/sounds/move/htrack2.mp3",
                "resources/sounds/move/htrack3.mp3",
            ),
        )
    val leg =
        SoundSprite(
            listOf(
                "resources/sounds/move/leg.wav",
                "resources/sounds/move/leg2.mp3",
                "resources/sounds/move/leg3.mp3",
                "resources/sounds/move/leg4.mp3",
            ),
        )
    val air =
        SoundSprite(
            listOf(
                "resources/sounds/move/air.wav",
                "resources/sounds/move/air2.mp3",
                "resources/sounds/move/air3.mp3",
                "resources/sounds/move/air4.mp3",
            ),
        )
    val naval =
        SoundSprite(
            listOf(
                "resources/sounds/move/naval.wav",
                "resources/sounds/move/naval2.mp3",
                "resources/sounds/move/naval3.mp3",
            ),
        )

    val gun = SoundSprite(listOf("resources/sounds/fire/gun.wav"))
    val artillery =
        SoundSprite(
            listOf(
                "resources/sounds/fire/gun.wav",
                "resources/sounds/fire/artillery.mp3",
                "resources/sounds/fire/artillery2.mp3",
                "resources/sounds/fire/artillery3.mp3",
                "resources/sounds/fire/artillery4.mp3",
            ),
        )
    val tank =
        SoundSprite(
            listOf(
                "resources/sounds/fire/gun.wav",
                "resources/sounds/fire/tank.mp3",
                "resources/sounds/fire/tank2.mp3",
                "resources/sounds/fire/tank3.mp3",
                "resources/sounds/fire/tank4.mp3",
            ),
        )
    val recon =
        SoundSprite(
            listOf(
                "resources/sounds/fire/recon.mp3",
                "resources/sounds/fire/recon2.mp3",
                "resources/sounds/fire/recon3.mp3",
            ),
        )
    val airDefence =
        SoundSprite(
            listOf(
                "resources/sounds/fire/aa.mp3",
                "resources/sounds/fire/aa2.mp3",
                "resources/sounds/fire/aa3.mp3",
                "resources/sounds/fire/aa4.mp3",
                "resources/sounds/fire/aa5.mp3",
            ),
        )
    val antiTank =
        SoundSprite(
            listOf(
                "resources/sounds/fire/antitank.mp3",
                "resources/sounds/fire/antitank2.mp3",
                "resources/sounds/fire/antitank3.mp3",
                "resources/sounds/fire/antitank4.mp3",
            ),
        )
    val smallShip =
        SoundSprite(
            listOf(
                "resources/sounds/fire/smallship.mp3",
                "resources/sounds/fire/smallship2.mp3",
                "resources/sounds/fire/smallship3.mp3",
            ),
        )
    val bigShip =
        SoundSprite(
            listOf(
                "resources/sounds/fire/bigship.mp3",
                "resources/sounds/fire/bigship2.mp3",
                "resources/sounds/fire/bigship3.mp3",
                "resources/sounds/fire/bigship4.mp3",
            ),
        )
    val submarine =
        SoundSprite(
            listOf(
                "resources/sounds/fire/submarine.mp3",
                "resources/sounds/fire/submarine2.mp3",
            ),
        )
    val infantry =
        SoundSprite(
            listOf(
                "resources/sounds/fire/smallgun.wav",
                "resources/sounds/fire/infantry.mp3",
                "resources/sounds/fire/infantry2.mp3",
                "resources/sounds/fire/infantry3.mp3",
                "resources/sounds/fire/infantry4.mp3",
                "resources/sounds/fire/infantry5.mp3",
            ),
        )
    val fighter =
        SoundSprite(
            listOf(
                "resources/sounds/fire/smallgun.wav",
                "resources/sounds/fire/fighter.mp3",
                "resources/sounds/fire/fighter2.mp3",
                "resources/sounds/fire/fighter3.mp3",
            ),
        )
    val bomber =
        SoundSprite(
            listOf(
                "resources/sounds/fire/smallgun.wav",
                "resources/sounds/fire/bomber.mp3",
                "resources/sounds/fire/bomber2.mp3",
                "resources/sounds/fire/bomber3.mp3",
                "resources/sounds/fire/bomber4.mp3",
            ),
        )
    val fortification =
        SoundSprite(
            listOf(
                "resources/sounds/fire/fortification.mp3",
                "resources/sounds/fire/fortification2.mp3",
            ),
        )
    val smallgun = SoundSprite(listOf("resources/sounds/fire/smallgun.wav"))
    val explosion =
        SoundSprite(
            listOf(
                "resources/sounds/fire/explosion.wav",
                "resources/sounds/fire/explosion2.mp3",
                "resources/sounds/fire/explosion3.mp3",
                "resources/sounds/fire/explosion4.mp3",
                "resources/sounds/fire/explosion5.mp3",
            ),
        )
    val dummy = SoundSprite(listOf(""))

    /** Environment is independent of unit mute and the user-owned music session. */
    private var ambient: dynamic = null
    private var ambientUrl: String? = null
    private var ambientGeneration = 0
    private var ambientPending = false
    private var ambientInstalled = false
    private var ambientOwner = false
    private val ambientToken =
        kotlin.js.Date
            .now()
            .toString() + kotlin.random.Random.nextDouble()
    private const val AMBIENT_OWNER_KEY = "osada-environment-owner"

    fun startAmbient(url: String) {
        installAmbientEvents()
        if (ambientUrl == url) return
        stopAmbient()
        ambientUrl = url
        refreshAmbientVolume()
    }

    fun stopAmbient() {
        ambientGeneration++
        ambientPending = false
        ambientUrl = null
        suspendAmbient()
    }

    private fun suspendAmbient() {
        ambientGeneration++
        ambientPending = false
        try {
            ambient?.pause()
        } catch (_: Throwable) {
        }
        ambient = null
        try {
            if (org.osada.storage.localStorage
                    .getItem(AMBIENT_OWNER_KEY) == ambientToken
            ) {
                org.osada.storage.localStorage
                    .removeItem(AMBIENT_OWNER_KEY)
            }
        } catch (_: Throwable) {
        }
        ambientOwner = false
    }

    private fun installAmbientEvents() {
        if (ambientInstalled) return
        ambientInstalled = true
        listOf("pointerdown", "keydown").forEach { type ->
            kotlinx.browser.document.addEventListener(type, { event ->
                if (event.isTrusted && ambientPending && ambientOwner) playAmbient()
            }, true)
        }
        kotlinx.browser.document.addEventListener("visibilitychange", {
            if (ambientVisible()) refreshAmbientVolume() else suspendAmbient()
        })
        kotlinx.browser.window.addEventListener("pagehide", { suspendAmbient() })
        kotlinx.browser.window.addEventListener("storage", { event ->
            val raw = event.asDynamic()
            val otherOwner = raw.newValue != ambientToken && raw.newValue != null
            val lostOwnership =
                org.osada.storage.localStorage
                    .getItem(AMBIENT_OWNER_KEY) != ambientToken
            if (raw.key == AMBIENT_OWNER_KEY && otherOwner && lostOwnership) {
                suspendAmbient()
            }
        })
    }

    private fun ambientVisible(): Boolean =
        kotlinx.browser.document
            .asDynamic()
            .visibilityState == "visible"

    private fun playAmbient() {
        val audio = ambient ?: return
        val token = ambientGeneration
        ambientPending = false
        try {
            val promise = audio.play()
            if (promise != null && promise != undefined) {
                promise.then({ _: dynamic ->
                    if (token != ambientGeneration || !ambientOwner || !ambientVisible()) audio.pause()
                    null
                }, { error: dynamic ->
                    if (token == ambientGeneration) ambientPending = error?.name == "NotAllowedError"
                    null
                })
            }
        } catch (_: Throwable) {
            ambientPending = false
        }
    }

    /** Zero cancels pending retries; raising the independent environment slider takes effect now. */
    fun refreshAmbientVolume() {
        if (!ambientVisible() || uiSettings.ambientVolume <= 0) {
            suspendAmbient()
            return
        }
        val url = ambientUrl ?: return
        if (ambient == null) {
            try {
                org.osada.storage.localStorage
                    .setItem(AMBIENT_OWNER_KEY, ambientToken)
                ambientOwner = true
                val audio = js("new Audio()")
                audio.src = url
                audio.loop = true
                audio.volume = uiSettings.ambientVolume
                ambient = audio
                playAmbient()
            } catch (_: Throwable) {
                ambientPending = false
            }
        } else {
            ambient.volume = uiSettings.ambientVolume
        }
    }

    /** Compatibility seam: both Settings and library use the independent music preference. */
    fun refreshMusicVolume() {
        org.osada.ui.music.MusicSession
            .setVolume(uiSettings.musicVolume)
    }

    /** Music playback is user-owned. Legacy callers publish scenario context rather than autoplay. */
    fun startMusic(url: String) {
        ScenarioMusic.play(url.substringAfterLast('/'))
    }

    fun stopMusic() {
        ScenarioMusic.play(null)
    }
}

/**
 * Maps a `movmethod` index (legacy enum order, see [playMoveSound]) to a [Sound] sprite.
 * Legacy: `moveSoundByMoveMethod = "track htrack htrack leg leg air naval naval leg track naval leg".split(" ")`
 */
val moveSoundByMoveMethod =
    listOf(
        Sound.track, // 0 tracked
        Sound.htrack, // 1 halfTracked
        Sound.htrack, // 2
        Sound.leg, // 3 foot
        Sound.leg, // 4
        Sound.air, // 5 air
        Sound.naval, // 6 naval
        Sound.naval, // 7
        Sound.leg, // 8
        Sound.track, // 9
        Sound.naval, // 10
        Sound.leg, // 11
    )

/**
 * Plays the movement sound matching the unit's current movement method.
 * Mirrors the legacy behaviour: trains (ground units with DEEP_NAVAL movmethod)
 * use the tracked sound.
 */
fun playMoveSound(unit: GameUnit) {
    // OG picks the movement sound per EQUIPMENT RECORD (`equip.xeqp` @70). `OgSoundLibrary` answers
    // false in every shipped build -- its audio may not be redistributed and is not present -- and
    // the class-based table below is then exactly what it always was.
    if (uiSettings.muteUnitSounds || OgSoundLibrary.playMove(unit)) return
    val data = unit.unitData()
    val unmountedEngineerInfantry =
        !unit.isMounted &&
            unit.carrier <= 0 &&
            data.uclass == UnitClass.INFANTRY.value &&
            data.name.contains("Engineer", ignoreCase = true)
    if (unmountedEngineerInfantry) {
        Sound.leg.play()
        return
    }
    var movmethod = data.movmethod
    if (GameRules.isTrain(unit)) movmethod = MovMethod.TRACKED.value
    moveSoundByMoveMethod.getOrNull(movmethod)?.play()
}
