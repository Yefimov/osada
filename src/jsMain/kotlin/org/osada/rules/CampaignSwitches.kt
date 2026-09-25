package org.osada.rules

import org.osada.GameHolder
import org.osada.model.GameUnit
import org.osada.model.Player

/**
 * OpenSuite's per-scenario campaign switches (`.xcam` record `@540`/`@541`), decoded by the owner's
 * controlled diff of 2026-09-25 and imported into the campaign record by
 * `tools/og-import/add_campaign_switches.py` (bit table: `cam_to_json.SWITCHES_540/541`).
 *
 * Read from the CURRENT scenario's record at the moment they matter, so none of them needs a save
 * key: a restored battle is back on the same record. `autorefit`/`autosupply` live in
 * [CampaignAutoRefit]; the rest are here. None is a ruleset key: each is the campaign author's own
 * instruction about the human's core army, in the same standing as a scenario's `.buy4` list.
 *
 * | key | OpenSuite label | deployed |
 * |---|---|---|
 * | `restartcore` | Restart (lose) Core units | `bn4s19`, the start of `camp6bn4`'s ELAS branch |
 * | `coremaincountry` | Set core units to same player's main country | `bn9s01`, `aljf_4` |
 * | `nopurchase` | Disable Purchase | four `bn4s*`, `aljf_4` |
 * | `noupgrade` | Disable Upgrade | `bn4s19`, `aljf_4`, `simpob_24/25` |
 * | `noscore` | Don't score | `ga4_8`, `rcampcho` |
 * | `nosellprotos` | Can't sell Protos | none -- imported only |
 * | `upgradeinsh` | Upgrade & OS in SHs | 69 records -- imported only: it PERMITS what OSADA already allows |
 */
internal object CampaignSwitches {
    fun restartsCore(record: dynamic): Boolean = isSet(record, "restartcore")

    fun adoptsMainCountry(record: dynamic): Boolean = isSet(record, "coremaincountry")

    fun skipsScore(record: dynamic): Boolean = isSet(record, "noscore")

    /** *"Disable Purchase"*: the campaign's human may buy nothing in this battle. */
    fun purchaseForbidden(player: Player?): Boolean = purchaseForbiddenBy(current(), humanId(), player?.id)

    /**
     * *"Disable Upgrade"* -- OG's wording on the author's campaign page is *"disable upgrading units
     * at initial HQ"*. OSADA has no separate HQ screen; the reserve tray is where the core waits
     * before it deploys, so the ban covers a core formation while it is still undeployed. Once on
     * the map it may upgrade as usual, as it could in OG after leaving the HQ.
     */
    fun upgradeForbidden(unit: GameUnit): Boolean = upgradeForbiddenBy(current(), humanId(), unit)

    /**
     * The two per-battle purchase LIMITS, as opposed to what may be bought: OG's purchase cap and
     * *"Disable Purchase"*. One call so `Player.buyUnit` stays inside detekt's complexity budget.
     */
    fun purchaseLimitsAllow(player: Player): Boolean = PurchaseCap.allows(player) && !purchaseForbidden(player)

    internal fun purchaseForbiddenBy(
        record: dynamic,
        humanId: Int?,
        playerId: Int?,
    ): Boolean = humanId != null && playerId == humanId && isSet(record, "nopurchase")

    internal fun upgradeForbiddenBy(
        record: dynamic,
        humanId: Int?,
        unit: GameUnit,
    ): Boolean = unit.isCore && !unit.isDeployed && unit.owner == humanId && isSet(record, "noupgrade")

    private fun humanId(): Int? = GameHolder.instance?.campaignPlayer?.id

    private fun current(): dynamic = GameHolder.instance?.campaign?.getCurrentScenario()

    private fun isSet(
        record: dynamic,
        key: String,
    ): Boolean = record != null && record[key] == true
}
