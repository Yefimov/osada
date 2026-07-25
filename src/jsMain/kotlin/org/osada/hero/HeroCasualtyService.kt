package org.osada.hero

/**
 * Outcome weighting for a commander whose formation's unit is destroyed in combat — design brief
 * §11. Pure and deterministic (§7.4 / §29.17): a reload that replays the same destruction reproduces
 * the same fate, so save-scumming a death is not free. The spread deliberately keeps meaningful
 * survival odds (§26): a lost battle should risk the commander, but hero death must not be so likely
 * that sacrificing — or reloading — becomes the only rational play.
 *
 * Two factors shift the odds, matching §11's list: whether the unit **surrendered** (the game's
 * encirclement / no-escape case — captured, missing and killed all become far likelier) and whether
 * a **supply route** was intact (evacuation and a clean recovery become likelier). Both are supplied
 * by the caller; the service stays a pure function of its [Context].
 */
object HeroCasualtyService {
    /** What became of the commander. The mapped [status] is what the roster and dossier read. */
    enum class Disposition(
        val status: HeroStatus,
    ) {
        EVACUATED(HeroStatus.RESERVE),
        LIGHTLY_WOUNDED(HeroStatus.WOUNDED),
        SERIOUSLY_WOUNDED(HeroStatus.SERIOUSLY_WOUNDED),
        MISSING(HeroStatus.MISSING),
        CAPTURED(HeroStatus.CAPTURED),
        KILLED(HeroStatus.KILLED),
    }

    data class Context(
        val surrendered: Boolean,
        val safeSupply: Boolean,
        val seed: Int,
    )

    /**
     * Whether the commander *leaves* the formation is deliberately NOT decided here. This service is
     * only reached when the formation's unit has been destroyed, and a destroyed unit never carries
     * into the next scenario ([org.osada.model.isCampaignPersistentFor]) — so the formation is gone
     * whatever the commander's fate, and only [org.osada.hero.HeroCampaign.applyCasualty] knows that.
     * An earlier version returned `detach = false` for [Disposition.LIGHTLY_WOUNDED] ("he stays with
     * his unit"), which left the hero permanently bound to a formation no unit would ever carry again.
     */
    data class Outcome(
        val disposition: Disposition,
        val injury: HeroInjury?,
    )

    private val BASE =
        mapOf(
            Disposition.EVACUATED to 28.0,
            Disposition.LIGHTLY_WOUNDED to 24.0,
            Disposition.SERIOUSLY_WOUNDED to 16.0,
            Disposition.MISSING to 8.0,
            Disposition.CAPTURED to 8.0,
            Disposition.KILLED to 16.0,
        )
    private val SURRENDER_MOD =
        mapOf(
            Disposition.EVACUATED to 0.35,
            Disposition.LIGHTLY_WOUNDED to 0.5,
            Disposition.SERIOUSLY_WOUNDED to 0.8,
            Disposition.MISSING to 2.0,
            Disposition.CAPTURED to 4.0,
            Disposition.KILLED to 1.6,
        )
    private val SAFE_SUPPLY_MOD =
        mapOf(
            Disposition.EVACUATED to 1.6,
            Disposition.LIGHTLY_WOUNDED to 1.4,
            Disposition.SERIOUSLY_WOUNDED to 1.1,
            Disposition.MISSING to 0.4,
            Disposition.CAPTURED to 0.5,
            Disposition.KILLED to 0.5,
        )

    const val LIGHT_WOUND_ID = "light_wound"
    const val SERIOUS_WOUND_ID = "serious_wound"

    fun resolve(
        context: Context,
        scenarioId: String,
    ): Outcome {
        val weights =
            Disposition.entries.associateWith { d ->
                var w = BASE.getValue(d)
                if (context.surrendered) w *= SURRENDER_MOD.getValue(d)
                if (context.safeSupply) w *= SAFE_SUPPLY_MOD.getValue(d)
                w
            }
        val rng = SeededRandom(SeededRandom.seedFrom(context.seed.toString(), "casualty"))
        val disposition = weightedPick(rng, weights)
        val injury =
            when (disposition) {
                Disposition.LIGHTLY_WOUNDED -> HeroInjury(LIGHT_WOUND_ID, scenarioId, permanent = false)
                Disposition.SERIOUSLY_WOUNDED -> HeroInjury(SERIOUS_WOUND_ID, scenarioId, permanent = true)
                else -> null
            }
        return Outcome(disposition, injury)
    }

    private fun weightedPick(
        rng: SeededRandom,
        weights: Map<Disposition, Double>,
    ): Disposition {
        val total = weights.values.sum()
        var r = rng.nextDouble() * total
        for ((disposition, weight) in weights) {
            r -= weight
            if (r < 0) return disposition
        }
        return weights.keys.last()
    }
}
