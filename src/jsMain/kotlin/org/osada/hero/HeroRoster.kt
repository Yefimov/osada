package org.osada.hero

/**
 * The hero and formation roster for ONE campaign run.
 *
 * Lifecycle mirrors [org.osada.campaign.CampaignNarrativeState] exactly, because it solves the
 * same problem: created empty by `newCampaign`, mutated through this class, snapshotted into the
 * save's `campaign` block, and restored from it. Saves written before this feature have no such
 * block and restore to an empty roster.
 *
 * Keyed by the raw string inside [FormationId] / [HeroId] rather than by the wrapper, so a lookup
 * cannot silently miss because two equal ids were boxed differently.
 *
 * Definition and state are stored side by side but separately — see [HeroModel] for why identity
 * and career have different lifetimes.
 */
internal class HeroRoster {
    private val formations = mutableMapOf<String, CoreFormation>()
    private val definitions = mutableMapOf<String, HeroDefinition>()
    private val states = mutableMapOf<String, HeroState>()

    /**
     * Campaign-wide drought counter (§7.2): eligible emergence failures since the last hero.
     * Rises on a failed eligible check, resets to 0 when one emerges. Campaign-scoped rather than
     * per-formation on purpose — the guarantee protects the *campaign* against long stretches
     * without a hero, so any qualifying formation can be the one it fires on.
     */
    var drought: Int = 0

    /**
     * The authored legendary hero reserved for this campaign (§6.4, §23), or null when none is
     * reserved or it has already appeared. [legendarySpawned] guards against re-reserving after the
     * early legendary has been consumed.
     */
    var reservedLegendary: String? = null
    var legendarySpawned: Boolean = false

    /** True when nothing has been recorded, so the save can omit the block entirely. */
    val isEmpty: Boolean
        get() =
            formations.isEmpty() &&
                definitions.isEmpty() &&
                drought == 0 &&
                reservedLegendary == null &&
                !legendarySpawned

    // ------------------------------------------------------------- formations

    fun formation(id: FormationId): CoreFormation? = formations[id.value]

    fun putFormation(formation: CoreFormation) {
        formations[formation.id.value] = formation
    }

    fun allFormations(): List<CoreFormation> = formations.values.toList()

    // ----------------------------------------------------------------- heroes

    fun definition(id: HeroId): HeroDefinition? = definitions[id.value]

    fun state(id: HeroId): HeroState? = states[id.value]

    fun allDefinitions(): List<HeroDefinition> = definitions.values.toList()

    /** Adds a hero and its opening career state. Overwrites any hero already under that id. */
    fun putHero(
        definition: HeroDefinition,
        state: HeroState,
    ) {
        definitions[definition.id.value] = definition
        states[definition.id.value] = state
    }

    /** Replaces career state in place. No-op for a hero that was never added. */
    fun updateState(state: HeroState) {
        if (definitions.containsKey(state.heroId.value)) {
            states[state.heroId.value] = state
        }
    }

    /**
     * The career state of the hero commanding [formationId], or null when the formation is
     * unknown, has no hero, or names a hero this build cannot resolve.
     */
    fun assignedHero(formationId: FormationId): HeroState? =
        formations[formationId.value]
            ?.assignedHeroId
            ?.let { states[it.value] }

    fun clear() {
        formations.clear()
        definitions.clear()
        states.clear()
        reservedLegendary = null
        legendarySpawned = false
    }
}
