package org.osada.ai

import org.osada.ActionType
import org.osada.GameHolder
import org.osada.PlayerSide
import org.osada.PlayerType
import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.ui.UIBuilder
import kotlin.js.json

class AIScripted(private val player: Player, private val map: GameMap) {
    private val actions: MutableList<dynamic> = mutableListOf()

    init {
        buildTutorialActions()
    }

    @JsName("buildActions")
    fun buildActions() {
        buildTutorialActions()
    }

    @JsName("getAction")
    fun getAction(): dynamic? {
        if (actions.isEmpty()) return null
        return actions.removeAt(0)
    }

    private fun addAction(type: ActionType, params: Array<dynamic>) {
        actions.add(json(Pair("type", type.value), Pair("param", params)))
    }

    private fun unitAt(row: Int, col: Int): GameUnit? = map.map?.getOrNull(row)?.getOrNull(col)?.getUnit(false)

    private fun buildTutorialActions() {
        actions.clear()

        val axisUnits = mutableMapOf<String, GameUnit?>()
        val alliesUnits = mutableMapOf<String, GameUnit?>()
        axisUnits["recon"] = unitAt(8, 12)
        axisUnits["legioninf"] = unitAt(4, 2)
        axisUnits["pz2a"] = unitAt(9, 6)
        axisUnits["ssinf1"] = unitAt(8, 6)
        axisUnits["ssinf2"] = unitAt(9, 5)
        axisUnits["ssinf3"] = unitAt(9, 4)
        axisUnits["arty"] = unitAt(8, 4)
        alliesUnits["inf1"] = unitAt(8, 15)

        when (map.turn) {
            1 -> if (player.side == PlayerSide.AXIS.value) {
                message(
                    "The objective of the game is to conquer all enemy positions marked by a golden frame and enemy flag. This is one of the positions. Moving one of your units into this position will capture this city and raise your flag over it.",
                    8,
                    15,
                )
                message(
                    "This is the second primary objective since it has your enemy flag (Nationalist Spain) and a golden frame around. Capturing primary objectives gives you a good amount of points called prestige which can be used to upgrade or buy new troops.",
                    16,
                    8,
                )
                message(
                    "This is an optional objective with your enemy flag. Although capturing these  objectives won't count towards victory is good to do so for extra prestige and troops experience. Also troops are better reinforced or resupplied in a city.",
                    11,
                    22,
                )

                var b = axisUnits["legioninf"]
                select(b)
                message(
                    "This is one of your units and usually represent a historical battalion, the number below is battalion size, usually named strength. Since you are playing as the Republican (Loyalist) side the number is on a grey box. Your enemies (Nationalist Spain) will have this number box in green.",
                    4,
                    2,
                )
                message(
                    "Units have several stats, click the <span class='smallButtonSubMenu'>i</span> button on the right menu to open a panel with unit stats on the bottom of the screen. Notice the movement range <span class='statsGlyph'>?</span> for this unit is 3. The grey hexes around this unit represent this movement range.",
                    4,
                    2,
                )
                message(
                    "This unit has a transport, shown by the <span class='smallButtonSubMenu'>[</span> button on the bottom of the screen. While on transports moving range is increased but, except infantry, units become vulnerable to enemy fire. I will now mount and move this unit for you toward objective.",
                    4,
                    2,
                )
                mount(b)
                select(b)
                move(b, 7, 9)

                b = axisUnits["recon"]
                select(b)
                message(
                    "This is a recon unit. Recon units can move multiple times per turn and have bigger spotting range <span class='statsGlyph'>'</span> as the game will only show enemy units in the spotting range of your units. Let's check if there aren't any hidden units near the city.",
                    8,
                    12,
                )
                move(b, 10, 14)
                message(
                    "The objective is lightly defended by just one infantry unit. Let's move the rest of the units into fire range, destroy the defender and capture the city. When units move watch how the strength number color changes indicating that unit has moved.",
                    8,
                    15,
                )

                move(axisUnits["pz2a"], 7, 11)
                mount(axisUnits["ssinf1"])
                move(axisUnits["ssinf1"], 7, 15)
                mount(axisUnits["ssinf2"])
                move(axisUnits["ssinf2"], 8, 14)
                mount(axisUnits["ssinf3"])
                move(axisUnits["ssinf3"], 9, 14)
                mount(axisUnits["arty"])
                move(axisUnits["arty"], 7, 13)
                message(
                    "As we moved all our units and no the targets are in fire range, we should end turn <span class='smallButtonSubMenu'>t</span>which will start enemy turn. If we don't capture all primary objectives in the maximum number of turns (shown left of the top bar) the scenario will end in defeat.",
                    7,
                    13,
                )
            } else {
                attack(alliesUnits["inf1"], axisUnits["ssinf1"])
            }

            2 -> if (player.side == PlayerSide.AXIS.value) {
                message(
                    "Notice how this infantry unit jumped out of transport when attacked, instead of staying in the vulnerable transport. Only infantry units will do this others will not dismount when attached. All units are dismounted at the start of your turn.",
                    7,
                    15,
                )
                message(
                    "This is an artillery unit, depending on it's fire range <span class='statsGlyph'>&gt;</span> it can fire on enemy positions from afar without suffering losses. Also each attack will soften enemy positions reducing their <span class='statsGlyph'>&quot;</span> entrenchment.",
                    7,
                    13,
                )

                val arty = axisUnits["arty"]
                val inf1 = alliesUnits["inf1"]
                attack(arty, inf1)
                message(
                    "We inflicted losses to at least 1 company from the enemy battalion and reduced their entrenchment. Both attacker and defender received combat experience <span class='statsGlyph'>@</span> the later only a small amount since it hasn't inflicted any casualties.",
                    8,
                    15,
                )
                message(
                    "Entrenchment <span class='statsGlyph'>&quot;</span> of a unit is important as it can avoid loses completely. It's a good idea to always check and reduce entrenchment with artillery or bombers before attacking in close combat. Entrenchment is automatically increased depending on terrain each turn when a unit doesn't move.",
                    8,
                    15,
                )

                var b = unitAt(8, 14)
                message(
                    "Notice the small circle on the left of the unit strength box. The presence of this circle signifies that the unit hasn't fired yet and can do so. Some units like mounted artillery and mobile air defense can't move and fire in the same turn.",
                    8,
                    14,
                )
                attack(b, inf1)
                message(
                    "Infantry units are usually good at defending cities, as they force attacker in close combat <span class='statsGlyph'>6</span> instead of normal ground combat defense <span class='statsGlyph'>5</span>. Other terrain types (shown right of the top bar) influence combat differently for each unit type.",
                    8,
                    15,
                )

                b = unitAt(9, 14)
                attack(b, inf1)
                b = unitAt(7, 15)
                attack(b, inf1)
                message(
                    "Since the unit defending the city was destroyed or retreated because of heavy casualties we can now move one of our units to capture this objective and get our prestige reward.",
                    8,
                    15,
                )

                b = unitAt(7, 11)
                move(b, 8, 15)
                message(
                    "The city is now ours, and it's flag changed to your flag. This objective capture also increased your prestige by 150&nbsp;" +
                        UIBuilder.currencyIcon +
                        ". You can check prestige, score, remaining turns  and objectives by clicking anywhere on the top bar.",
                    8,
                    15,
                )
                attack(b, inf1)
                message(
                    "This is the last remaining primary objective. Let's gamble and try capturing it without trying to spot enemy. Capturing objectives in early turns gives a better score.",
                    16,
                    8,
                )

                b = unitAt(10, 14)
                select(b)
                move(b, 16, 8)
                message(
                    "Our unit was surprised by an enemy unit. This happens when you move a unit into an unspotted hex that's occupied by an enemy unit. Surprise attack reduces unit defense and attack values resulting in heavy casualties. Let move to the next turn.",
                    15,
                    10,
                )
                select(b)
            }

            3 -> if (player.side == PlayerSide.AXIS.value) {
                var u = unitAt(15, 10)
                select(u)
                message(
                    "You can reinforce this unit casualties by clicking on the <span class='smallButtonSubMenu'>#</span> button at the bottom of the screen. Reinforcements costs prestige and the amount is reduced by enemy units adjacent to your unit. After reinforce unit can no longer attack or move.",
                    15,
                    10,
                )
                reinforce(u)

                u = unitAt(7, 15)
                select(u)
                message(
                    "Let's move the rest of the units towards the objective, note how terrain influence movement range. The longest move range is on road or clear terrain but it also depends on unit movement type shown with symbol <span class='statsGlyph' style='float:none;'>~</span>",
                    7,
                    15,
                )
                mount(u)
                move(u, 14, 11)

                u = unitAt(8, 14)
                mount(u)
                select(u)
                move(u, 14, 9)

                u = unitAt(9, 14)
                mount(u)
                select(u)
                move(u, 14, 8)

                u = unitAt(7, 9)
                mount(u)
                select(u)
                move(u, 11, 9)

                u = unitAt(7, 13)
                mount(u)
                select(u)
                move(u, 13, 13)

                u = unitAt(8, 15)
                select(u)
                move(u, 12, 12)
            }

            4 -> if (player.side == PlayerSide.AXIS.value) {
                var u = unitAt(14, 9)
                select(u)
                message(
                    "Let's attack the weakest enemy unit on the front line. The attack cursor gives an estimate of your casualties on the left and enemy casualties on the right below the flags of the units involved in combat.",
                    15,
                    9,
                )
                var e = unitAt(15, 9)
                attack(u, e)
                message(
                    "This artillery unit has automatically fired when we attacked. That's because artillery provides support fire to friendly units in their range if attacked. Other units with combat support include air defense and fighter planes when attacked by an enemy plane.",
                    16,
                    7,
                )
                message(
                    "Luckily our artillery unit has a bigger range than enemy artillery so we can safely move it into fire range without making us vulnerable to enemy fire while in transport.",
                    13,
                    13,
                )

                u = unitAt(13, 13)
                mount(u)
                move(u, 14, 12)
                message(
                    "Armored units are considered hard targets <span class='statsGlyph'>`</span> and will take less casualties from artillery fire than a soft target like infantry or artillery. In some cases armored units are used to deplete the ammo of the defending artillery.",
                    12,
                    12,
                )

                u = unitAt(12, 12)
                move(u, 14, 7)
                e = unitAt(15, 8)
                attack(u, e)

                u = unitAt(15, 10)
                if (u != null) u.fuel = 0
                select(u)
                message(
                    "This unit it's out of fuel and it can no longer move.You can always supply ammo <span class='statsGlyph'></span> and fuel to your units by clicking on the <span class='smallButtonSubMenu'>!</span>   button on the bottom of the screen",
                    15,
                    10,
                )
                resupply(u)

                GameHolder.instance?.ui?.mainMenuButton("mainmenu")
                message(
                    "This concludes the tutorial and gives you the control of the army to capture the last objective. Remember to use your prestige to upgrade or buy new units by clicking on the <span class='smallButtonSubMenu'>w</span>  button from the menu on the right side of the screen.",
                    16,
                    8,
                )
                message(
                    "You should attack enemy artillery with your own artillery and move your tanks and attack the enemy artillery. There is a possibility that your armor attack will result in an OverRun which completely destroys an unit and allows your armor to continue move and attack.",
                    16,
                    8,
                )
                message(
                    "Remember to tap or click the <span class='smallButtonSubMenu'>t</span> end turn button twice to end your turns. Depending on scenario you or the enemy might also receive reinforcements. On this tutorial you will receive reinforcements next turn.",
                    16,
                    8,
                )
            }

            else -> {
                if (player.side == PlayerSide.AXIS.value) {
                    player.handler = null
                    player.type = PlayerType.HUMAN_LOCAL
                } else {
                    player.handler = AI(player, map)
                    player.type = PlayerType.AI_LOCAL
                }
            }
        }
    }

    private fun message(text: String, row: Int, col: Int) {
        addAction(ActionType.MESSAGE, arrayOf(text, Cell(row, col)))
    }

    private fun select(unit: GameUnit?) {
        unit?.let { addAction(ActionType.SELECT, arrayOf(it)) }
    }

    private fun move(unit: GameUnit?, row: Int, col: Int) {
        unit?.let { addAction(ActionType.MOVE, arrayOf(it, Cell(row, col))) }
    }

    private fun attack(attacker: GameUnit?, defender: GameUnit?) {
        if (attacker != null && defender != null) {
            addAction(ActionType.ATTACK, arrayOf(attacker, defender))
        }
    }

    private fun mount(unit: GameUnit?) {
        unit?.let { addAction(ActionType.MOUNT, arrayOf(it)) }
    }

    private fun reinforce(unit: GameUnit?) {
        unit?.let { addAction(ActionType.REINFORCE, arrayOf(it)) }
    }

    private fun resupply(unit: GameUnit?) {
        unit?.let { addAction(ActionType.RESUPPLY, arrayOf(it)) }
    }
}
