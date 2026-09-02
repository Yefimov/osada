package org.osada.scenario

import org.osada.GameHolder
import org.osada.difficultyModifiers
import org.osada.model.Equipment
import org.osada.model.FrontFactionSlot
import org.osada.model.Player
import org.osada.model.addPlayer
import org.osada.model.addPlayersEquipment
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * [ScenarioLoader]'s `<player>` element parser and the equipment/reinforcement/hex parsing it
 * kicks off once player equipment is loaded. Split out purely to keep [ScenarioLoader] within
 * the project's function-count/class-size limits -- not expected to be called from elsewhere.
 */
internal object ScenarioPlayerParser {
    fun parse(
        scenario: Scenario,
        doc: Document,
    ) {
        val playerElements = doc.getElementsByTagName("player")
        val minTurnPrestige = resolveMinTurnPrestige()
        val players = mutableListOf<Player>()
        for (i in 0 until playerElements.length) {
            val el = playerElements.item(i) ?: continue
            players.add(parsePlayerElement(el, minTurnPrestige))
        }

        addCarryOverEquipmentCountries(players)
        Equipment.addPlayersEquipment(players) {
            players.forEach { scenario.map.addPlayer(it) }
            ScenarioReinforcementParser.parse(scenario, doc)
            ScenarioEventParser.parse(scenario, doc)
            ScenarioHexParser.parse(scenario, doc)
            scenario.isLoaded = true
            scenario.onLoadFinished()
        }
    }

    /**
     * Equipment is stored in country-split JSON files. A campaign may move a persistent formation
     * between theatres whose scenario player lists no longer mention that formation's equipment
     * country (for example Republican Spanish infantry carried into a Soviet scenario).
     *
     * This runs before [Equipment.addPlayersEquipment] clears the previous scenario's equipment
     * map, so the stable carried eqids can still tell us which country files the next scenario must
     * keep loaded. The unit flag is also retained as a tolerant fallback for older saves.
     */
    private fun addCarryOverEquipmentCountries(players: List<Player>) {
        val carriedPlayer = GameHolder.instance?.savedCampaignPlayer ?: return
        val nextPlayer = players.firstOrNull { it.id == carriedPlayer.id } ?: return

        carriedPlayer.getCoreUnitList().forEach { unit ->
            val equipmentIds =
                listOfNotNull(
                    unit.eqid,
                    unit.transport?.eqid,
                    unit.carrier.takeIf { it > 0 },
                )
            val countries =
                equipmentIds
                    .mapNotNull(Equipment::getEquipment)
                    .map { it.country } +
                    listOf(unit.flag)

            countries.filter { it > 0 }.forEach { country ->
                if (country !in nextPlayer.supportCountries) nextPlayer.supportCountries.add(country)
            }
        }
    }

    private fun resolveMinTurnPrestige(): Int {
        val campaign = GameHolder.instance?.campaign ?: return 0
        val turnPrestigeRatio = difficultyModifiers[campaign.difficulty]?.turnPrestige ?: 0.0
        return kotlin.math.round(campaign.startprestige * turnPrestigeRatio).toInt()
    }

    /**
     * OG's three non-organic transport POOLS, and whether the scenario authored them at all.
     *
     * The attribute is the pool SIZE -- OG's own wording, changelog 0.90.42.2 -- so it seeds the
     * ceiling as well as the count that is free right now (`model/TransportPools`).
     *
     * [Player.transportPoolsAuthored] is PRESENCE, not value. An OG scenario writes all three
     * attributes even when the pool is zero; a Panzer Marshal scenario writes none. Only the first
     * of those means "air and naval transports come from the pool and are not for sale"
     * (`rules/FrontsAndFactions`).
     */
    private fun applyTransportPools(
        el: Element,
        player: Player,
    ) {
        player.airTransports = el.getAttribute("airtrans")?.toIntOrNull() ?: 0
        player.navalTransports = el.getAttribute("navaltrans")?.toIntOrNull() ?: 0
        player.railTransports = el.getAttribute("railtrans")?.toIntOrNull() ?: 0
        player.transportPoolsAuthored =
            el.hasAttribute("airtrans") ||
            el.hasAttribute("navaltrans") ||
            el.hasAttribute("railtrans")
        player.airTransportsMax = player.airTransports
        player.navalTransportsMax = player.navalTransports
        player.railTransportsMax = player.railTransports
    }

    /**
     * The three authored limits on what this player may acquire, all read the same way: an ABSENT
     * attribute is unrestricted.
     *
     * * `purchasecap` -- OG's cap on NET-NEW formations (`.xscn` player record `+35`), already gated
     *   on `opt_purchase_cap` by the importer. **An attribute of "0" is not the absent case**: it is
     *   the author forbidding growth while still allowing losses to be replaced
     *   (`rules/PurchaseCap`).
     * * `ff` -- Fronts and Factions as the scenario stores them, `country:fronts:factions` per OG
     *   slot. `add_fronts_factions.py` writes nothing for a player whose every mask is zero, because
     *   a zero mask is OG's own wildcard (`rules/FrontsAndFactions`).
     * * `buylist` -- the same mechanic as OpenSuite RESOLVES it, a `.buy4` whitelist layered on top.
     *   `add_purchase_lists.py` never writes an empty list, so a present-but-empty attribute cannot
     *   occur and an empty parse is read as absent rather than as "may buy nothing".
     *
     * **Each field is filled only when the player still carries the unauthored value.** At scenario
     * load that is every one of them, so this is exactly the old unconditional assignment; the
     * guard exists for [AuthoredOptionsBackfill], which re-reads the XML for a save written before
     * these were serialized and must never overwrite what the save itself carried.
     */
    internal fun applyAuthoredPurchaseLimits(
        el: Element,
        player: Player,
    ) {
        if (player.purchaseCap == null) {
            player.purchaseCap = el.getAttribute("purchasecap")?.toIntOrNull()
        }
        if (player.frontFactionSlots.isEmpty()) {
            player.frontFactionSlots = FrontFactionSlot.parse(el.getAttribute("ff"))
        }
        if (player.purchaseList == null) {
            player.purchaseList =
                el
                    .getAttribute("buylist")
                    ?.split(",")
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    ?.toSet()
                    ?.takeIf { it.isNotEmpty() }
        }
    }

    private fun parsePlayerElement(
        el: Element,
        minTurnPrestige: Int,
    ): Player {
        val player = Player()
        player.id = el.getAttribute("id")?.toIntOrNull() ?: 0
        player.side = el.getAttribute("side")?.toIntOrNull() ?: 0
        player.country = el.getAttribute("country")?.toIntOrNull() ?: 0
        applyTransportPools(el, player)
        // Already gated on the scenario's own `opt_default_xp` / `opt_allow_default_str` switch by
        // the importer, so 0 here means "not authored" and never "the author chose zero".
        player.defaultExperience = el.getAttribute("defaultxp")?.toIntOrNull() ?: 0
        player.defaultStrength = el.getAttribute("defaultstr")?.toIntOrNull() ?: 0
        applyAuthoredPurchaseLimits(el, player)
        player.prestigePerTurn = el
            .getAttribute("turnprestige")
            ?.split(", ")
            ?.map { value ->
                val v = value.toIntOrNull() ?: 0
                if (v < minTurnPrestige) minTurnPrestige else v
            }?.toMutableList() ?: mutableListOf()
        player.prestige = player.prestigePerTurn.getOrElse(0) { 0 }
        // Split on the comma alone and trim, NOT on ", ". `turnprestige` is written with a space
        // after each comma, `support` is not (`support="251,298,0,0"`) — so splitting both the same
        // way yielded ONE token, "251,298,0,0", which parses to null and was then dropped by the
        // `> 0` filter. Every scenario in the register therefore loaded with NO support countries.
        //
        // The damage is invisible until a scenario actually places a unit from a support nation:
        // its equipment file is never fetched, `Equipment.getEquipment(eqid)` returns an empty
        // EquipmentData, and the unit draws with no icon, no name (the unit card falls back to its
        // numeric id — "27th") and movpoints 0, so it cannot be moved. Reported 2026-08-16 against
        // "Victory at Kampala" (8,29): a UNLA Militia from country 250, one of Tanzania's two
        // declared support nations.
        player.supportCountries =
            el
                .getAttribute("support")
                ?.split(",")
                ?.map { it.trim().toIntOrNull() ?: 0 }
                ?.filter { it > 0 }
                ?.toMutableList()
                ?: mutableListOf()
        return player
    }
}
