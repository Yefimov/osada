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
    /** The mark this commander's units wear on the map; empty means the seat number. */
    val symbol: String = "",
) {
    /** The seat number, shown wherever no symbol was chosen. */
    val mark: String get() = "${seat + 1}"

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

/**
 * The marks a commander may wear instead of a seat number: a closed set of small filled glyphs on a
 * 24x24 grid, drawn the same way on the map canvas (`Path2D`) and in the interface (inline SVG).
 * The id is what travels and is stored; anything not listed is refused.
 */
object CoopSeatSymbols {
    /** id -> SVG path data (filled, even-odd), 24x24 box. */
    val PATHS: Map<String, String> =
        linkedMapOf(
            "tank" to "M2 15h20v3a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2z M6 10h9v4H6z M15 11h8v2h-8z",
            "artillery" to "M3 18a3 3 0 1 0 6 0a3 3 0 1 0-6 0z M7.5 15.5L19.6 4.4l1.6 1.8L9.4 17.3z M10 18h11v2H10z",
            "plane" to
                "M11 2h2l1 7 8 4v2l-8-2-.5 5 2.5 2v1.5L12 20.5 8 21.5V20l2.5-2L10 13l-8 2v-2l8-4z",
            "blast" to
                "M12 1l2.2 6.2L20 4l-2.6 5.8L23 12l-5.6 2.2L20 20l-5.8-2.6L12 23l-2.2-5.6L4 20l2.6-5.8L1 12" +
                "l5.6-2.2L4 4l5.8 3.2z",
            "infantry" to "M3 15a9 8 0 0 1 18 0z M1 15h22v2.5H1z",
            "ship" to "M1 15h22l-3.5 5h-15z M6 10h10v4H6z M10 4h2v6h-2z",
            "rocket" to "M12 1c3 3 4 7 3.2 12l2.3 3v4l-3.5-2h-4l-3.5 2v-4l2.3-3C8 8 9 4 12 1z",
            "star" to "M12 2l2.9 6.9 7.1.6-5.4 4.7 1.6 7.3L12 17.8l-6.2 3.7 1.6-7.3L2 9.5l7.1-.6z",
            "shield" to "M12 2l8 3v6c0 5-3.5 9-8 11-4.5-2-8-6-8-11V5z",
            "skull" to
                "M12 2a8 8 0 0 0-8 8c0 3 1.5 5 3 6v4h10v-4c1.5-1 3-3 3-6a8 8 0 0 0-8-8z " +
                "M7 10.5a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0-4.4 0z M12.6 10.5a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0-4.4 0z",
        )

    val ALL: List<String> = PATHS.keys.toList()

    fun isValid(symbol: String): Boolean = symbol.isEmpty() || symbol in PATHS

    fun pathOf(symbol: String): String? = PATHS[symbol]

    /** Inline SVG for a known id. The markup is built only from the constants above, never from input. */
    fun svg(symbol: String): String? =
        pathOf(symbol)?.let {
            "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\"><path fill=\"currentColor\" " +
                "fill-rule=\"evenodd\" d=\"$it\"/></svg>"
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
