@file:Suppress("MaxLineLength", "LongMethod", "ComplexMethod")

package org.osada.ui

import org.osada.GameHolder
import org.osada.hero.HeroCampaign
import org.osada.i18n.I18n
import org.osada.model.Equipment
import org.osada.model.GameUnit
import org.osada.model.Leaders
import org.osada.model.TerrainEx
import org.osada.model.getCountryName
import org.osada.model.getUnits
import org.osada.rules.CombatResolver
import org.osada.rules.InitiativeModel
import org.osada.rules.UnitCapabilities
import org.osada.terrainNames
import org.osada.unitClassNames
import org.w3c.dom.HTMLElement

/**
 * Adds identified live state and the largest active combat modifiers to the enemy card.
 *
 * **Localized (DEFERRED.md §4.15).** Every chip label, tooltip and factor line used to be an
 * English string literal — this whole surface was invisible to §4.10's sweep because it predates it.
 * `terrainNames` / `unitClassNames` stay as they are: those are game-data name tables shared with
 * the rest of the engine, and localizing them is a separate job with a different blast radius.
 */
internal object CombatTransparencyPresenter {
    // TODO(detekt): CyclomaticComplexMethod (20) — assembles every identified-state chip and
    // active-modifier line for the enemy card; deliberately deferred rather than rushed.
    @Suppress("CyclomaticComplexMethod")
    fun presentEnemy(unit: GameUnit) {
        UnitIdentityStyles.ensureInstalled()
        val main = byId("ecMain") ?: return
        val chips = ensureChips(main)
        val data = unit.unitData(true)
        val terrain =
            unit.getHex()?.terrain?.let { terrainNames.getOrNull(it) } ?: I18n.t("combat.enemy.terrain.unknown")
        val game = GameHolder.instance
        val spotSide = game?.spotSide ?: -1
        val visibleUnits =
            game
                ?.scenario
                ?.map
                ?.getUnits()
                ?.filter { candidate ->
                    candidate === unit || candidate.tempSpotted || candidate.getHex()?.isSpotted(spotSide) == true
                }.orEmpty()
        val supportBars = UnitCapabilities.combatSupportBars(visibleUnits, unit)
        val dossier = HeroCampaign.dossier(unit)
        val legacyLeader =
            if (unit.leader >= 0) {
                Leaders.getUnitLeaderDescriptions(unit).firstOrNull()?.first
                    ?: I18n.t("combat.enemy.commander.fallback")
            } else {
                null
            }
        val commander = dossier?.let { "${it.rank} ${it.name}" } ?: legacyLeader
        // §1.3: TerrainEx.initiativeCap already throttles this unit's effective initiative in
        // combat (AttackCalculation.applyInitiativeBonus, capped by the DEFENDER's hex -- this card
        // is shown for the enemy the player may attack, i.e. the defender) but nothing told the
        // player why a unit with high listed initiative still lost the initiative roll. No existing
        // surface explains a single combat modifier this way, so it gets its own chip + tooltip line
        // rather than a silent number.
        val initiativeCap = unit.getHex()?.terrain?.let { TerrainEx.initiativeCap(it) }
        // The base the cap actually bites on, not the raw equipment stat: under `initiative_model`
        // a veteran brings its experience bars into the hex too, and the resolver caps the sum
        // (`AttackCalculation.applyInitiativeBonus`). Reading `data.initiative` alone would tell the
        // player a veteran was uncapped in a town when the resolver had just capped it, which is
        // precisely the unexplained roll this chip was added to prevent. Adds 0 with the key off.
        val initiativeBase = data.initiative + InitiativeModel.experienceBonus(unit)
        val initiativeCapped = initiativeCap != null && initiativeBase > initiativeCap
        val entrenchmentBypassed = unit.entrenchment > 0 && isEntrenchmentBypassed(unit)
        val iniCapArgs = mapOf("cap" to initiativeCap, "base" to initiativeBase)
        val entrenchmentHelp =
            if (entrenchmentBypassed) {
                "combat.enemy.chip.entrenchment.bypassed.help"
            } else {
                "combat.enemy.chip.entrenchment.help"
            }

        setChip(chips, "ecExperience", "EXP ${unit.experience}", true, I18n.t("combat.enemy.chip.experience.help"))
        setChip(chips, "ecEntrenchment", "ENT ${unit.entrenchment}", unit.entrenchment > 0, I18n.t(entrenchmentHelp))
        byId("ecEntrenchment")?.classList?.toggle("osada-ec-chip--struck", entrenchmentBypassed)
        setChip(
            chips,
            "ecSuppression",
            "SUPP ${unit.hits}",
            unit.hits > 0,
            I18n.t("combat.enemy.chip.suppression.help"),
        )
        setChip(
            chips,
            "ecSupport",
            "SUPPORTED +$supportBars",
            supportBars > 0,
            I18n.t("combat.enemy.chip.support.help"),
        )
        setChip(chips, "ecMounted", "MOUNTED", unit.isMounted, I18n.t("combat.enemy.chip.mounted.help"))
        setChip(chips, "ecSurprised", "SURPRISED", unit.isSurprised, I18n.t("combat.enemy.chip.surprised.help"))
        setChip(chips, "ecCommander", "LEADER", commander != null, commander ?: "")
        setChip(
            chips,
            "ecIniCap",
            I18n.t("combat.enemy.chip.initiative_cap", iniCapArgs),
            initiativeCapped,
            I18n.t("combat.enemy.chip.initiative_cap.help", iniCapArgs),
        )
        setChip(
            chips,
            "ecEntBypass",
            I18n.t("combat.enemy.chip.entrenchment_bypassed"),
            entrenchmentBypassed,
            I18n.t("combat.enemy.chip.entrenchment_bypassed.help"),
        )

        val country = Equipment.getCountryName(unit.flag - 1)
        val className = unitClassNames.getOrNull(data.uclass) ?: I18n.t("combat.enemy.class.unknown")
        byId("ecSub")?.textContent =
            I18n.t(
                "combat.enemy.sub",
                mapOf("class" to className, "country" to country, "terrain" to terrain),
            )
        byId("ecStat")?.textContent =
            I18n.t(
                "combat.enemy.stat",
                mapOf(
                    "strength" to unit.strength,
                    "experience" to unit.experience,
                    "entrenchment" to unit.entrenchment,
                    "ground" to data.grounddef,
                    "air" to data.airdef,
                ),
            )

        // Keys are spelled out in full, never assembled from a prefix + variable: check_translations.py
        // finds used keys by matching the literal argument, and an interpolated key is invisible to it.
        val ent = unit.entrenchment
        val exp = unit.experience
        val factors =
            buildList {
                add(I18n.t("combat.enemy.factors.title"))
                add(I18n.t("combat.enemy.factors.terrain", mapOf("terrain" to terrain)))
                if (ent > 0) add(I18n.t("combat.enemy.factors.entrenchment", mapOf("value" to ent)))
                if (entrenchmentBypassed) add(I18n.t("combat.enemy.factors.entrenchment_bypassed"))
                if (exp > 0) add(I18n.t("combat.enemy.factors.experience", mapOf("value" to exp)))
                if (unit.hits > 0) add(I18n.t("combat.enemy.factors.suppression", mapOf("value" to unit.hits)))
                if (supportBars > 0) add(I18n.t("combat.enemy.factors.support", mapOf("value" to supportBars)))
                if (initiativeCapped) add(I18n.t("combat.enemy.factors.initiative_cap", iniCapArgs))
                if (commander != null) add(I18n.t("combat.enemy.factors.leader", mapOf("name" to commander)))
                if (unit.isMounted) add(I18n.t("combat.enemy.factors.mounted"))
                if (unit.isSurprised) add(I18n.t("combat.enemy.factors.surprised"))
                add(I18n.t("combat.enemy.factors.hidden_supply"))
            }.joinToString("\n• ", postfix = "", prefix = "• ")
        byId("ecStat")?.title = factors
        byId("ecName")?.title =
            listOfNotNull(byId("ecName")?.title, factors)
                .filter(String::isNotBlank)
                .joinToString("\n\n")
    }

    /**
     * Whether the player's currently selected unit would ignore [defender]'s entrenchment
     * (DEFERRED.md §1.20).
     *
     * **Asks the rule, it does not restate it.** `CombatResolver.isEntrenchmentIntact` is the single
     * predicate combat itself uses, so the chip and the resolution cannot drift — that matters here
     * because there are three independent bypass sources (the "Ignore trench" `attr` bit, the
     * Infiltration/Street-Fighter leaders on vulnerable terrain, and the Bunker Buster attachment),
     * and the defender's own Ferocious Defense leader overrides all of them.
     *
     * Null-safe by design: this card is also shown on plain hover with nothing of the player's
     * selected, and with no attacker there is no bypass to report.
     */
    private fun isEntrenchmentBypassed(defender: GameUnit): Boolean {
        val attacker =
            GameHolder.instance
                ?.scenario
                ?.map
                ?.currentUnit
        val terrain = defender.getHex()?.terrain
        val hostile = attacker != null && attacker.player?.side != defender.player?.side
        return attacker != null &&
            terrain != null &&
            hostile &&
            !CombatResolver.isEntrenchmentIntact(attacker, defender, terrain)
    }

    private fun ensureChips(main: HTMLElement): HTMLElement =
        (byId("ecLiveState") ?: addTag(main, "div")).also { container ->
            container.id = "ecLiveState"
            container.className = "osada-ec-chips"
            val strengthRow = byId("ecStrRow")
            if (strengthRow != null && container.parentElement === main) main.insertBefore(container, strengthRow)
        }

    private fun setChip(
        parent: HTMLElement,
        id: String,
        text: String,
        visible: Boolean,
        title: String,
    ) {
        val chip =
            (byId(id) ?: addTag(parent, "span")).also {
                it.id = id
                it.className = "osada-ec-chip"
            }
        chip.style.display = if (visible) "inline-flex" else "none"
        chip.textContent = if (visible) text else ""
        chip.title = title
    }
}
