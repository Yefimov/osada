package org.osada.rules

import org.osada.LeaderType
import org.osada.UnitClass
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.Leaders
import org.osada.model.TerrainEx

/**
 * What a formation's entrenchment is worth, owned in one place so the combat pipeline and the
 * unit-card tooltip read the same numbers.
 *
 * [AttackCalculation.applyEntrenchment] adds [defenderBonus] to the defender's defence; the card
 * explains the same value, so a tooltip can never promise a bonus combat does not grant (or hide one
 * it does).
 */
internal object EntrenchmentDefence {
    /** How far above its terrain's baseline a formation can dig (`GameUnit.entrench`). */
    const val MAX_ABOVE_TERRAIN = 5

    /** The defender's entrenchment as it stands against one attacker. */
    data class Against(
        /** The level the defender currently holds. */
        val level: Int,
        /** Defence actually added: 0 when bypassed, `2 * level` when [doubled]. */
        val bonus: Int,
        /** Infantry in close terrain attacked by a vehicle counts its entrenchment twice. */
        val doubled: Boolean,
        /** The attacker ignores it (`Ignore entrench`, Bunker Buster, Infiltration / Street Fighter). */
        val bypassed: Boolean,
    )

    /**
     * The terrain floor [unit] snaps back to at the start of a new turn on [hex]: the efile's
     * `TerrainEx` baseline, raised to a crater's cover, plus a Fast Entrench attachment.
     */
    fun terrainBaseline(
        unit: GameUnit,
        hex: Hex,
    ): Int {
        // A crater is cover the shells dug for you (`rules/Craters`, OSADA's own rule): it raises the
        // FLOOR the terrain gives, so it can never beat entrenching and can never be farmed by
        // shelling your own line. `maxOf`, not `+`, is the whole of that design.
        val terrainFloor = maxOf(TerrainEx.baseEntrenchment(hex.terrain), Craters.entrenchmentFloor(hex))
        return terrainFloor + Attachments.bonus(unit, Attachments.SLOT_FAST_ENTRENCH)
    }

    /**
     * The defence [defender]'s entrenchment adds against [attacker] -- bypassed by Infiltration
     * Tactics (unless the defender has Ferocious Defense) and by whatever
     * [CombatResolver.isEntrenchmentIntact] names, and double-counted for a vehicle attacking
     * entrenched infantry in close terrain.
     */
    fun defenderBonus(
        attacker: GameUnit,
        defender: GameUnit,
        context: AttackCalculation.CombatContext,
        closeCombat: Boolean,
    ): Against {
        val infiltrated =
            Leaders.unitHasLeader(attacker, LeaderType.INFILTRATION_TACTICS) &&
                !Leaders.unitHasLeader(defender, LeaderType.FEROCIOUS_DEFENSE)
        val bypassed = !context.entrenchmentIntact || infiltrated
        val single = if (bypassed) 0 else defender.entrenchment
        val doubled =
            context.defenderData.uclass == UnitClass.INFANTRY.value &&
                UnitPredicates.isCloseCombatTerrain(context.dTerrain) &&
                !closeCombat &&
                context.attackerData.uclass > UnitClass.INFANTRY.value &&
                context.attackerData.uclass < UnitClass.GROUND_TRANSPORT.value
        return Against(defender.entrenchment, if (doubled) single * 2 else single, doubled && single > 0, bypassed)
    }

    /** [defenderBonus] for the two units where they stand now, or `null` when either is off the map. */
    fun forecast(
        attacker: GameUnit,
        defender: GameUnit,
    ): Against? {
        val context = AttackCalculation.resolveCombatContext(attacker, defender) ?: return null
        return defenderBonus(attacker, defender, context, CloseCombat.applies(context))
    }

    /**
     * [RuggedCombat.ruggedDefenceOdds] for the two units where they stand now: the chance combat
     * would roll against, drawing nothing, or `null` when this pair cannot raise a rugged defence
     * at all (or either unit is off the map).
     */
    fun ruggedOdds(
        attacker: GameUnit,
        defender: GameUnit,
    ): Double? =
        AttackCalculation.resolveCombatContext(attacker, defender)?.let {
            RuggedCombat.ruggedDefenceOdds(attacker, defender, it)
        }
}
