package org.osada.ui

import org.osada.*
import org.osada.model.GameUnit
import org.osada.rules.GameRules
import kotlin.random.Random

/**
 * Self-contained sound layer ported from the legacy `soundData` / `soundSprite`
 * implementation.  The public object mirrors the legacy `soundData` namespace so
 * that callers can write `Sound.track.play()` just like the original JS.
 */
class SoundSprite(private val urls: List<String>) {
    private val clips: MutableList<dynamic> = mutableListOf()

    init {
        for (url in urls) {
            if (url.isBlank()) continue
            try {
                val audio = js("new Audio(url)")
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
            // Read live, not cached at construction: the settings slider can change this
            // mid-game, and clips are created once up front (init block) with no volume set
            // at all — leaving them at the Audio element's own default of 1.0 (full volume),
            // which is exactly the "too loud" complaint.
            clip.volume = uiSettings.soundVolume
        } catch (_: Throwable) {
            // ignore
        }
        try {
            clip.play()
        } catch (_: Throwable) {
            // ignore
        }
    }
}

object Sound {
    val track = SoundSprite(listOf(
        "resources/sounds/move/track.wav",
        "resources/sounds/move/track2.mp3",
        "resources/sounds/move/track3.mp3"
    ))
    val htrack = SoundSprite(listOf(
        "resources/sounds/move/htrack.wav",
        "resources/sounds/move/htrack2.mp3",
        "resources/sounds/move/htrack3.mp3"
    ))
    val leg = SoundSprite(listOf(
        "resources/sounds/move/leg.wav",
        "resources/sounds/move/leg2.mp3",
        "resources/sounds/move/leg3.mp3",
        "resources/sounds/move/leg4.mp3"
    ))
    val air = SoundSprite(listOf(
        "resources/sounds/move/air.wav",
        "resources/sounds/move/air2.mp3",
        "resources/sounds/move/air3.mp3",
        "resources/sounds/move/air4.mp3"
    ))
    val naval = SoundSprite(listOf(
        "resources/sounds/move/naval.wav",
        "resources/sounds/move/naval2.mp3",
        "resources/sounds/move/naval3.mp3"
    ))

    val gun = SoundSprite(listOf("resources/sounds/fire/gun.wav"))
    val artillery = SoundSprite(listOf(
        "resources/sounds/fire/gun.wav",
        "resources/sounds/fire/artillery.mp3",
        "resources/sounds/fire/artillery2.mp3",
        "resources/sounds/fire/artillery3.mp3",
        "resources/sounds/fire/artillery4.mp3"
    ))
    val tank = SoundSprite(listOf(
        "resources/sounds/fire/gun.wav",
        "resources/sounds/fire/tank.mp3",
        "resources/sounds/fire/tank2.mp3",
        "resources/sounds/fire/tank3.mp3",
        "resources/sounds/fire/tank4.mp3"
    ))
    val recon = SoundSprite(listOf(
        "resources/sounds/fire/recon.mp3",
        "resources/sounds/fire/recon2.mp3",
        "resources/sounds/fire/recon3.mp3"
    ))
    val airDefence = SoundSprite(listOf(
        "resources/sounds/fire/aa.mp3",
        "resources/sounds/fire/aa2.mp3",
        "resources/sounds/fire/aa3.mp3",
        "resources/sounds/fire/aa4.mp3",
        "resources/sounds/fire/aa5.mp3"
    ))
    val antiTank = SoundSprite(listOf(
        "resources/sounds/fire/antitank.mp3",
        "resources/sounds/fire/antitank2.mp3",
        "resources/sounds/fire/antitank3.mp3",
        "resources/sounds/fire/antitank4.mp3"
    ))
    val smallShip = SoundSprite(listOf(
        "resources/sounds/fire/smallship.mp3",
        "resources/sounds/fire/smallship2.mp3",
        "resources/sounds/fire/smallship3.mp3"
    ))
    val bigShip = SoundSprite(listOf(
        "resources/sounds/fire/bigship.mp3",
        "resources/sounds/fire/bigship2.mp3",
        "resources/sounds/fire/bigship3.mp3",
        "resources/sounds/fire/bigship4.mp3"
    ))
    val submarine = SoundSprite(listOf(
        "resources/sounds/fire/submarine.mp3",
        "resources/sounds/fire/submarine2.mp3"
    ))
    val infantry = SoundSprite(listOf(
        "resources/sounds/fire/smallgun.wav",
        "resources/sounds/fire/infantry.mp3",
        "resources/sounds/fire/infantry2.mp3",
        "resources/sounds/fire/infantry3.mp3",
        "resources/sounds/fire/infantry4.mp3",
        "resources/sounds/fire/infantry5.mp3"
    ))
    val fighter = SoundSprite(listOf(
        "resources/sounds/fire/smallgun.wav",
        "resources/sounds/fire/fighter.mp3",
        "resources/sounds/fire/fighter2.mp3",
        "resources/sounds/fire/fighter3.mp3"
    ))
    val bomber = SoundSprite(listOf(
        "resources/sounds/fire/smallgun.wav",
        "resources/sounds/fire/bomber.mp3",
        "resources/sounds/fire/bomber2.mp3",
        "resources/sounds/fire/bomber3.mp3",
        "resources/sounds/fire/bomber4.mp3"
    ))
    val fortification = SoundSprite(listOf(
        "resources/sounds/fire/fortification.mp3",
        "resources/sounds/fire/fortification2.mp3"
    ))
    val smallgun = SoundSprite(listOf("resources/sounds/fire/smallgun.wav"))
    val explosion = SoundSprite(listOf(
        "resources/sounds/fire/explosion.wav",
        "resources/sounds/fire/explosion2.mp3",
        "resources/sounds/fire/explosion3.mp3",
        "resources/sounds/fire/explosion4.mp3",
        "resources/sounds/fire/explosion5.mp3"
    ))
    val dummy = SoundSprite(listOf(""))

    /** Looping ambient weather sound (rain/winter). Single instance: starting replaces the previous.
     *  Respects the mute setting. Browsers may defer playback until the first user interaction. */
    private var ambient: dynamic = null

    fun startAmbient(url: String) {
        stopAmbient()
        if (uiSettings.muteUnitSounds) return
        try {
            val a = js("new Audio(url)")
            a.loop = true
            // Own slider (Settings > Sound > Ambient volume): a continuous background loop
            // shouldn't be chained to the discrete unit/fire cue level.
            a.volume = uiSettings.ambientVolume
            // play() returns a promise that rejects if autoplay is blocked (no user interaction yet);
            // swallow it so it isn't an "Uncaught (in promise)". Sound starts after the first click.
            val p = a.play()
            if (p != null && p != undefined) p.catch { }
            ambient = a
        } catch (_: Throwable) {
            // Audio may be unavailable (headless/test) or autoplay-blocked; ignore.
        }
    }

    fun stopAmbient() {
        try { ambient?.pause() } catch (_: Throwable) {}
        ambient = null
    }

    /** Live-applies the Ambient slider to a loop that is already playing (the loop only reads
     *  the setting when it starts, i.e. on weather change). */
    fun refreshAmbientVolume() {
        try { ambient?.volume = uiSettings.ambientVolume } catch (_: Throwable) {}
    }
}

/**
 * Maps [movMethod] index (legacy enum order) to a [Sound] sprite.
 * Legacy: `moveSoundByMoveMethod = "track htrack htrack leg leg air naval naval leg track naval leg".split(" ")`
 */
val moveSoundByMoveMethod = listOf(
    Sound.track,    // 0 tracked
    Sound.htrack,   // 1 halfTracked
    Sound.htrack,   // 2
    Sound.leg,      // 3 foot
    Sound.leg,      // 4
    Sound.air,      // 5 air
    Sound.naval,    // 6 naval
    Sound.naval,    // 7
    Sound.leg,      // 8
    Sound.track,    // 9
    Sound.naval,    // 10
    Sound.leg       // 11
)

/**
 * Plays the movement sound matching the unit's current movement method.
 * Mirrors the legacy behaviour: trains (ground units with DEEP_NAVAL movmethod)
 * use the tracked sound.
 */
fun playMoveSound(unit: GameUnit) {
    if (uiSettings.muteUnitSounds) return
    var movmethod = unit.unitData().movmethod
    if (GameRules.isTrain(unit)) movmethod = MovMethod.TRACKED.value
    moveSoundByMoveMethod.getOrNull(movmethod)?.play()
}
