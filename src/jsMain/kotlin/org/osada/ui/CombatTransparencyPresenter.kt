@file:Suppress("MaxLineLength", "LongMethod", "ComplexMethod")

package org.osada.ui

import org.osada.GameHolder
import org.osada.LeaderType
import org.osada.hero.HeroCampaign
import org.osada.hero.HeroDisplay
import org.osada.i18n.I18n
import org.osada.model.Equipment
import org.osada.model.GameUnit
import org.osada.model.Leaders
import org.osada.model.TerrainEx
import org.osada.model.getCountryName
import org.osada.model.getUnits
import org.osada.rules.EntrenchmentDefence
import org.osada.rules.InitiativeModel
import org.osada.rules.UnitCapabilities
import org.osada.terrainNames
import org.osada.unitClassNames
import org.w3c.dom.HTMLElement

/**
 * Adds identified live state and the largest active combat modifiers to the enemy card.
 *
 * **Localized.** Every chip label, tooltip and factor line used to be an
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
        val selected = game?.scenario?.map?.currentUnit

        setChip(chips, "ecExperience", "EXP ${unit.experience}", true, I18n.t("combat.enemy.chip.experience.help"))
        setChip(
            chips,
            "ecEntrenchment",
            "ENT ${unit.entrenchment}",
            EntrenchmentTooltip.visible(unit),
            EntrenchmentTooltip.text(unit, selected),
        )
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
        presentEnemyCommander(main, unit, dossier?.let { "${it.rank} ${it.name}" })
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
                    "strength" to
                        HiddenNumbers.strengthFor(
                            unit,
                            org.osada.GameHolder.instance
                                ?.spotSide,
                        ),
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
     * (`docs/design/attachments.md`).
     *
     * **Asks the rule, it does not restate it.** `EntrenchmentDefence.forecast` is what combat itself adds,
     * so the chip and the resolution cannot drift — that matters here
     * because there are three independent bypass sources (the "Ignore trench" `attr` bit, the
     * Infiltration/Street-Fighter leaders, and the Bunker Buster attachment),
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
        val hostile = attacker != null && attacker.player?.side != defender.player?.side
        return attacker != null &&
            hostile &&
            EntrenchmentDefence.forecast(attacker, defender)?.bypassed == true
    }

    /**
     * The enemy's commander, drawn the way the player's own card draws one: the `.uc-commander-icon`
     * peaked cap, then the traits by name, each trait's effect in the tooltip. Until 2026-09-28 the
     * enemy card only carried a bare "LEADER" chip whose tooltip named the FIRST trait -- so the
     * Camouflage Expert waiting at a crossroads, or the Overwatch battery covering it, was invisible
     * even once spotted.
     *
     * The traits are asked of [Leaders.unitHasLeader], the rules' own entry point, rather than read
     * off `GameUnit.leader`: that covers both of a legacy leader's attributes (individual and class,
     * the second of which the old chip never showed) and a hero's, and a hero still settling into a
     * transferred formation correctly shows none. Names and effects come from the same localized
     * strings the commander dossier uses ([HeroDisplay.trait]).
     *
     * Hidden when the unit has no commander. The card is only ever shown for a unit the player can
     * see, so this reveals nothing fog of war hides.
     */
    private fun presentEnemyCommander(
        main: HTMLElement,
        unit: GameUnit,
        heroLabel: String?,
    ) {
        val line =
            byId("ecCommanderLine") ?: addTag(main, "div").also { created ->
                created.id = "ecCommanderLine"
                TapTip.fromTitle(created)
            }
        // Directly under the class/country/terrain line, above the state chips and the strength bar.
        byId("ecLiveState")?.let { chips -> if (chips.parentElement === main) main.insertBefore(line, chips) }
        val traits =
            if (unit.leader >= 0 || heroLabel != null) {
                LeaderType.entries.filter { Leaders.unitHasLeader(unit, it) }
            } else {
                emptyList()
            }
        if (traits.isEmpty() && heroLabel == null) {
            line.style.display = "none"
            line.textContent = ""
            line.title = ""
            return
        }
        val lines = traits.map { HeroDisplay.trait(it, "") }
        line.style.display = ""
        line.className =
            "uc-commander-line " + if (heroLabel != null) "uc-commander-line--hero" else "uc-commander-line--legacy"
        line.style.cursor = "help"
        line.textContent = ""
        addTag(line, "span").also {
            it.className = "uc-commander-icon"
            it.setAttribute("aria-hidden", "true")
        }
        addTag(line, "span").also {
            it.className = "uc-commander-text"
            it.textContent = listOfNotNull(heroLabel).plus(lines.map { l -> l.title }).joinToString(" · ")
        }
        line.title =
            listOfNotNull(heroLabel ?: I18n.t("combat.enemy.commander.fallback"))
                .plus(lines.map { "${it.title}: ${it.effect}" })
                .joinToString("\n")
        line.setAttribute("aria-label", line.title.replace("\n", ". "))
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
            byId(id) ?: addTag(parent, "span").also {
                it.id = id
                it.className = "osada-ec-chip"
                // Every chip explains itself in `title=`, which a phone never shows. Attached once,
                // when the chip is created; the text is read at tap time, so it stays current.
                TapTip.fromTitle(it)
            }
        chip.style.display = if (visible) "inline-flex" else "none"
        chip.textContent = if (visible) text else ""
        chip.title = title
    }
}
