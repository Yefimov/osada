package org.osada.ai

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
        "Primary objectives have an enemy flag inside a golden frame. Capture this crossing by moving " +
            "one of your ground units onto the hex after its defender has been removed.",
        row = 4,
        col = 7,
    )
    message(
        "Hamar-Daba is the second primary objective. Both primary objectives must be held to win, and " +
            "capturing them awards prestige for reinforcement, upgrades, and new units.",
        row = 7,
        col = 8,
    )
    message(
        "Bain-Tsagan Observation Point is optional: it is not required for victory, but it offers extra " +
            "prestige, experience, and a useful forward position.",
        row = 4,
        col = 3,
    )

    val infantry = unitByEqid(SOVIET_MG_INFANTRY, SOVIET_OWNER)
    select(infantry)
    message(
        "The number beside a unit is its strength. It represents how much of the formation remains " +
            "combat-effective. The colour and small status markers also show whether the unit has moved " +
            "or fired during the current turn.",
        row = 3,
        col = 11,
    )
    message(
        "Selecting a unit opens its Unit Info card in the lower-left corner. Press <b>ALL STATS</b> " +
            "to expand movement, spotting, attack, defence, ammunition, fuel, experience, and " +
            "entrenchment. Highlighted hexes show the unit's current movement range.",
        row = 3,
        col = 11,
    )
    message(
        "This machine-gun battalion has truck transport. Press the truck button to mount or dismount it: " +
            "<span class='smallButtonSubMenu' " +
            "style='float:none;display:inline-block;vertical-align:middle;'>[</span><br>" +
            "Transport greatly improves movement on roads, but mounted units are vulnerable. " +
            "Infantry automatically dismounts when attacked.",
        row = 3,
        col = 11,
    )
    mount(infantry)
    select(infantry)
    move(infantry, row = 4, col = 10)

    val recon = unitByEqid(SOVIET_BA_10, SOVIET_OWNER)
    select(recon)
    message(
        "The BA-10 is a reconnaissance unit. Recon has a larger spotting range and may move in several " +
            "stages, making it ideal for revealing ambushes before slower units advance.",
        row = 8,
        col = 9,
    )
    move(recon, row = 7, col = 10)
    message(
        "Roads are usually fastest, while hills, rivers, marshes, and rough ground reduce movement in " +
            "different ways. Scout first, then commit the tanks and infantry.",
        row = 7,
        col = 10,
    )
    message(
        "The first demonstration turn is complete. A scenario is lost if its primary objectives are not " +
            "taken before the final turn shown on the top bar.",
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
        "The Japanese counterattack forced the transported infantry to dismount automatically. " +
            "The nearby Soviet battery also provided support fire: artillery may fire automatically " +
            "when a friendly ground unit inside its range is attacked.",
        row = 4,
        col = 10,
    )

    val artillery = unitByEqid(SOVIET_ARTILLERY, SOVIET_OWNER)
    val defender = unitByEqid(JAPANESE_FORWARD_INFANTRY, JAPANESE_OWNER)
    select(artillery)
    message(
        "The crossing is held by entrenched infantry. Artillery attacks from range, normally avoids " +
            "return fire, and reduces entrenchment — this is artillery preparation or softening.",
        row = 6,
        col = 8,
    )
    message(
        "Before attacking, the combat cursor estimates losses for both sides. Treat the estimate as a " +
            "warning rather than a promise: terrain, experience, entrenchment, initiative, and support " +
            "fire can all change the result.",
        row = 4,
        col = 7,
    )
    attack(artillery, defender)
    message(
        "That was a real bombardment: the defender lost strength and/or entrenchment, ammunition was " +
            "spent, and both formations gained combat experience. Prepared positions should be softened " +
            "before a close assault.",
        row = 4,
        col = 7,
    )

    val tank = unitByEqid(SOVIET_BT_5, SOVIET_OWNER)
    select(tank)
    message(
        "Infantry and artillery are soft targets; tanks are hard targets. Different weapons use different " +
            "attack values against them. The BT-5 is now positioned to exploit the weakened line next turn.",
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
        "Reinforcement restores lost strength and costs prestige. Enemy units nearby reduce the result, " +
            "and a reinforced unit cannot move or attack again during that turn.",
        row = 4,
        col = 10,
    )
    reinforce(infantry)

    val tank = unitByEqid(SOVIET_BT_5, SOVIET_OWNER)
    if (tank != null) tank.fuel = 0
    select(tank)
    message(
        "Units consume ammunition and, when motorised, fuel. A unit without ammunition cannot attack, " +
            "and a motorised unit without fuel cannot move. Press the barrel button to restore both: " +
            "<span class='smallButtonSubMenu' " +
            "style='float:none;display:inline-block;vertical-align:middle;'>!</span>",
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
        "The artillery preparation has left the forward company at breaking point. A tank attacking an " +
            "adjacent unit can score an OverRun when it destroys the defender while suffering no more " +
            "than one loss.",
        row = 4,
        col = 7,
    )
    attack(tank, defender)
    message(
        "<b>OverRun!</b> The weakened unit was destroyed and the BT-5 remains able to continue its advance. " +
            "OverRun is the reward for preparing the attack and committing armour at the right moment.",
        row = 4,
        col = 7,
    )
    move(tank, row = 4, col = 7)
    message(
        "Khaylastyn Crossing has been captured. The flag changes to your side and the prestige award is " +
            "added to the top bar. Early objective captures also improve the final score.",
        row = 4,
        col = 7,
    )
    modalMessage(
        title = "You Are Now in Command",
        body =
            "<b>Well done, Commander.</b><br><br>" +
                "The three-turn demonstration is complete. The next Soviet turn is yours.<br><br>" +
                "Capture the Hamar-Daba Command Post to win. Bain-Tsagan Observation Point is optional.<br><br>" +
                "Scout first, soften entrenched defenders with artillery, and use armour to exploit the breach.",
    )
}
