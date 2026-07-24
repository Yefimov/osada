package org.osada.hero

/**
 * The player-facing payload of a commander casualty (design brief §11, §14) — the "risk of losing a
 * commander you care about" event. Built in the hero layer and drained by the UI after combat
 * resolution, the same pattern as [HeroEmergenceAnnouncement] and [HeroPromotionAnnouncement].
 *
 * Carries the [HeroCasualtyService.Disposition] rather than pre-rendered fate text so the presenter
 * resolves it through [HeroDisplay] and stays localization-ready (§29.20). [memorial] is the
 * restrained formation tradition a fallen commander leaves behind (§11.2), or null.
 */
data class HeroCasualtyAnnouncement(
    val heroName: String,
    val rankId: String,
    val formationName: String,
    val disposition: HeroCasualtyService.Disposition,
    val memorial: String?,
) {
    companion object {
        internal fun from(
            outcome: HeroCasualtyService.Outcome,
            formation: CoreFormation,
            definition: HeroDefinition,
            rankId: String,
            memorial: String?,
        ): HeroCasualtyAnnouncement =
            HeroCasualtyAnnouncement(
                heroName = definition.displayName,
                rankId = rankId,
                formationName = formation.displayName,
                disposition = outcome.disposition,
                memorial = memorial,
            )
    }
}
