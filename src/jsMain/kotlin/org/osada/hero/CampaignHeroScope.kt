package org.osada.hero

import org.osada.GameHolder
import org.osada.getCampaignPlayer
import org.osada.model.GameUnit

/**
 * Who may take part in the persistent **campaign hero system** -- the one eligibility rule every
 * hero entry point asks (`docs/design/combat-movement-heroes-weather-audit.md` §3.1).
 *
 * The owner's decisions it encodes:
 *
 * - heroes exist only in a campaign run. A standalone scenario, including a unit bought in one, uses
 *   the legacy leader path;
 * - only the campaign player's formations are heroes' formations. An AI purchase in a campaign keeps
 *   its ordinary legacy leader but never enters the player's roster;
 * - eligibility follows the existing campaign-player enrolment policy, which includes eligible
 *   non-core formations on the map (`CoreUnitListOperations.ensureFormationIds`). It is therefore
 *   an OWNER test, deliberately not `isCore`.
 *
 * A formation id on a unit is identity, not authorisation: a stale id carried in a save must not
 * bypass this, which is why callers ask here rather than testing `formationId` themselves.
 */
internal object CampaignHeroScope {
    /**
     * The campaign's human core is player id 0 -- the convention `Game.assignCampaignPlayerType`
     * enforces. Used as the fallback before `Game.campaignPlayer` has been assigned during a load,
     * and by the roster sweep, which runs before any player exists.
     */
    const val CAMPAIGN_PLAYER_ID = 0

    /** Whether a campaign run is active at all. */
    fun campaignActive(): Boolean = GameHolder.instance?.campaign != null

    private fun campaignPlayerId(): Int = GameHolder.instance?.getCampaignPlayer()?.id ?: CAMPAIGN_PLAYER_ID

    /** Whether formations owned by player [ownerId] may belong to the hero system right now. */
    fun ownerEligible(ownerId: Int): Boolean = campaignActive() && ownerId == campaignPlayerId()

    /** Whether [unit] may be enrolled in, or act through, the hero system. */
    fun unitEligible(unit: GameUnit?): Boolean =
        unit != null && !unit.isTemporaryBorrowed && ownerEligible(ownerOf(unit))

    /** [unit]'s owning player id. `owner` is authoritative; a unit that has only been handed its
     *  [GameUnit.player] so far (mid-construction) is judged by that player instead of by -1. */
    private fun ownerOf(unit: GameUnit): Int = if (unit.owner >= 0) unit.owner else unit.player?.id ?: -1

    /**
     * Whether a roster record is an ACTIVE formation of the campaign player. An inactive record is
     * kept -- its history and any biography that references it survive -- but it takes no part in
     * acquisition, trait resolution, transfers or the serving-commanders UI.
     */
    fun formationActive(formation: CoreFormation): Boolean =
        !formation.inactive && formation.ownerId == campaignPlayerId()

    /** The sweep's own test, which cannot consult a live player: see [CAMPAIGN_PLAYER_ID]. */
    fun belongsToCampaignPlayer(formation: CoreFormation): Boolean = formation.ownerId == CAMPAIGN_PLAYER_ID
}
