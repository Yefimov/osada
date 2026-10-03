package org.osada.multiplayer.coop

import org.osada.hero.HeroPortraitArt
import org.osada.hero.PortraitComposerV2

/**
 * A commander's face, stored as IDENTIFIERS and never as a bitmap or a URL (§4, §10).
 *
 * Either an allowlisted painted [artId], or the composition the local procedural composer produced
 * ([seed] + [layerIds] + [poolId] + [generatorVersion]). The composition is stored rather than
 * re-derived from the seed so a later change to the composer cannot silently change a teammate's
 * face. Cosmetic: it never advances the gameplay random stream, and it never copies a legendary
 * hero's biography, medals or signature trait.
 */
data class CoopPortrait(
    val artId: String? = null,
    val seed: Int = 0,
    val layerIds: List<String> = emptyList(),
    val poolId: String? = null,
    val generatorVersion: Int = GENERATOR_VERSION,
) {
    /** True when every identifier resolves to a known asset. Anything else is refused at the door. */
    fun isValid(): Boolean =
        if (artId != null) {
            HeroPortraitArt.byId(artId) != null
        } else {
            layerIds.isNotEmpty() &&
                layerIds.size <= MAX_LAYERS &&
                layerIds.all { PortraitComposerV2.layerPath(it) != null }
        }

    companion object {
        const val GENERATOR_VERSION = 1
        const val MAX_LAYERS = 24

        fun legendary(artId: String): CoopPortrait? =
            CoopPortrait(artId = artId).takeIf { HeroPortraitArt.byId(artId) != null }

        /**
         * A procedural face. A reroll changes the cosmetic seed; pool and optional gender constrain
         * its appearance. A reload reproduces the same face from the stored composition.
         */
        fun generated(
            seed: Int,
            pool: PortraitComposerV2.Pool = PortraitComposerV2.Pool.USSR_1942,
            female: Boolean? = null,
        ): CoopPortrait {
            val composition =
                PortraitComposerV2.composeFor(
                    seed = seed,
                    unitClass = 0,
                    rankId = DEFAULT_RANK,
                    birthYear = null,
                    serviceYear = null,
                    poolOverride = pool,
                    femaleOverride = female,
                )
            return CoopPortrait(
                seed = seed,
                layerIds = composition.layerIds,
                poolId = composition.poolId,
            )
        }

        private const val DEFAULT_RANK = "colonel"
    }
}

/**
 * A persistent PLAYER profile, not a mortal unit hero (§1.2): losing a tank must not eliminate its
 * human player. It has no battlefield body. [id] is stable for the whole campaign; [participantId]
 * is the room connection currently bound to it and is refreshed on recovery.
 */
data class CoopCommander(
    val id: String,
    val seat: Int,
    val participantId: String?,
    val name: String,
    val portrait: CoopPortrait,
    val abilityIds: List<String>,
) {
    val leaderEffects: Set<org.osada.LeaderType>
        get() = abilityIds.mapNotNull(CoopAbilityCatalogue::leaderFor).toSet()

    companion object {
        const val MAX_NAME_LENGTH = 24

        /** Names are shown with `textContent` everywhere, but are still bounded and stripped of controls. */
        fun cleanName(raw: String?): String =
            raw
                .orEmpty()
                .filter { !it.isISOControl() }
                .trim()
                .take(MAX_NAME_LENGTH)

        fun idForSeat(seat: Int): String = "c${seat + 1}"
    }
}

/** Slot allowance and loadout legality (§5.1, §5.4). */
object CoopLoadout {
    /** Every commander starts with this many positive-ability slots. */
    const val BASE_SLOTS = 2

    /** The hard ceiling: two separate campaign unlocks add one slot each. */
    const val MAX_SLOTS = 4

    /** Most [CoopTier.RESTRICTED] abilities one commander may carry. */
    const val MAX_RESTRICTED = 1

    enum class Issue { TOO_MANY, UNKNOWN_ABILITY, DUPLICATE_ABILITY, TOO_MANY_RESTRICTED }

    /** Slots after [unlocksEarned] common campaign milestones. A standalone battle earns none. */
    fun slotCap(unlocksEarned: Int): Int = (BASE_SLOTS + unlocksEarned).coerceIn(BASE_SLOTS, MAX_SLOTS)

    /**
     * Legality of a chosen set against [cap]. Leaving slots empty is allowed (and earns no
     * compensation); a negative card, a fifth ability and a duplicate effect are all refused here.
     */
    fun validate(
        abilityIds: List<String>,
        cap: Int,
    ): List<Issue> {
        val issues = mutableListOf<Issue>()
        if (abilityIds.size > cap.coerceAtMost(MAX_SLOTS)) issues += Issue.TOO_MANY
        val known = abilityIds.mapNotNull(CoopAbilityCatalogue::byId)
        if (known.size != abilityIds.size) issues += Issue.UNKNOWN_ABILITY
        if (abilityIds.toSet().size != abilityIds.size) issues += Issue.DUPLICATE_ABILITY
        if (known.count { it.tier == CoopTier.RESTRICTED } > MAX_RESTRICTED) issues += Issue.TOO_MANY_RESTRICTED
        return issues
    }
}
