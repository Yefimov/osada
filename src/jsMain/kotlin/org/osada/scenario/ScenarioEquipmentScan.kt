package org.osada.scenario

import org.osada.GameHolder
import org.osada.model.GameUnit
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Every equipment id a scenario document references, collected BEFORE any equipment is fetched.
 *
 * [org.osada.model.EquipmentCountryIndex] explains why this is needed at all: the merged country
 * file an equipment record lives in is not derivable from the player list or from a unit's `flag`,
 * so the loader has to be told which ids the scenario actually uses.
 *
 * The scan is document-wide on purpose. `getElementsByTagName("unit")` reaches the three places a
 * scenario may place a formation -- a `<hex>`, a `<reinforce><at>` wave and an `<events><spawn>`
 * block -- in one pass, and all three are parsed by the same [ScenarioUnitParser], so a fourth
 * placement site would be covered the moment it starts writing `<unit>` too.
 */
internal object ScenarioEquipmentScan {
    /** The `<unit>` attributes that hold an equipment id. `id` is the formation itself; `transport`
     *  its organic carrier; `carrier` the container it deploys from ([GameUnit.carrier]). */
    private val UNIT_EQUIPMENT_ATTRIBUTES = listOf("id", "transport", "carrier")

    /**
     * Authored ids from [doc], plus the campaign core being carried into this scenario.
     *
     * The carried core matters because those formations are deployed into the new map after
     * loading and are never written in its XML: a Republican Spanish rifle battalion carried into a
     * Soviet scenario is a real case, and its record has to be fetched by ID for the same reason
     * Tiganca's garrison does.
     */
    fun collect(doc: Document): Set<Int> {
        val ids = mutableSetOf<Int>()
        collectFromUnitElements(doc, ids)
        collectFromHexElements(doc, ids)
        collectFromCarriedCore(ids)
        return ids
    }

    private fun collectFromUnitElements(
        doc: Document,
        into: MutableSet<Int>,
    ) {
        val unitElements = doc.getElementsByTagName("unit")
        for (i in 0 until unitElements.length) {
            val el = unitElements.item(i) as? Element ?: continue
            UNIT_EQUIPMENT_ATTRIBUTES.forEach { attribute ->
                el.getAttribute(attribute)?.toIntOrNull()?.let { if (it > 0) into.add(it) }
            }
        }
    }

    /**
     * `trigequip` -- the equipment id OG's trigger actions 8 and 9 hand the player
     * ([org.osada.rules.TriggerHexes]). It is an authored reference like any other, and a trigger
     * that fires into an unloaded country file would raise the same empty formation.
     */
    private fun collectFromHexElements(
        doc: Document,
        into: MutableSet<Int>,
    ) {
        val hexElements = doc.getElementsByTagName("hex")
        for (i in 0 until hexElements.length) {
            val el = hexElements.item(i) as? Element ?: continue
            el.getAttribute("trigequip")?.toIntOrNull()?.let { if (it > 0) into.add(it) }
        }
    }

    private fun collectFromCarriedCore(into: MutableSet<Int>) {
        val carriedPlayer = GameHolder.instance?.savedCampaignPlayer ?: return
        carriedPlayer.getCoreUnitList().forEach { unit -> addUnitEquipment(unit, into) }
    }

    private fun addUnitEquipment(
        unit: GameUnit,
        into: MutableSet<Int>,
    ) {
        listOfNotNull(unit.eqid, unit.transport?.eqid, unit.carrier)
            .forEach { if (it > 0) into.add(it) }
        unit.hangar.forEach { addUnitEquipment(it, into) }
    }
}
