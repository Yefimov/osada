package org.osada.rules

import org.osada.UnitClass
import org.osada.model.ATTR2_MASK_EVADE
import org.osada.model.EfileConfig
import org.osada.model.GameUnit
import org.osada.model.listKey

/**
 * Open General's **Evade**, built from the author's own current documentation
 * (`https://www.luis-guzman.com/OpenGen_Combat.html`), 2026-08-27.
 *
 * ### This is the third reading, and the first from a primary source
 *
 * The first two were built from the manual and from `equip.cfg`'s comments, and both were wrong the
 * same way: they treated the `Evade` special as the only gate. Luis Guzman's combat page states the
 * rule outright, and it has **two eligibility routes**:
 *
 * > *"Two categories can evade: classes defined by `class_evade`... and units with the "Evade"
 * > special attribute (as long as they're not sabotaged)."*
 * >
 * > When `class_evade` is undefined, *"only Submarines can evade"*, at **30%** — *"or 50% in
 * > PG2-Mode"*.
 *
 * So `class_evade` is not a probability table bolted onto the special: **it is itself a grant**. A
 * class with a non-zero column evades whether or not any of its records carry the bit. That is why
 * `EFILE_NOKORP`'s infantry column of 60 is not the absurdity it first looked like — it is exactly
 * what it says, subject to the modifiers below and to finding a hex to retreat into.
 *
 * **The two routes are scored differently**, which is what finally explains `EFILE_LUPO`: its
 * author sets `class_evade` for helicopters AND grants the special to all 531 of them, which is
 * redundant under any one-route reading. It is not redundant here.
 *
 * | | class route | `Evade` special route |
 * |---|---|---|
 * | base | the class's `class_evade` column | the same |
 * | ZOC | reduced by `zoc_evade` per ADDITIONAL adjacent enemy | the same |
 * | experience | `+5 × defender bars − 5 × attacker bars` | `+5 × defender bars` only |
 * | attacker is air | halved | not halved |
 * | defender is mounted | halved again | not halved |
 *
 * ### What this corrected, and what survived
 *
 * Survived: `class_evade` is indexed by OG's own class numbering ([OG_CLASS_OF]), `zoc_evade`
 * counts enemies ADDITIONAL to the attacker, and submarines evade without the bit.
 *
 * Corrected: the special is not the only gate; the fallback is **30%, not the manual's 50%**, which
 * the author's page keeps only for PG2 mode; the class route carries two halvings and the
 * attacker's experience; and evasion **requires a retreat hex** — *"only works if Defender can find
 * a position (hex) to retreat, otherwise is skipped"* — so a successful evade MOVES the defender
 * rather than merely cancelling the damage.
 *
 * Retained from the 0.70.0 changelog, which the combat page does not contradict: *"Units with evade
 * special attribute do not try to evade if attacker is surprised or rugged defense is raised."*
 */
internal object Evade {
    /**
     * The base used when the efile defines no `class_evade` at all — the author's *"30%"*.
     *
     * **Not the manual's 50%.** That figure survives on the author's page only for PG2 mode, which
     * OSADA does not run; the manual's §7.2 sentence is the stale generalisation.
     */
    private const val DEFAULT_PERCENT = 30

    /** `evade_special` bits, from `EFILE_NOKORP/equip.cfg`'s own comment. */
    private const val SPECIAL_NO_EVADE_VS_AIR = 1
    private const val SPECIAL_NO_EVADE_AT_RANGE = 2
    private const val SPECIAL_DISABLED = 4
    private const val SPECIAL_GROUND_ONLY = 8

    /** OG's documented default — *"default, as it is now"*. No shipped efile overrides it. */
    private const val SPECIAL_DEFAULT = 9

    private const val FULL_ROLL = 100

    /** The author's *"5 * exp_bars"*; a bar is 100 experience, as everywhere else in this engine. */
    private const val PER_EXPERIENCE_BAR = 5

    /** OG's own count — *"must define 23 values delimited by commas"*. */
    private const val OG_CLASS_COUNT = 23

    /**
     * OSADA's `UnitClass` in OG's own class numbering, which is what `class_evade` is indexed by.
     *
     * **The two are NOT the same list, and reading one as the other is a trap this project has
     * already fallen into once** (`docs/og-fidelity-plan.md` §U.8: OG class 17 is the Destroyer,
     * OSADA's is the Carrier). This was derived from the merge's own `eqid-map.json` by comparing
     * every OG record's `equip.xeqp` class byte against the `uclass` its merged twin carries, over
     * all eleven readable efiles; every row came back at 100% agreement except OG's *Spare*,
     * *Install* and *Special* slots, which efiles repurpose freely. `EFILE_LUPO`'s readme is an
     * independent check: its `class_evade` matches its own prose column for column.
     *
     * OG has two classes OSADA does not — **RT** (rail transport, folded into Ground Transport) and
     * **HTP** (helicopters, folded into Tactical Bomber) — and one where OSADA has four: **CShip**,
     * which OSADA splits into Battleship, Battle Cruiser, Cruiser and Light Cruiser.
     */
    private val OG_CLASS_OF: Map<Int, Int> =
        mapOf(
            UnitClass.INFANTRY.value to 1,
            UnitClass.TANK.value to 2,
            UnitClass.RECON.value to 3,
            UnitClass.ANTI_TANK.value to 4,
            UnitClass.FLAK.value to 5,
            UnitClass.FORTIFICATION.value to 6,
            UnitClass.GROUND_TRANSPORT.value to 7,
            UnitClass.ARTILLERY.value to 9,
            UnitClass.AIR_DEFENCE.value to 10,
            UnitClass.FIGHTER.value to 11,
            UnitClass.TACTICAL_BOMBER.value to 12,
            UnitClass.LEVEL_BOMBER.value to 13,
            UnitClass.AIR_TRANSPORT.value to 14,
            UnitClass.SUBMARINE.value to 16,
            UnitClass.DESTROYER.value to 17,
            UnitClass.CARRIER.value to 19,
            UnitClass.NAVAL_TRANSPORT.value to 20,
        )

    /** OG's `CShip`, which OSADA splits into four surface classes — see [OG_CLASS_OF]. */
    private const val OG_CLASS_CAPITAL_SHIP = 18

    private fun ogClassOf(uclass: Int): Int = OG_CLASS_OF[uclass] ?: OG_CLASS_CAPITAL_SHIP

    /** Whether [unit]'s equipment carries OG's `Evade` (`attr2` bit 7). Read on the REAL record:
     *  the ability belongs to the formation, not to a transport it is riding. */
    fun hasAbility(unit: GameUnit): Boolean = unit.unitData(true).attr2 and ATTR2_MASK_EVADE != 0

    /**
     * The efile's `class_evade` column for [uclass], or null when the efile defines no table.
     *
     * Both of OG's forms are honoured: `!N` means every class alike (which is what all three
     * shipped efiles use), and 23 comma-separated columns mean one per OG class. A list of any
     * other length is ignored rather than partly read — OG's own comment says *"must define 23
     * values"*, so a shorter one is malformed.
     */
    private fun classColumn(uclass: Int): Int? {
        val flat =
            EfileConfig.rawKeys["class_evade"]
                ?.trim()
                ?.removePrefix("!")
                ?.toIntOrNull()
        val table = EfileConfig.listKey("class_evade").takeIf { it.size == OG_CLASS_COUNT }
        return flat ?: table?.getOrNull(ogClassOf(uclass) - 1)
    }

    /**
     * The base percentage before modifiers, or 0 when [unit] cannot evade at all.
     *
     * Three cases, all the author's: the efile defines `class_evade`, so that class's column
     * applies to everybody in the class; it does not and the unit is a **Submarine**, which is the
     * one class eligible without a table; it does not and the unit carries the special, whose own
     * base is *"base probability"* with no table to read one from.
     */
    private fun basePercent(unit: GameUnit): Int {
        val uclass = unit.unitData(true).uclass
        val column = classColumn(uclass)
        if (column != null) return column
        val classEligible = uclass == UnitClass.SUBMARINE.value
        return if (classEligible || hasAbility(unit)) DEFAULT_PERCENT else 0
    }

    /** Whether [unit] may attempt an evade at all — either OG route. */
    fun eligible(unit: GameUnit): Boolean = basePercent(unit) > 0

    /**
     * Whether OG's `evade_special` permits an evade against this particular attack.
     *
     * The author's page attaches this key to the SPECIAL route. It is applied to both here, on the
     * one reading a key whose own comment says *"evade is disabled completely"* can bear: a switch
     * that turns the mechanic off has to turn it off. Recorded rather than assumed — if OG scopes it
     * to special-carriers alone, this is the sentence to correct.
     */
    private fun permittedBySpecial(
        attacker: GameUnit,
        defender: GameUnit,
        distance: Int,
    ): Boolean {
        val special = EfileConfig.intKey("evade_special", SPECIAL_DEFAULT)
        val restrictionsApply =
            special and SPECIAL_GROUND_ONLY == 0 || UnitPredicates.isGround(defender)
        val refusedForAir =
            restrictionsApply && special and SPECIAL_NO_EVADE_VS_AIR != 0 && UnitPredicates.isAir(attacker)
        val refusedForRange =
            restrictionsApply && special and SPECIAL_NO_EVADE_AT_RANGE != 0 && distance > 1
        return special and SPECIAL_DISABLED == 0 && !refusedForAir && !refusedForRange
    }

    private fun bars(unit: GameUnit): Int = UnitExperience.bars(unit)

    /**
     * The chance [defender] evades this attack, as a percentage, or 0 when it cannot.
     *
     * **[hasRetreatHex] is OG's own precondition** — *"only works if Defender can find a position
     * (hex) to retreat, otherwise is skipped"* — passed in rather than computed here because the
     * caller already holds the grid and the map dimensions.
     *
     * **[surprisedOrRugged] is OG's exclusion**, from the 0.70.0 changelog. Both of those already
     * favour the defender, so the rule is anti-stacking.
     */
    @Suppress("LongParameterList") // one OG modifier per parameter; collapsing them would hide them
    fun percentFor(
        attacker: GameUnit,
        defender: GameUnit,
        distance: Int,
        adjacentEnemies: Int,
        hasRetreatHex: Boolean,
        surprisedOrRugged: Boolean = false,
    ): Int {
        val base = basePercent(defender)
        val refused =
            base <= 0 ||
                // OG's `Saboteur`: a sabotaged formation "cannot ... evade", and the author's own
                // eligibility sentence says the special route applies "as long as they're not
                // sabotaged". Both routes are refused: a unit that has been got at does not slip
                // away, whichever door its evade came through.
                defender.sabotaged ||
                !hasRetreatHex ||
                surprisedOrRugged ||
                !permittedBySpecial(attacker, defender, distance)
        if (refused) return 0
        val zoc = EfileConfig.intKey("zoc_evade", 0) * (adjacentEnemies - 1).coerceAtLeast(0)
        val percent =
            if (hasAbility(defender)) {
                base - zoc + PER_EXPERIENCE_BAR * bars(defender)
            } else {
                classRoutePercent(attacker, defender, base - zoc)
            }
        return percent.coerceIn(0, FULL_ROLL)
    }

    /** The class route's own modifiers: both sides' experience, then the two halvings. */
    private fun classRoutePercent(
        attacker: GameUnit,
        defender: GameUnit,
        afterZoc: Int,
    ): Int {
        var percent = afterZoc + PER_EXPERIENCE_BAR * (bars(defender) - bars(attacker))
        if (UnitPredicates.isAir(attacker)) percent /= 2
        if (defender.isMounted) percent /= 2
        return percent
    }

    /**
     * Rolls the evade for a COMMITTED attack.
     *
     * **Only ever called from the committed path**, which is [GameRandomSource]'s first contract
     * rule: a forecast that rolled here would advance the shared stream on one peer's screen and
     * desynchronise a multiplayer battle. The forecast therefore shows the ordinary result and the
     * evade is announced when it happens, exactly as a rugged defence is.
     */
    @Suppress("LongParameterList") // mirrors [percentFor]
    fun rolls(
        attacker: GameUnit,
        defender: GameUnit,
        distance: Int,
        adjacentEnemies: Int,
        hasRetreatHex: Boolean,
        surprisedOrRugged: Boolean,
    ): Boolean {
        val percent =
            percentFor(attacker, defender, distance, adjacentEnemies, hasRetreatHex, surprisedOrRugged)
        return percent > 0 && GameRandomSource.nextInt(FULL_ROLL) < percent
    }
}
