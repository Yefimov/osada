package org.osada.ai

import org.osada.PlayerSide
import org.osada.model.GameUnit
import org.osada.ui.UIBuilder

// Turn 2 of AIScripted's scripted tutorial -- split out of buildTutorialActions (LongMethod) and
// into its own file (keeps AIScriptedHelpers.kt's per-turn siblings under detekt's per-file
// TooManyFunctions budget too).

internal fun AIScripted.buildTurn2Actions(
    axisUnits: Map<String, GameUnit?>,
    alliesUnits: Map<String, GameUnit?>,
) {
    if (player.side == PlayerSide.AXIS.value) {
        buildTurn2AxisIntro()
        val inf1 = alliesUnits["inf1"]
        buildTurn2AxisArtyAttack(axisUnits, inf1)
        buildTurn2AxisCityAssault(inf1)
        buildTurn2AxisCapture(inf1)
    }
}

private fun AIScripted.buildTurn2AxisIntro() {
    message(
        "Notice how this infantry unit jumped out of transport when attacked, instead of staying in " +
            "the vulnerable transport. Only infantry units will do this others will not dismount " +
            "when attached. All units are dismounted at the start of your turn.",
        row = 7,
        col = 15,
    )
    message(
        "This is an artillery unit, depending on it's fire range <span " +
            "class='statsGlyph'>&gt;</span> it can fire on enemy positions from afar without " +
            "suffering losses. Also each attack will soften enemy positions reducing their <span " +
            "class='statsGlyph'>&quot;</span> entrenchment.",
        row = 7,
        col = 13,
    )
}

private fun AIScripted.buildTurn2AxisArtyAttack(
    axisUnits: Map<String, GameUnit?>,
    inf1: GameUnit?,
) {
    val arty = axisUnits["arty"]
    attack(arty, inf1)
    message(
        "We inflicted losses to at least 1 company from the enemy battalion and reduced their " +
            "entrenchment. Both attacker and defender received combat experience <span " +
            "class='statsGlyph'>@</span> the later only a small amount since it hasn't inflicted any " +
            "casualties.",
        row = 8,
        col = 15,
    )
    message(
        "Entrenchment <span class='statsGlyph'>&quot;</span> of a unit is important as it can avoid " +
            "loses completely. It's a good idea to always check and reduce entrenchment with " +
            "artillery or bombers before attacking in close combat. Entrenchment is automatically " +
            "increased depending on terrain each turn when a unit doesn't move.",
        row = 8,
        col = 15,
    )
}

private fun AIScripted.buildTurn2AxisCityAssault(inf1: GameUnit?) {
    var b = unitAt(row = 8, col = 14)
    message(
        "Notice the small circle on the left of the unit strength box. The presence of this circle " +
            "signifies that the unit hasn't fired yet and can do so. Some units like mounted " +
            "artillery and mobile air defense can't move and fire in the same turn.",
        row = 8,
        col = 14,
    )
    attack(b, inf1)
    message(
        "Infantry units are usually good at defending cities, as they force attacker in close combat " +
            "<span class='statsGlyph'>6</span> instead of normal ground combat defense <span " +
            "class='statsGlyph'>5</span>. Other terrain types (shown right of the top bar) influence " +
            "combat differently for each unit type.",
        row = 8,
        col = 15,
    )

    b = unitAt(row = 9, col = 14)
    attack(b, inf1)
    b = unitAt(row = 7, col = 15)
    attack(b, inf1)
    message(
        "Since the unit defending the city was destroyed or retreated because of heavy casualties we " +
            "can now move one of our units to capture this objective and get our prestige reward.",
        row = 8,
        col = 15,
    )
}

private fun AIScripted.buildTurn2AxisCapture(inf1: GameUnit?) {
    var b = unitAt(row = 7, col = 11)
    move(b, row = 8, col = 15)
    message(
        "The city is now ours, and it's flag changed to your flag. This objective capture " +
            "also increased your prestige by 150&nbsp;" +
            UIBuilder.currencyIcon +
            ". You can check prestige, score, remaining turns  and objectives by clicking anywhere " +
            "on the top bar.",
        row = 8,
        col = 15,
    )
    attack(b, inf1)
    message(
        "This is the last remaining primary objective. Let's gamble and try capturing it without " +
            "trying to spot enemy. Capturing objectives in early turns gives a better score.",
        row = 16,
        col = 8,
    )

    b = unitAt(row = 10, col = 14)
    select(b)
    move(b, row = 16, col = 8)
    message(
        "Our unit was surprised by an enemy unit. This happens when you move a unit into an " +
            "unspotted hex that's occupied by an enemy unit. Surprise attack reduces unit defense " +
            "and attack values resulting in heavy casualties. Let move to the next turn.",
        row = 15,
        col = 10,
    )
    select(b)
}
