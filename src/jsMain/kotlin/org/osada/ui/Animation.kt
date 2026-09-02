package org.osada.ui

import kotlinx.browser.window
import org.osada.uiSettings
import kotlin.random.Random

/**
 * An animation frame descriptor.  Mirrors the plain object created by the
 * legacy `animationSprite()` helper.
 */
data class Sprite(
    val image: dynamic,
    val width: Int,
    val frames: Int,
    val sound: SoundSprite,
)

/**
 * Factory that returns a randomised sprite variant each time it is invoked,
 * matching the legacy behaviour where `animationsData.explosion()` returns one
 * of several pre-loaded sprites.
 */
class AnimationSprite(
    files: List<Triple<String, Int, Int>>, // path, frames, width
    soundName: String? = null,
) {
    private val sprites: MutableList<Sprite> = mutableListOf()
    private val sound: SoundSprite = resolveSound(soundName)

    init {
        for ((path, frames, width) in files) {
            val img = js("new Image()")
            img.src = path
            sprites.add(Sprite(img, width, frames - 1, sound))
        }
    }

    operator fun invoke(): Sprite = sprites[Random.nextInt(sprites.size)]
}

private val soundByName: Map<String, SoundSprite> =
    mapOf(
        "explosion" to Sound.explosion,
        "gun" to Sound.gun,
        "artillery" to Sound.artillery,
        "tank" to Sound.tank,
        "recon" to Sound.recon,
        "antiTank" to Sound.antiTank,
        "airDefence" to Sound.airDefence,
        "smallgun" to Sound.smallgun,
        "infantry" to Sound.infantry,
        "fighter" to Sound.fighter,
        "bomber" to Sound.bomber,
        "submarine" to Sound.submarine,
        "smallShip" to Sound.smallShip,
        "bigShip" to Sound.bigShip,
        "fortification" to Sound.fortification,
    )

private fun resolveSound(name: String?): SoundSprite = soundByName[name] ?: Sound.dummy

object Animations {
    val explosion =
        AnimationSprite(
            listOf(
                Triple("resources/animations/explosions.png", 12, 120),
                Triple("resources/animations/explosions2.png", 12, 120),
                Triple("resources/animations/explosions3.png", 12, 120),
            ),
            "explosion",
        )

    val gun =
        AnimationSprite(
            listOf(
                Triple("resources/animations/fire-gun.png", 5, 150),
            ),
            "gun",
        )

    val smallgun =
        AnimationSprite(
            listOf(
                Triple("resources/animations/fire-smallgun.png", 7, 80),
            ),
            "smallgun",
        )
}

private val animationFilesByName: Map<String, AnimationSprite> =
    mapOf(
        "explosion" to Animations.explosion,
        "gun" to Animations.gun,
        "artillery" to AnimationSprite(listOf(Triple("resources/animations/fire-gun.png", 5, 150)), "artillery"),
        "tank" to AnimationSprite(listOf(Triple("resources/animations/fire-gun.png", 5, 150)), "tank"),
        "recon" to AnimationSprite(listOf(Triple("resources/animations/fire-gun.png", 5, 150)), "recon"),
        "antiTank" to AnimationSprite(listOf(Triple("resources/animations/fire-gun.png", 5, 150)), "antiTank"),
        "airDefence" to AnimationSprite(listOf(Triple("resources/animations/fire-gun.png", 5, 150)), "airDefence"),
        "smallgun" to Animations.smallgun,
        "infantry" to AnimationSprite(listOf(Triple("resources/animations/fire-smallgun.png", 7, 80)), "infantry"),
        "fighter" to AnimationSprite(listOf(Triple("resources/animations/fire-smallgun.png", 7, 80)), "fighter"),
        "bomber" to AnimationSprite(listOf(Triple("resources/animations/fire-smallgun.png", 7, 80)), "bomber"),
        "submarine" to AnimationSprite(listOf(Triple("resources/animations/fire-gun.png", 5, 150)), "submarine"),
        "smallShip" to AnimationSprite(listOf(Triple("resources/animations/fire-gun.png", 5, 150)), "smallShip"),
        "bigShip" to AnimationSprite(listOf(Triple("resources/animations/fire-gun.png", 5, 150)), "bigShip"),
        "fortification" to
            AnimationSprite(
                listOf(Triple("resources/animations/fire-smallgun.png", 7, 80)),
                "fortification",
            ),
    )

/**
 * Maps unit class index (legacy `unitClass` enum order) to the animation key
 * used during an attack.
 */
val attackAnimationByClass =
    listOf(
        null,
        "infantry",
        "tank",
        "recon",
        "antiTank",
        "airDefence",
        "fortification",
        "smallgun",
        "artillery",
        "airDefence",
        "fighter",
        "bomber",
        "bomber",
        null,
        "submarine",
        "smallShip",
        "bigShip",
        null,
        null,
        "bigShip",
        "bigShip",
        "smallShip",
    )

fun getAnimationSprite(key: String): AnimationSprite? = animationFilesByName[key]

/**
 * A single canvas-based animation.  It replicates the legacy `Animation`
 * class: it clears/draws successive frames of a sprite sheet and plays an
 * associated sound.
 */
class Animation(
    private val ctx: dynamic,
    private val x: Int,
    private val y: Int,
    private val sprite: Sprite,
    private val rotate: Double = 0.0,
    private val clearByComposite: Boolean = false,
    /**
     * OG's per-equipment attack/destruction sound, when one is available for the unit this
     * animation belongs to. Null -- the shipped state everywhere, since the audio may not be
     * redistributed -- plays the animation's own class sound (`ui/OgSoundLibrary`).
     */
    private val soundOverride: SoundSprite? = null,
) {
    private var intervalId: Int = -1
    private var frame: Int = 0
    private val delay: Int = if (uiSettings.quickAnimation) 50 else 150

    val duration: Int get() = delay * sprite.frames

    fun start() {
        (soundOverride ?: sprite.sound).play()
        intervalId = window.setInterval({ step() }, delay)
    }

    private fun step() {
        if (clearByComposite) {
            drawFrame(frame - 1, "destination-out")
        } else {
            val h = sprite.image.height as Double
            ctx.clearRect(x, y, sprite.width, h)
        }
        if (frame > sprite.frames) {
            window.clearInterval(intervalId)
            return
        }
        if (frame <= sprite.frames) {
            drawFrame(frame, "source-over")
        }
        frame++
    }

    private fun drawFrame(
        f: Int,
        composite: String,
    ) {
        if (f < 0) return
        val h = sprite.image.height as Double
        ctx.save()
        ctx.globalCompositeOperation = composite
        ctx.translate(x + sprite.width / 2.0, y + h / 2.0)
        ctx.rotate(rotate)
        ctx.drawImage(
            sprite.image,
            sprite.width * f,
            0,
            sprite.width,
            h,
            -sprite.width / 2.0,
            -h / 2.0,
            sprite.width,
            h,
        )
        ctx.restore()
    }
}

/**
 * Sequences multiple [Animation] objects one after another and optionally
 * invokes a callback when the chain finishes.
 */
class AnimationChain {
    companion object {
        private const val CHAIN_GAP_MS = 500
        private const val CLEAR_DELAY_MS = 100
    }

    private val animations: MutableList<Animation> = mutableListOf()
    private var index: Int = 0

    fun add(animation: Animation) {
        animations.add(animation)
    }

    fun clear() {
        animations.clear()
        index = 0
    }

    fun start(callback: dynamic = null) {
        if (index < animations.size) {
            animations[index].start()
            window.setTimeout(
                { start(callback) },
                animations[index].duration + CHAIN_GAP_MS,
            )
            index++
        } else {
            val cbfunc = callback?.cbfunc
            if (callback != null && cbfunc != null && cbfunc != undefined) cbfunc(callback)
            window.setTimeout({ clear() }, CLEAR_DELAY_MS)
        }
    }
}
