package org.osada.model

import org.osada.GameHolder
import org.osada.PlayerType
import org.osada.difficultyModifiers
import org.osada.hero.FormationIdentity
import org.osada.hero.HeroCampaign
import org.osada.i18n.GameText
import org.osada.scoreGains

@JsExport
@JsName("Player")
class Player {
    var id: Int = -1
    var side: Int = -1
    var country: Int = -1
    var prestige: Int = 0
    var score: Int = 0
    var playedTurn: Int = -1
    var type: PlayerType = PlayerType.HUMAN_LOCAL
    var handler: dynamic = null

    /**
     * OG's non-organic transport pools, as **availability** — how many of each are free right now.
     *
     * OG itself draws this distinction, in the changelog entry that added the editor field:
     *
     * > *"Transport pool **sizes (not availability)** are included in scenario settings dialog"*
     * > — 0.90.42.2, and *"Available ATP,NTP,RTP are shown for active player when mouse hovers
     * > Airfields"*.
     *
     * So the scenario byte is a SIZE (a ceiling, held in [airTransportsMax] and its two siblings)
     * and these three are the live count that embarkation spends and disembarkation gives back.
     * See `model/TransportPools` for the lifetime and the sentence it rests on.
     */
    var airTransports: Int = 0
    var navalTransports: Int = 0

    /** OG's per-player RAIL transport pool -- *"the player must have some rail transport
     *  available"*. Scenario attribute `railtrans`, the third of OG's four pools beside
     *  `airtrans`, `navaltrans` and the helo pool OSADA does not model yet.
     *
     *  Imported since 2026-08-29 from player record `+21`, confirmed by OpenSuite's own REPORT
     *  logs (`docs/og-open-questions.md` §Y.1). Spent by `rules/RailTransport` and returned at the
     *  owner's next turn — `model/TransportPools` carries the reasoning. */
    var railTransports: Int = 0

    /**
     * The authored SIZE of each pool — the ceiling a release may not push availability past.
     *
     * A scenario may deploy a unit that already sits in a transport, and that unit never spent a
     * pool point; without a ceiling its disembarkation would MINT one. These three are set beside
     * the available counts by `ScenarioPlayerParser` and carried through saves.
     */
    var airTransportsMax: Int = 0
    var navalTransportsMax: Int = 0
    var railTransportsMax: Int = 0

    /**
     * OG's per-player **default experience** for newly acquired units — player record `+37`,
     * scenario attribute `defaultxp`, switched on by `opt_default_xp` (224 of the 397 deployed
     * scenarios whose source parses).
     *
     * **0 means the author did not set it**, and the importer writes 0 whenever the scenario's own
     * switch is off — the value byte carries leftover editor state otherwise. OSADA then keeps the
     * behaviour it had before this existed rather than substituting a zero the author never chose.
     */
    var defaultExperience: Int = 0

    /**
     * OG's per-player **default strength** for newly acquired units — player record `+39`,
     * attribute `defaultstr`, switched on by `opt_allow_default_str` (149 scenarios).
     *
     * `uspanwar1` states its own value in prose — *"New purchased units will have 5 as default
     * strength"* — and its byte reads 5, which is what confirmed the offset. 0 means not authored,
     * exactly as for [defaultExperience].
     */
    var defaultStrength: Int = 0

    /**
     * OG's per-scenario **purchase whitelist** for this player — the `.buy4` sidecar, deployed as
     * the `buylist` attribute and read by `rules/ScenarioPurchaseList`.
     *
     * **Null means unrestricted**, and that is the case for 497 of the 502 deployed scenarios. A
     * non-null set is the resolved output of OpenSuite's Fronts/Factions picker: exactly the
     * equipment ids the author left this player able to buy or upgrade into.
     */
    var purchaseList: Set<Int>? = null
    var supportCountries: MutableList<Int> = mutableListOf()
    var prestigePerTurn: MutableList<Int> = mutableListOf()

    private val coreUnits: MutableList<GameUnit> = mutableListOf()
    var dossier: dynamic = null

    fun getCoreUnitList(): List<GameUnit> = coreUnits.toList()

    /**
     * Adds [unit] to the campaign core roster, minting its persistent formation id if it has none.
     *
     * Core-roster insertion is one enrollment path (tray, purchase, carry-over and restore). The
     * campaign-wide ownership sweep also covers non-core pre-placement and scripted reinforcement;
     * [org.osada.hero.FormationIdentity.ensure] is idempotent across both paths.
     */
    fun addCoreUnit(unit: GameUnit?): Boolean {
        if (unit == null || coreUnits.any { it === unit }) return false
        unit.isCore = true
        if (!unit.isTemporaryBorrowed) {
            FormationIdentity.ensure(unit, knownFormationIds(getCoreUnitList()))
            HeroCampaign.synchronizeFormation(unit)
        }
        coreUnits.add(unit)
        return true
    }

    fun removeUndeployedCoreUnit(index: Int) {
        if (index in coreUnits.indices) {
            coreUnits.removeAt(index)
        }
    }

    fun setCoreUnitList(list: List<GameUnit>) {
        coreUnits.clear()
        coreUnits.addAll(list)
    }

    fun getCountryName(): String = Equipment.getCountryName(country)

    fun getSideName(): String = GameText.side(side)

    fun hasUndeployedUnits(): Boolean = coreUnits.any { !it.isDeployed }

    fun updateScore(
        amount: Int,
        multiplier: Int = 1,
    ) {
        val coef = GameHolder.instance?.campaign?.let { difficultyModifiers[it.difficulty]?.scoreCoef } ?: 1.0
        score += (amount * multiplier * coef).toInt()
    }

    fun endTurn(turn: Int) {
        playedTurn = turn
        if (turn < prestigePerTurn.size) {
            awardPrestige(prestigePerTurn[turn])
        }
        updateScore(scoreGains["endTurn"] ?: 0)
    }

    fun setPlayerToHQ() {
        supportCountries.clear()
        prestigePerTurn.clear()
        airTransports = 0
        navalTransports = 0
        railTransports = 0
        airTransportsMax = 0
        navalTransportsMax = 0
        railTransportsMax = 0
        defaultExperience = 0
        defaultStrength = 0
        val iter = coreUnits.iterator()
        while (iter.hasNext()) {
            val unit = iter.next()
            if (unit.destroyed) {
                iter.remove()
            } else {
                // Returns the formation to the tray ready to ACT, not ready to fight: the turn
                // flags and movement are reset, but strength, ammo and fuel are carried forward as
                // the battle left them.
                //
                // This used to also do `if (strength < 10) strength = 10; refillAmmoFuel()` -- a
                // free, unconditional, full refit of the whole army after every scenario. That made
                // attrition nearly meaningless between battles and voided every authored `resupply`
                // campaign effect, which could only ever top up units the pass had already filled.
                // Restoring readiness is now a paid decision the player makes in the reserve tray
                // ([ReserveRefit]).
                unit.hasMoved = false
                unit.hasFired = false
                unit.hasResupplied = false
                unit.isSurprised = false
                unit.isDeployed = false
                unit.moveLeft = unit.unitData().movpoints
                unit.entrenchment = 0
                unit.entrenchTicks = 0
                unit.hits = 0
                unit.setHex(null)
            }
        }
    }

    fun copy(
        other: Player,
        accumulateScore: Boolean = false,
    ) {
        id = other.id
        side = other.side
        country = other.country
        prestige = other.prestige
        playedTurn = other.playedTurn
        type = other.type
        if (accumulateScore) {
            score += other.score
        } else {
            score = other.score
            airTransports = other.airTransports
            navalTransports = other.navalTransports
            railTransports = other.railTransports
            airTransportsMax = other.airTransportsMax
            navalTransportsMax = other.navalTransportsMax
            railTransportsMax = other.railTransportsMax
            defaultExperience = other.defaultExperience
            defaultStrength = other.defaultStrength
            supportCountries.clear()
            supportCountries.addAll(other.supportCountries)
            prestigePerTurn.clear()
            prestigePerTurn.addAll(other.prestigePerTurn)
        }
        setCoreUnitList(other.getCoreUnitList())
        if (other.dossier != null) {
            copyDossier(other)
        }
    }
}
