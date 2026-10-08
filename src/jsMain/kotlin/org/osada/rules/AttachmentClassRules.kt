package org.osada.rules

import org.osada.LeaderType
import org.osada.UnitClass
import org.osada.entrenchRateFor
import org.osada.model.Equipment
import org.osada.model.GameUnit
import org.osada.model.Leaders
import org.osada.model.ignoresEntrenchment
import org.osada.model.isBridge

/**
 * Which attachments a formation's CLASS may take -- one table for every efile.
 *
 * **Where the table comes from (measured 2026-09-26).** OG stores eligibility per equipment record,
 * in `equip.xeqa` (a version-6 file: `u16` version, `u16` count, then one `u16` per record where bit
 * N permits `attach_N`). Four installed efiles ship one. Grouped by class, three of them (LXF,
 * BASEKORP, KAISER) are exactly OG's pre-v6 fallback rule (A) from `EFILE_NOKORP/equip.cfg` --
 * *"Bridging, FastEntrench and Forward Observer will be disabled for air units and naval units"* --
 * while ATOMIC's author curated per record (aircraft rarely take Fuel Pods, warships rarely Bunker
 * Buster, artillery rarely Air Defense).
 *
 * The owner chose a common rule for all content over importing the masks, so this table is their
 * INTERSECTION: a slot is forbidden to a class if any of the four efiles forbids it to most of that
 * class's records. Cruisers, which ATOMIC does not field, follow the other warships so the navy
 * reads as one rule. Rules (B) and (C) of the same fallback are kept: a unit that already is a
 * bridge cannot buy Bridging, and only a sapper may buy Fast Builder.
 *
 * The table gates what can be BOUGHT. An attachment an author fitted in a scenario stays fitted.
 *
 * **Nor is an attachment sold that would give this formation nothing** ([addsSomething],
 * 2026-09-27, owner's report): Bunker Buster to an engineer that already ignores entrenchment,
 * Fast Entrench to one that cannot dig in,
 * Fuel Pods or Support to one with no fuel or ammunition to extend. OG's own rule (B) is the same
 * idea for Bridging, and the reason is the one it gives -- the purchase would just waste prestige.
 */
internal object AttachmentClassRules {
    /** Rule (A) plus ATOMIC's Fuel Pods restriction -- every aircraft class. */
    private val AIRCRAFT =
        setOf(
            Attachments.SLOT_BRIDGING,
            Attachments.SLOT_FORWARD_OBSERVER,
            Attachments.SLOT_FAST_ENTRENCH,
            Attachments.SLOT_FUEL_PODS,
        )

    /** The aircraft set plus Bunker Buster -- every ship class. */
    private val SHIPS = AIRCRAFT + Attachments.SLOT_BUNKER_BUSTER

    private val FORBIDDEN_BY_CLASS: Map<Int, Set<Int>> =
        mapOf(
            UnitClass.FORTIFICATION.value to
                setOf(
                    Attachments.SLOT_BRIDGING,
                    Attachments.SLOT_FORWARD_OBSERVER,
                    Attachments.SLOT_FAST_ENTRENCH,
                    Attachments.SLOT_BUNKER_BUSTER,
                    Attachments.SLOT_FUEL_PODS,
                    Attachments.SLOT_FAST_SPEED,
                ),
            UnitClass.GROUND_TRANSPORT.value to setOf(Attachments.SLOT_FORWARD_OBSERVER),
            UnitClass.ARTILLERY.value to setOf(Attachments.SLOT_AIR_DEFENSE, Attachments.SLOT_BRIDGING),
            UnitClass.AIR_DEFENCE.value to setOf(Attachments.SLOT_BRIDGING, Attachments.SLOT_BUNKER_BUSTER),
            UnitClass.FIGHTER.value to AIRCRAFT,
            UnitClass.TACTICAL_BOMBER.value to AIRCRAFT,
            UnitClass.LEVEL_BOMBER.value to AIRCRAFT,
            UnitClass.AIR_TRANSPORT.value to AIRCRAFT,
            UnitClass.SUBMARINE.value to SHIPS,
            UnitClass.DESTROYER.value to SHIPS,
            UnitClass.BATTLESHIP.value to SHIPS,
            UnitClass.CARRIER.value to SHIPS,
            UnitClass.NAVAL_TRANSPORT.value to SHIPS,
            UnitClass.BATTLE_CRUISER.value to SHIPS,
            UnitClass.CRUISER.value to SHIPS,
            UnitClass.LIGHT_CRUISER.value to SHIPS,
        )

    /**
     * Whether [unit] may buy [slotNumber]. Read on the REAL record (`unitData(true)`): a towed gun
     * riding its tractor is still artillery, and the attachment belongs to the gun.
     */
    fun allows(
        unit: GameUnit,
        slotNumber: Int,
    ): Boolean {
        val uclass = unit.unitData(true).uclass
        val byClass = slotNumber !in FORBIDDEN_BY_CLASS[uclass].orEmpty()
        return byClass &&
            addsSomething(unit, slotNumber) &&
            when (slotNumber) {
                // Rule (B): a bridge buying Bridging would not stack a second one.
                Attachments.SLOT_BRIDGING ->
                    !Equipment.isBridge(unit.eqid) && !Leaders.unitHasLeader(unit, LeaderType.BRIDGING)
                // Rule (C): only units having Sapper (Build/Repair) special can use Fast Builder.
                Attachments.SLOT_FAST_BUILDER -> Engineering.isSapper(unit)
                else -> true
            }
    }

    /**
     * Whether [slotNumber] would change anything for [unit] -- read on the formation's own record
     * (`unitData(true)`), since that is what the attachment belongs to:
     *
     *  - Bunker Buster is the `Ignore entrench` ability (`CombatResolver.isEntrenchmentIntact` reads
     *    the two as one), so an engineer already has it;
     *  - Air Defense adds to an air attack, which a formation with none never makes or returns
     *    (`AttackEligibility.canFire` needs `airatk > 0`);
     *  - Fast Entrench raises the entrenchment a formation digs to, and some classes never dig;
     *  - Fuel Pods and Support extend fuel and ammunition, and there is nothing to extend at zero;
     *  - Special Munition sharpens the unit's own fire, which needs an attack value to fire with.
     */
    private fun addsSomething(
        unit: GameUnit,
        slotNumber: Int,
    ): Boolean {
        val data = unit.unitData(true)
        return when (slotNumber) {
            Attachments.SLOT_BUNKER_BUSTER -> !Equipment.ignoresEntrenchment(unit.eqid)
            // Always useful since 2026-10-08: it adds air DEFENCE as well as attack, and lets a
            // formation with no air attack engage aircraft (`AirDefenceAttachmentGrant`).
            Attachments.SLOT_AIR_DEFENSE -> true
            Attachments.SLOT_FAST_ENTRENCH -> entrenchRateFor(data.uclass) > 0
            Attachments.SLOT_FUEL_PODS -> data.fuel > 0
            Attachments.SLOT_SUPPORT_AMMO -> data.ammo > 0
            // Lowers the firing unit's kill threshold, so a formation that never fires gains nothing.
            Attachments.SLOT_SPECIAL_MUNITION ->
                data.softatk > 0 || data.hardatk > 0 || data.airatk > 0 || data.navalatk > 0
            else -> true
        }
    }
}
