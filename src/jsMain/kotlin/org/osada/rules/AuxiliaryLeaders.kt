package org.osada.rules

import org.osada.GameHolder
import org.osada.getCampaignPlayer
import org.osada.model.EfileConfig
import org.osada.model.GameUnit

/**
 * OG's `noldr_auxunits` — auxiliary formations do not produce leaders in a campaign.
 *
 * > *"Default =0. Set to 1 to avoid P1 aux units to get a leader (when playing campaigns)"* —
 * > `EFILE_NOKORP/equip.cfg`
 *
 * `eqp-basekorp` is the one shipped efile that sets it.
 *
 * **"P1 aux units" is read as the campaign player's NON-CORE formations**, which is what an
 * auxiliary is in this codebase ([GameUnit.isCore]) and what the parenthetical *"when playing
 * campaigns"* points at: a core formation is the one that follows the player from scenario to
 * scenario and is worth investing a commander in, an auxiliary is lent for one battle and left
 * behind. Outside a campaign there are no auxiliaries and the key does nothing, which is the
 * comment's own scope.
 *
 * Applied in [org.osada.model.Leaders.generateLeader], the single place a legacy leader is minted —
 * so combat promotion, the full-experience grant and a scenario's own authored leaders all honour
 * it together, exactly as `Cannot get a leader` does.
 */
object AuxiliaryLeaders {
    /**
     * Whether [unit] is refused a leader because it is an auxiliary in a campaign under an efile
     * that asked for this.
     *
     * False for every efile that says nothing, which is nine of the ten shipped ones, and false in
     * every standalone scenario regardless of efile.
     */
    fun refuses(unit: GameUnit): Boolean {
        val holder = if (EfileConfig.flag("noldr_auxunits", false)) GameHolder.instance else null
        val campaignPlayer = holder?.takeIf { it.campaign != null }?.getCampaignPlayer()
        return campaignPlayer != null && unit.owner == campaignPlayer.id && !unit.isCore
    }
}
