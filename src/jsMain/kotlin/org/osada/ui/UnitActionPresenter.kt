package org.osada.ui

import org.osada.i18n.GameText
import org.osada.i18n.I18n
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.TerrainEx
import org.osada.rules.ActionAvailability
import org.osada.rules.ActionBlock
import org.osada.rules.ActionEffect
import org.osada.rules.ActionEffectKind
import org.osada.rules.SupplyContext
import org.osada.rules.SupplyContextRules
import org.osada.rules.SupplySource
import org.osada.rules.UnitActionId
import org.osada.ui.keyboard.CommandCatalog

/**
 * Localizes one [ActionAvailability] into the chip label and the anchored explanation panel
 * (`docs/design/action-affordances-and-objectives.md` §4).
 *
 * Every number here comes from the availability record, which took it from the command's own rule
 * helper -- nothing is recomputed, so the tooltip cannot claim a value the command will not
 * deliver.
 */
internal object UnitActionPresenter {
    /** One tooltip line: a green effect, a red block, or a neutral rule note. */
    data class Line(
        val kind: String,
        val text: String,
    )

    data class View(
        val label: String,
        val glyph: String,
        val status: String,
        val description: String,
        val lines: List<Line>,
        val enabled: Boolean,
        /** Locale-independent key cap for this action, or `null` when it has no binding. */
        val keyCap: String? = null,
    ) {
        /** Flat text for `aria-describedby`, so the panel's content is not sight-only. */
        fun semanticText(): String = (listOf(label, status, description) + lines.map { it.text }).joinToString(". ")
    }

    /** Effects that state a cost or a caveat rather than a gain -- rendered as neutral notes. */
    private val NEUTRAL_EFFECTS =
        setOf(ActionEffectKind.ENDS_UNIT_ACTION, ActionEffectKind.LIMBER_TOGGLE_FREE)

    private const val GOOD = "good"
    private const val BAD = "bad"
    private const val DIM = "dim"

    fun view(
        availability: ActionAvailability,
        unit: GameUnit,
        map: GameMap,
        asleep: Boolean,
    ): View {
        val variant = variantKey(availability.action, unit, asleep)
        return View(
            label = I18n.t("unit_info.action.$variant.label"),
            glyph = UIBuilder.unitContextButtons[availability.action.id] ?: "",
            status =
                I18n.t(
                    if (availability.enabled) {
                        "unit_info.action.status.available"
                    } else {
                        "unit_info.action.status.unavailable"
                    },
                ),
            description = I18n.t("unit_info.action.$variant.help"),
            lines =
                blockLines(availability.reasons) +
                    effectLines(availability.effects) +
                    ruleNotes(availability, map, unit),
            enabled = availability.enabled,
            keyCap = keyCap(availability.action),
        )
    }

    /** The key cap the fixed command catalog declares for this action, so the panel and the F1 card
     *  can never advertise a key the router does not dispatch. */
    fun keyCap(action: UnitActionId): String? =
        CommandCatalog.unitActionFor
            .entries
            .firstOrNull { it.value == action.id }
            ?.let { CommandCatalog.byId(it.key)?.capLabel }

    /** The chip's label/help vary with the unit's state, not just the action id. */
    fun variantKey(
        action: UnitActionId,
        unit: GameUnit,
        asleep: Boolean,
    ): String =
        when {
            action == UnitActionId.MOUNT && unit.isMounted -> "dismount"
            action == UnitActionId.EMBARK && unit.carrier != 0 -> "disembark"
            action == UnitActionId.SLEEP && asleep -> "wake"
            else -> action.id
        }

    private fun blockLines(reasons: List<ActionBlock>): List<Line> =
        reasons.map { block ->
            val key = "unit_info.action.reason.${block.reason.name.lowercase()}"
            Line(BAD, I18n.t(key, mapOf("amount" to block.amount)))
        }

    private fun effectLines(effects: List<ActionEffect>): List<Line> =
        effects.map { effect ->
            Line(
                if (effect.kind in NEUTRAL_EFFECTS) DIM else GOOD,
                I18n.t(
                    "unit_info.action.effect.${effect.kind.name.lowercase()}",
                    mapOf("amount" to effect.amount, "detail" to effect.detail),
                ),
            )
        }

    /**
     * The neutral factor lines behind a supply/reinforcement percentage. Only the terms that
     * actually participated are rendered -- road and rail never both appear, and a zero ground
     * modifier is omitted rather than shown as "+0%" (§4 point 3).
     */
    private fun ruleNotes(
        availability: ActionAvailability,
        map: GameMap,
        unit: GameUnit,
    ): List<Line> {
        val usesSupply =
            availability.action == UnitActionId.RESUPPLY ||
                availability.action == UnitActionId.REINFORCE ||
                availability.action == UnitActionId.OVERSTRENGTH
        if (!usesSupply) return emptyList()
        return supplyFactorLines(SupplyContextRules.getSupplyContext(map, unit))
    }

    fun supplyFactorLines(context: SupplyContext): List<Line> {
        val lines = mutableListOf<Line>()
        when (context.source) {
            SupplySource.AIRFIELD_CARRIER -> lines += Line(DIM, I18n.t("unit_info.supply_factor.airfield"))
            SupplySource.NAVAL -> lines += Line(DIM, I18n.t("unit_info.supply_factor.naval"))
            SupplySource.NONE -> return lines
            SupplySource.GROUND -> lines += groundFactorLines(context)
        }
        return lines
    }

    private fun groundFactorLines(context: SupplyContext): List<Line> {
        val factor = context.terrainFactor ?: return emptyList()
        val lines = mutableListOf<Line>()
        lines +=
            Line(
                DIM,
                I18n.t(
                    "unit_info.supply_factor.terrain",
                    mapOf("terrain" to GameText.terrain(context.terrain), "value" to factor.basePercent),
                ),
            )
        if (factor.roadPercent != 0) {
            val key =
                if (factor.roadKind == TerrainEx.SupplyRoadKind.RAIL) {
                    "unit_info.supply_factor.rail"
                } else {
                    "unit_info.supply_factor.road"
                }
            lines += Line(DIM, I18n.t(key, mapOf("value" to signed(factor.roadPercent))))
        }
        if (factor.groundPercent != 0) {
            lines +=
                Line(
                    DIM,
                    I18n.t(
                        "unit_info.supply_factor.ground",
                        mapOf(
                            "ground" to GameText.ground(context.groundCondition),
                            "value" to signed(factor.groundPercent),
                        ),
                    ),
                )
        }
        if (context.adjacentEnemies > 0) {
            lines +=
                Line(
                    DIM,
                    I18n.plural(
                        "unit_info.supply_factor.enemies",
                        context.adjacentEnemies,
                        mapOf("divisor" to formatDivisor(context.adjacentEnemyDivisor)),
                    ),
                )
        }
        lines += Line(DIM, I18n.t("unit_info.supply_factor.total", mapOf("value" to context.efficiencyPercent)))
        return lines
    }

    /** `+20` / `-30`, so a modifier reads as a modifier rather than an absolute percentage. */
    private fun signed(value: Int): String = if (value > 0) "+$value" else value.toString()

    private fun formatDivisor(divisor: Double): String =
        if (divisor == divisor.toInt().toDouble()) divisor.toInt().toString() else divisor.toString()
}
