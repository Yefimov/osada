package org.osada.ai

import org.osada.GameHolder
import org.osada.PlayerSide
import org.osada.ui.mainMenuButton

// Turn 4 (TUTORIAL_TURN_FINAL_ASSAULT) of AIScripted's scripted tutorial -- split out of
// buildTutorialActions (LongMethod) and into its own file (keeps AIScriptedHelpers.kt's per-turn
// siblings under detekt's per-file TooManyFunctions budget too).

internal fun AIScripted.buildTurn4Actions() {
    if (player.side == PlayerSide.AXIS.value) {
        buildTurn4AxisFirstAttack()
        buildTurn4AxisArtyReposition()
        buildTurn4AxisSecondAttack()
        buildTurn4AxisResupply()
        buildTurn4AxisOutro()
    }
}

private fun AIScripted.buildTurn4AxisFirstAttack() {
    val u = unitAt(row = 14, col = 9)
    select(u)
    message(
        "Let's attack the weakest enemy unit on the front line. The attack cursor gives an estimate " +
            "of your casualties on the left and enemy casualties on the right below the flags of the " +
            "units involved in combat.",
        row = 15,
        col = 9,
    )
    val e = unitAt(row = 15, col = 9)
    attack(u, e)
    message(
        "This artillery unit has automatically fired when we attacked. That's because artillery " +
            "provides support fire to friendly units in their range if attacked. Other units with " +
            "combat support include air defense and fighter planes when attacked by an enemy plane.",
        row = 16,
        col = 7,
    )
    message(
        "Luckily our artillery unit has a bigger range than enemy artillery so we can safely move it " +
            "into fire range without making us vulnerable to enemy fire while in transport.",
        row = 13,
        col = 13,
    )
}

private fun AIScripted.buildTurn4AxisArtyReposition() {
    val u = unitAt(row = 13, col = 13)
    mount(u)
    move(u, row = 14, col = 12)
    message(
        "Armored units are considered hard targets <span class='statsGlyph'>`</span> and will take " +
            "less casualties from artillery fire than a soft target like infantry or artillery. In " +
            "some cases armored units are used to deplete the ammo of the defending artillery.",
        row = 12,
        col = 12,
    )
}

private fun AIScripted.buildTurn4AxisSecondAttack() {
    val u = unitAt(row = 12, col = 12)
    move(u, row = 14, col = 7)
    val e = unitAt(row = 15, col = 8)
    attack(u, e)
}

private fun AIScripted.buildTurn4AxisResupply() {
    val u = unitAt(row = 15, col = 10)
    if (u != null) u.fuel = 0
    select(u)
    message(
        "This unit it's out of fuel and it can no longer move.You can always supply ammo <span " +
            "class='statsGlyph'></span> and fuel to your units by clicking on the <span " +
            "class='smallButtonSubMenu'>!</span>   button on the bottom of the screen",
        row = 15,
        col = 10,
    )
    resupply(u)
}

private fun AIScripted.buildTurn4AxisOutro() {
    GameHolder.instance?.ui?.mainMenuButton("mainmenu")
    message(
        "This concludes the tutorial and gives you the control of the army to capture the last " +
            "objective. Remember to use your prestige to upgrade or buy new units by clicking on the " +
            "<span class='smallButtonSubMenu'>w</span>  button from the menu on the right side of " +
            "the screen.",
        row = 16,
        col = 8,
    )
    message(
        "You should attack enemy artillery with your own artillery and move your tanks and attack " +
            "the enemy artillery. There is a possibility that your armor attack will result in an " +
            "OverRun which completely destroys an unit and allows your armor to continue move and " +
            "attack.",
        row = 16,
        col = 8,
    )
    message(
        "Remember to tap or click the <span class='smallButtonSubMenu'>t</span> end turn button " +
            "twice to end your turns. Depending on scenario you or the enemy might also receive " +
            "reinforcements. On this tutorial you will receive reinforcements next turn.",
        row = 16,
        col = 8,
    )
}
