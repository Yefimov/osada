package org.osada.ai

import org.osada.PlayerSide
import org.osada.model.GameUnit

// Turn 1 of AIScripted's scripted tutorial -- split out of buildTutorialActions (LongMethod) and
// into its own file (keeps AIScriptedHelpers.kt's per-turn siblings under detekt's per-file
// TooManyFunctions budget too).

internal fun AIScripted.buildTurn1Actions(
    axisUnits: Map<String, GameUnit?>,
    alliesUnits: Map<String, GameUnit?>,
) {
    if (player.side == PlayerSide.AXIS.value) {
        buildTurn1AxisObjectiveIntro()
        buildTurn1AxisLegionInf(axisUnits)
        buildTurn1AxisRecon(axisUnits)
        buildTurn1AxisAdvance(axisUnits)
    } else {
        attack(alliesUnits["inf1"], axisUnits["ssinf1"])
    }
}

private fun AIScripted.buildTurn1AxisObjectiveIntro() {
    message(
        "The objective of the game is to conquer all enemy positions marked by a golden frame and " +
            "enemy flag. This is one of the positions. Moving one of your units into this position " +
            "will capture this city and raise your flag over it.",
        row = 8,
        col = 15,
    )
    message(
        "This is the second primary objective since it has your enemy flag (Nationalist Spain) and a " +
            "golden frame around. Capturing primary objectives gives you a good amount of points " +
            "called prestige which can be used to upgrade or buy new troops.",
        row = 16,
        col = 8,
    )
    message(
        "This is an optional objective with your enemy flag. Although capturing these  objectives " +
            "won't count towards victory is good to do so for extra prestige and troops experience. " +
            "Also troops are better reinforced or resupplied in a city.",
        row = 11,
        col = 22,
    )
}

private fun AIScripted.buildTurn1AxisLegionInf(axisUnits: Map<String, GameUnit?>) {
    val b = axisUnits["legioninf"]
    select(b)
    message(
        "This is one of your units and usually represent a historical battalion, the number below is " +
            "battalion size, usually named strength. Since you are playing as the Republican " +
            "(Loyalist) side the number is on a grey box. Your enemies (Nationalist Spain) will have " +
            "this number box in green.",
        row = 4,
        col = 2,
    )
    message(
        "Units have several stats, click the <span class='smallButtonSubMenu'>i</span> button on the " +
            "right menu to open a panel with unit stats on the bottom of the screen. Notice the " +
            "movement range <span class='statsGlyph'>?</span> for this unit is 3. The grey hexes " +
            "around this unit represent this movement range.",
        row = 4,
        col = 2,
    )
    message(
        "This unit has a transport, shown by the <span class='smallButtonSubMenu'>[</span> button on " +
            "the bottom of the screen. While on transports moving range is increased but, except " +
            "infantry, units become vulnerable to enemy fire. I will now mount and move this unit " +
            "for you toward objective.",
        row = 4,
        col = 2,
    )
    mount(b)
    select(b)
    move(b, row = 7, col = 9)
}

private fun AIScripted.buildTurn1AxisRecon(axisUnits: Map<String, GameUnit?>) {
    val b = axisUnits["recon"]
    select(b)
    message(
        "This is a recon unit. Recon units can move multiple times per turn and have bigger spotting " +
            "range <span class='statsGlyph'>'</span> as the game will only show enemy units in the " +
            "spotting range of your units. Let's check if there aren't any hidden units near the city.",
        row = 8,
        col = 12,
    )
    move(b, row = 10, col = 14)
    message(
        "The objective is lightly defended by just one infantry unit. Let's move the rest of the " +
            "units into fire range, destroy the defender and capture the city. When units move watch " +
            "how the strength number color changes indicating that unit has moved.",
        row = 8,
        col = 15,
    )
}

private fun AIScripted.buildTurn1AxisAdvance(axisUnits: Map<String, GameUnit?>) {
    move(axisUnits["pz2a"], row = 7, col = 11)
    mount(axisUnits["ssinf1"])
    move(axisUnits["ssinf1"], row = 7, col = 15)
    mount(axisUnits["ssinf2"])
    move(axisUnits["ssinf2"], row = 8, col = 14)
    mount(axisUnits["ssinf3"])
    move(axisUnits["ssinf3"], row = 9, col = 14)
    mount(axisUnits["arty"])
    move(axisUnits["arty"], row = 7, col = 13)
    message(
        "As we moved all our units and no the targets are in fire range, we should end turn <span " +
            "class='smallButtonSubMenu'>t</span>which will start enemy turn. If we don't capture all " +
            "primary objectives in the maximum number of turns (shown left of the top bar) the " +
            "scenario will end in defeat.",
        row = 7,
        col = 13,
    )
}
