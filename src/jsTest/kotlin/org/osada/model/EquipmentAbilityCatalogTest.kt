package org.osada.model

import org.osada.UnitClass
import org.osada.UnitType
import org.osada.rules.UnitCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The abilities widened into `attr2`/`attrEx` on 2026-08-19 (`docs/og-fidelity-plan.md` §C) --
 * ASW submarine-attack eligibility, `Lasting Suppression`, and the purchase-window ability catalog
 * itself. `UnitCapabilities.hasAllWeather`/`hasNoInterceptAir`/RCN-OVR-AA badge coverage live in
 * their own existing test files (`AllWeatherCombatTest`, `AAInterceptionTest`,
 * `EquipmentMarkingsTest`) rather than being duplicated here.
 */
class EquipmentAbilityCatalogTest {
    // ---- ASW: attacking submarines ------------------------------------------------------------

    @Test
    fun onlyDestroyerAndTacticalBomberAttackSubmarinesByDefault() {
        val destroyer = EquipmentData().apply { uclass = UnitClass.DESTROYER.value }
        val tacBomber = EquipmentData().apply { uclass = UnitClass.TACTICAL_BOMBER.value }
        val infantry = EquipmentData().apply { uclass = UnitClass.INFANTRY.value }
        val submarine =
            EquipmentData().apply {
                uclass = UnitClass.SUBMARINE.value
                target = UnitType.SEA.value
            }

        Equipment.resetEquipment()
        Equipment.putEquipment(1, destroyer)
        Equipment.putEquipment(2, tacBomber)
        Equipment.putEquipment(3, infantry)
        Equipment.putEquipment(4, submarine)

        assertTrue(Equipment.canInitiateAttackOnUnitType(1, 4))
        assertTrue(Equipment.canInitiateAttackOnUnitType(2, 4))
        assertFalse(Equipment.canInitiateAttackOnUnitType(3, 4), "infantry has no way to reach a submarine")
    }

    /** OG's `ASW` (`SpecialEx` bit 61.4, `attrEx` bit 12), `Manual_OG-en.pdf` §7.2: "can attack
     *  submarines" -- a plain grant, the same shape `CAN Air Atk` already has for aircraft. */
    @Test
    fun aswGrantsSubmarineTargetingOutsideTheDefaultClasses() {
        val cruiser =
            EquipmentData().apply {
                uclass = UnitClass.CRUISER.value
                attrEx = 4096
            } // ASW
        val submarine =
            EquipmentData().apply {
                uclass = UnitClass.SUBMARINE.value
                target = UnitType.SEA.value
            }

        Equipment.resetEquipment()
        Equipment.putEquipment(1, cruiser)
        Equipment.putEquipment(2, submarine)

        assertTrue(Equipment.canInitiateAttackOnUnitType(1, 2), "ASW grants what the class alone would refuse")
    }

    // ---- Lasting Suppression: the equipment-level source alongside SHOCK_TACTICS ----------------

    /** OG's `Lasting Suppression` (`SpecialEx` bit 60.1, `attrEx` bit 1), wired the way
     *  `docs/og-fidelity-plan.md` §0.1 said it must be: "this unit's `hits` survive the victim's
     *  `unitEndTurn`" -- read through [GameUnit.hit]'s `lasting` parameter. */
    @Test
    fun lastingSuppressionEquipmentBitBehavesLikeTheLeaderTrait() {
        Equipment.resetEquipment()
        Equipment.putEquipment(1, EquipmentData().apply { attrEx = 2 }) // Lasting Suppression
        Equipment.putEquipment(2, EquipmentData())
        val attackerWithBit = GameUnit(1).apply { strength = 10 }
        val attackerWithout = GameUnit(2).apply { strength = 10 }

        assertTrue(UnitCapabilities.hasLastingSuppression(attackerWithBit))
        assertFalse(UnitCapabilities.hasLastingSuppression(attackerWithout))

        val defender = GameUnit(2).apply { strength = 10 }
        defender.hit(1, UnitCapabilities.hasLastingSuppression(attackerWithBit))
        assertEquals(1, defender.lastingHits, "the bit alone must produce a lasting hit")
    }

    // ---- The purchase-window catalog itself ---------------------------------------------------

    /** Every ruleset-gated ability visible, so a test about BITS is not also a test about keys. */
    private fun allGatesOn() =
        AbilityGates(minefields = true, engineering = true, counterBattery = true, extendedLos = true)

    /** The shipped default: none of the four optional rules on. */
    private fun allGatesOff() =
        AbilityGates(minefields = false, engineering = false, counterBattery = false, extendedLos = false)

    @Test
    fun aBareRecordCarriesNoAbilityLines() {
        assertEquals(emptyList(), EquipmentData().abilityCatalogKeys(allGatesOn()))
    }

    /**
     * `Drop Mines`/`Clear Mines`/`Air Drop Mines` do nothing at all unless `RuleKey.MINEFIELDS` is
     * on, and it is OFF in every profile except Open General Fidelity. Reported by the owner of a
     * line reading *"Drop Mines (Open General Fidelity ruleset only)"* on a unit in a ruleset with
     * no mines: *"Don't show it for rulesets that are not OG (I mean, that don't use mines!)."*
     */
    @Test
    fun minefieldAbilitiesAreHiddenWhenTheRulesetHasNoMinefields() {
        val sapper =
            EquipmentData().apply {
                attr = ATTR_MASK_DROP_MINES or ATTR_MASK_BRIDGE
                attrEx = ATTR_EX_MASK_CLEAR_MINES
            }
        assertEquals(
            listOf("equipment.ability.bridge"),
            sapper.abilityCatalogKeys(allGatesOff()),
            "with minefields off only the abilities that still do something are listed",
        )
        val withMines = sapper.abilityCatalogKeys(allGatesOn())
        assertTrue("equipment.ability.drop_mines" in withMines)
        assertTrue("equipment.ability.clear_mines" in withMines)
        assertTrue("equipment.ability.bridge" in withMines, "the unrelated ability is unaffected")
    }

    /** Every ability carries a badge code, and no two abilities share one — the badge row would be
     *  ambiguous otherwise, and its tooltip is the only thing distinguishing them. */
    @Test
    fun everyAbilityHasAUniqueBadgeCode() {
        val all =
            EquipmentData()
                .apply {
                    attr = -1
                    attr2 = -1
                    attrEx = -1
                }.abilityCatalog(allGatesOn())
        val badges = all.map { it.badge }
        assertEquals(badges.toSet().size, badges.size, "badge codes must be unique: $badges")
        assertTrue(badges.all { it.isNotBlank() && it.length <= 3 }, "badges stay short: $badges")
        // Until 2026-08-28 this asserted that BOTH tiers were represented -- that some ability was
        // still descriptive-only. `Carrier Deploy`, `No Need Station` and `Supply Unit` were the
        // last three, and §AA wired all of them, so the descriptive tier is now empty and the
        // assertion is the stronger one: every special OG can express is executed by a rule.
        //
        // If a future import decodes a NEW bit, this is the test that should fail first -- adding
        // it to `DESCRIPTIVE_ABILITIES` is legitimate, and it should be a deliberate act with this
        // line updated, not a silent regression of the milestone.
        val unwired = all.filterNot { it.wired }.map { it.key }
        assertTrue(all.all { it.wired }, "every catalogued ability is wired: $unwired")
    }

    @Test
    fun everyWiredBitProducesExactlyOneLine() {
        val data =
            EquipmentData().apply {
                attr = ATTR_MASK_DROP_MINES or ATTR_MASK_MECHANIZED or ATTR_MASK_NO_SURRENDER
                attrEx = ATTR_EX_MASK_CLEAR_MINES or ATTR_EX_MASK_ALL_WEATHER
            }
        val keys = data.abilityCatalogKeys(allGatesOn())
        assertEquals(keys.toSet().size, keys.size, "no ability should be listed twice")
        assertTrue("equipment.ability.drop_mines" in keys)
        assertTrue("equipment.ability.mechanized" in keys)
        assertTrue("equipment.ability.no_surrender" in keys)
        assertTrue("equipment.ability.clear_mines" in keys)
        assertTrue("equipment.ability.all_weather" in keys)
    }

    /** `Jet (Stealth)` must never claim an effect it doesn't have -- see this file's own header and
     *  `OG_ABILITY_AUDIT.md` §7.1.1. This test only locks that the key exists and is reachable;
     *  the honesty of the STRING it maps to is an i18n-content concern, not a Kotlin one. */
    @Test
    fun jetStealthIsCatalogedAsDescriptiveOnly() {
        val data = EquipmentData().apply { attrEx = 524288 } // Jet (Stealth)
        assertEquals(listOf("equipment.ability.jet_stealth"), data.abilityCatalogKeys(allGatesOn()))
    }
}
