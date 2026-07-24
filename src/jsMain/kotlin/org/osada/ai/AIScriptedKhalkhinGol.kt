package org.osada.ai

import org.osada.i18n.I18n

internal const val SOVIET_TUTORIAL_SIDE = 1
private const val JAPANESE_TUTORIAL_SIDE = 0
private const val SOVIET_OWNER = 0
private const val JAPANESE_OWNER = 1

private const val SOVIET_MG_INFANTRY = 16625
private const val SOVIET_BA_10 = 16601
private const val SOVIET_ARTILLERY = 16726
private const val SOVIET_BT_5 = 16558
private const val JAPANESE_RECON_INFANTRY = 3556
private const val JAPANESE_FORWARD_INFANTRY = 3371

internal fun AIScripted.buildKhalkhinGolTurn1() {
    when (player.side) {
        SOVIET_TUTORIAL_SIDE -> buildKhalkhinGolTurn1Soviet()
        JAPANESE_TUTORIAL_SIDE -> buildKhalkhinGolTurn1Japanese()
    }
}

@Suppress("LongMethod")
private fun AIScripted.buildKhalkhinGolTurn1Soviet() {
    message(
        I18n.t("tutorial.turn1.primary_crossing"),
        row = 4,
        col = 7,
    )
    message(
        I18n.t("tutorial.turn1.hamar_daba"),
        row = 7,
        col = 8,
    )
    message(
        I18n.t("tutorial.turn1.optional_objective"),
        row = 4,
        col = 3,
    )

    val infantry = unitByEqid(SOVIET_MG_INFANTRY, SOVIET_OWNER)
    select(infantry)
    message(
        I18n.t("tutorial.turn1.strength"),
        row = 3,
        col = 11,
    )
    message(
        I18n.t("tutorial.turn1.unit_info"),
        row = 3,
        col = 11,
    )
    message(
        I18n.t("tutorial.turn1.transport"),
        row = 3,
        col = 11,
    )
    mount(infantry)
    select(infantry)
    move(infantry, row = 4, col = 10)

    val recon = unitByEqid(SOVIET_BA_10, SOVIET_OWNER)
    select(recon)
    message(
        I18n.t("tutorial.turn1.recon"),
        row = 8,
        col = 9,
    )
    move(recon, row = 7, col = 10)
    message(
        I18n.t("tutorial.turn1.terrain"),
        row = 7,
        col = 10,
    )
    message(
        I18n.t("tutorial.turn1.complete"),
        row = 7,
        col = 10,
    )
}

private fun AIScripted.buildKhalkhinGolTurn1Japanese() {
    val attacker = unitByEqid(JAPANESE_RECON_INFANTRY, JAPANESE_OWNER)
    val defender = unitByEqid(SOVIET_MG_INFANTRY, SOVIET_OWNER)
    attack(attacker, defender)
}

internal fun AIScripted.buildKhalkhinGolTurn2() {
    if (player.side != SOVIET_TUTORIAL_SIDE) return

    val infantry = unitByEqid(SOVIET_MG_INFANTRY, SOVIET_OWNER)
    select(infantry)
    message(
        I18n.t("tutorial.turn2.counterattack"),
        row = 4,
        col = 10,
    )

    val artillery = unitByEqid(SOVIET_ARTILLERY, SOVIET_OWNER)
    val defender = unitByEqid(JAPANESE_FORWARD_INFANTRY, JAPANESE_OWNER)
    select(artillery)
    message(
        I18n.t("tutorial.turn2.artillery"),
        row = 6,
        col = 8,
    )
    message(
        I18n.t("tutorial.turn2.combat_estimate"),
        row = 4,
        col = 7,
    )
    attack(artillery, defender)
    message(
        I18n.t("tutorial.turn2.bombardment"),
        row = 4,
        col = 7,
    )

    val tank = unitByEqid(SOVIET_BT_5, SOVIET_OWNER)
    select(tank)
    message(
        I18n.t("tutorial.turn2.target_types"),
        row = 5,
        col = 7,
    )
}

@Suppress("MagicNumber")
internal fun AIScripted.buildKhalkhinGolTurn3() {
    if (player.side != SOVIET_TUTORIAL_SIDE) return

    val infantry = unitByEqid(SOVIET_MG_INFANTRY, SOVIET_OWNER)
    if (infantry != null && infantry.strength > 7) infantry.strength = 7
    select(infantry)
    message(
        I18n.t("tutorial.turn3.reinforcement"),
        row = 4,
        col = 10,
    )
    reinforce(infantry)

    val tank = unitByEqid(SOVIET_BT_5, SOVIET_OWNER)
    if (tank != null) tank.fuel = 0
    select(tank)
    message(
        I18n.t("tutorial.turn3.resupply"),
        row = 5,
        col = 7,
    )
    resupply(tank)

    val defender = unitByEqid(JAPANESE_FORWARD_INFANTRY, JAPANESE_OWNER)
    if (defender != null) {
        // The tutorial must demonstrate OverRun deterministically rather than depend on combat RNG.
        defender.strength = 1
        defender.entrenchment = 0
    }
    message(
        I18n.t("tutorial.turn3.overrun_setup"),
        row = 4,
        col = 7,
    )
    attack(tank, defender)
    message(
        I18n.t("tutorial.turn3.overrun_result"),
        row = 4,
        col = 7,
    )
    move(tank, row = 4, col = 7)
    message(
        I18n.t("tutorial.turn3.crossing_captured"),
        row = 4,
        col = 7,
    )
    modalMessage(
        title = I18n.t("tutorial.command.title"),
        body = I18n.t("tutorial.command.body"),
    )
}
