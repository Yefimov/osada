@file:Suppress("unused") // Compatibility constants mirror the original JS public vocabulary.

package org.osada

// Enums and constants ported from the top of osada.js

@JsExport
enum class UnitType(
    val value: Int,
) {
    NONE(-1),
    SOFT(0),
    HARD(1),
    AIR(2),
    SEA(3),
}

val unitTypeNames = listOf("Soft", "Hard", "Air", "Sea")

enum class UnitClass(
    val value: Int,
) {
    NONE(0),
    INFANTRY(1),
    TANK(2),
    RECON(3),
    ANTI_TANK(4),
    FLAK(5),
    FORTIFICATION(6),
    GROUND_TRANSPORT(7),
    ARTILLERY(8),
    AIR_DEFENCE(9),
    FIGHTER(10),
    TACTICAL_BOMBER(11),
    LEVEL_BOMBER(12),
    AIR_TRANSPORT(13),
    SUBMARINE(14),
    DESTROYER(15),
    BATTLESHIP(16),
    CARRIER(17),
    NAVAL_TRANSPORT(18),
    BATTLE_CRUISER(19),
    CRUISER(20),
    LIGHT_CRUISER(21),
}

val unitClassNames =
    listOf(
        "No Class",
        "Infantry",
        "Tank",
        "Recon",
        "Anti Tank",
        "Flak",
        "Fortification",
        "Ground Transport",
        "Artillery",
        "Air Defence",
        "Fighter Aircraft",
        "Tactical Bomber",
        "Level Bomber",
        "Air Transport",
        "Submarine",
        "Destroyer",
        "Battleship",
        "Aircraft Carrier",
        "Naval Transport",
        "Battle Cruiser",
        "Cruiser",
        "Light Cruiser",
    )

val unitEntrenchRate = listOf(0, 3, 1, 2, 2, 1, 1, 1, 2, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

/**
 * [unitEntrenchRate] for [uclass], or 0 for a class outside the table.
 *
 * Never index [unitEntrenchRate] directly. OG's own class enum is 24 wide (0..23) against this
 * PM-derived 22, and some efiles carry values outside even OG's range — `eqp-atomic` and
 * `eqp-united` each hold one record with `uclass = 82`. A raw `unitEntrenchRate[uclass]` threw
 * `IndexOutOfBoundsException` out of `UnitPredicates.canEntrench`, which `unitEndTurn` calls for
 * every unit at every turn change: the exception escaped the AI turn loop, so the turn never
 * completed and the "Computer turn complete" banner stayed on screen forever (user report,
 * Willhelmshafen turn 2). A class this code has no rule for simply does not dig in.
 */
fun entrenchRateFor(uclass: Int): Int = unitEntrenchRate.getOrElse(uclass) { 0 }

/** 0-based (index 0 = January), matching JS `Date.getMonth()`. Equipment `monthavailable`/
 *  `monthexpired` are the OG CSV's own 1-based convention instead — subtract 1 when indexing here. */
val monthNamesShort = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

enum class RoadType(
    val value: Int,
) {
    NONE(0),
    NORTH(1),
    NORTHEAST(2),
    EAST_UNUSED(4),
    SOUTHEAST(8),
    SOUTH(16),
    SOUTHWEST(32),
    WEST_UNUSED(64),
    NORTHWEST(128),
}

enum class Direction(
    val value: Int,
) {
    S(0),
    SSE(1),
    SE(2),
    ESE(3),
    E(4),
    ENE(5),
    NE(6),
    NNE(7),
    N(8),
    NNW(9),
    NW(10),
    WNW(11),
    W(12),
    WSW(13),
    SW(14),
    SSW(15),
}

enum class TerrainType(
    val value: Int,
) {
    CLEAR(0),
    CITY(1),
    AIRFIELD(2),
    FOREST(3),
    BOCAGE(4),
    HILL(5),
    MOUNTAIN(6),
    SAND(7),
    SWAMP(8),
    OCEAN(9),
    RIVER(10),
    FORTIFICATION(11),
    PORT(12),
    STREAM(13),
    ESCARPMENT(14),
    IMPASSABLE_RIVER(15),
    ROUGH(16),
}

val terrainNames =
    listOf(
        "Clear",
        "City",
        "Airfield",
        "Forest",
        "Bocage",
        "Hill",
        "Mountain",
        "Sand",
        "Swamp",
        "Ocean",
        "River",
        "Fortification",
        "Port",
        "Stream",
        "Escarpment",
        "Impassable river",
        "Rough",
    )

val terrainEntrenchment = listOf(0, 3, 0, 2, 2, 1, 2, 0, 0, 0, 0, 4, 1, 0, 0, 0, 2)
val terrainInitiative = listOf(99, 1, 99, 3, 3, 5, 1, 99, 2, 99, 99, 3, 5, 99, 99, 99, 3, 1)

enum class GroundCondition(
    val value: Int,
) {
    DRY(0),
    FROZEN(1),
    MUD(2),
}

val groundConditionNames = listOf("Dry", "Frozen", "Mud")
val groundFontEncoding = listOf("6", "8", "7")

enum class WeatherCondition(
    val value: Int,
) {
    FAIR(0),
    OVERCAST(1),
    RAIN(2),
    SNOW(3),
}

val weatherConditionNames = listOf("Fair", "Overcast", "Raining", "Snowing")
val weatherFontEncoding = listOf("4", "5", "1", "2")

/* Image icons (resources/ui/osada/, extracted from the 08_plain_icon_glyphs asset sheet) —
 * replace the osada icon-font glyphs above in OSADA chrome; indexed like the name lists. */
val weatherIconFiles = listOf("clear", "overcast", "rain", "snow")
val groundIconFiles = listOf("dry", "frozen", "mud")

fun weatherIconImg(
    atmos: Int,
    cssClass: String,
): String =
    weatherIconFiles.getOrNull(atmos)?.let {
        "<img class=\"$cssClass\" src=\"resources/ui/osada/ico_weather_$it.png\" alt=\"\">"
    } ?: ""

fun groundIconImg(
    ground: Int,
    cssClass: String,
): String =
    groundIconFiles.getOrNull(ground)?.let {
        "<img class=\"$cssClass\" src=\"resources/ui/osada/ico_ground_$it.png\" alt=\"\">"
    } ?: ""

enum class PlayerSide(
    val value: Int,
) {
    AXIS(0),
    ALLIES(1),
}

val sideNames = listOf("Axis", "Allies")

object TooltipColor {
    const val PLAYER = 0
    const val ENEMY = 1
}

object TooltipStyle {
    const val TEXT = 0
    const val PIN = 1
}

enum class MovMethod(
    val value: Int,
) {
    TRACKED(0),
    HALF_TRACKED(1),
    WHEELED(2),
    LEG(3),
    TOWED(4),
    AIR(5),
    DEEP_NAVAL(6),
    COASTAL(7),
    ALL_TERRAIN_TRACKED(8),
    AMPHIBIOUS(9),
    NAVAL(10),
    ALL_TERRAIN_LEG(11),

    // Armored trains (e.g. the Perekop Bronevagons) — confined to hex.rail by
    // MovementRules.getMoveRange's isTrain check, NOT by this row's own table values (see the
    // movTable*[12] comment below). OG's own movement method 12 ("Rail"); the import pipeline
    // used to fold it into WHEELED(2) for lack of a PM equivalent (tools/og-import/csv_to_eqp.py).
    RAIL(12),
}

val movMethodNames =
    listOf(
        "Tracked",
        "Half Tracked",
        "Wheeled",
        "Leg",
        "Towed",
        "Air",
        "Deep Naval",
        "Costal",
        "All Terrain",
        "Amphibious",
        "Naval",
        "Mountain Leg",
        "Rail",
    )

@JsExport
enum class PlayerType(
    val value: Int,
) {
    HUMAN_LOCAL(0),
    HUMAN_NETWORK(1),
    AI_LOCAL(2),
    AI_SERVER(3),
    AI_SCRIPTED(4),
}

enum class ActionType(
    val value: Int,
) {
    MOVE(0),
    ATTACK(1),
    RESUPPLY(2),
    REINFORCE(3),
    UPGRADE(4),
    BUY(5),
    DEPLOY(6),
    MOUNT(7),
    UMOUNT(8),
    SELECT(9),
    END_TURN(10),
    MESSAGE(11),
    VIEWPORT(12),
    MODAL_MESSAGE(13),
}

enum class EmbarkType(
    val value: Int,
) {
    NONE(0),
    NAVAL(1),
    AIR_MOBILE(2),
    AIRBORNE(3),
}

enum class DifficultyType(
    val value: Int,
) {
    HISTORICAL(0),
    TACTICAL(1),
    OPERATIONAL(2),
}

enum class LeaderType(
    val value: Int,
) {
    MECHANIZED_VETERAN(1),
    TANK_KILLER(2),
    MARKSMAN(3),
    SKILLED_INTERCEPTOR(4),
    TENACIOUS_DEFENSE(5),
    ELITE_RECON_VETERAN(6),
    SKILLED_ASSAULT(7),
    AGGRESSIVE_TANK_MANEUVER(8),
    AGGRESSIVE_ATTACK(9),
    AGGRESSIVE_MANEUVER(10),
    ALL_WEATHER_COMBAT(11),
    ALPINE_TRAINING(12),
    BATTLEFIELD_INTELLIGENCE(13),
    BRIDGING(14),
    COMBAT_SUPPORT(15),
    DETERMINED_DEFENSE(16),
    DEVASTATING_FIRE(17),
    FEROCIOUS_DEFENSE(18),
    FIRE_DISCIPLINE(19),
    FIRST_STRIKE(20),
    FOREST_CAMOUFLAGE(21),
    INFILTRATION_TACTICS(22),
    INFLUENCE(23),
    LIBERATOR(24),
    OVERWATCH(25),
    OVERWHELMING_ATTACK(26),
    RECON_MOVEMENT(27),
    RESILIENCE(28),
    SHOCK_TACTICS(29),
    SKILLED_GROUND_ATTACK(30),
    SKILLED_RECONNAISSANCE(31),
    STREET_FIGHTER(32),
    SUPERIOR_MANEUVER(33),
}

enum class EndGameType {
    MOVE_CAPTURE,
    NO_TURNS_LEFT,
    NO_ENEMY_LEFT,
}

val endGameLossText =
    mapOf(
        EndGameType.MOVE_CAPTURE to "Enemy has captured all your objectives !<br>",
        EndGameType.NO_TURNS_LEFT to "You don't have any turns left !<br>",
        EndGameType.NO_ENEMY_LEFT to "All your units had been destroyed !<br>",
    )

val outcomeNames =
    mapOf(
        "lose" to "Defeat",
        "victory" to "Victory",
        "tactical" to "Tactical Victory",
        "briliant" to "Brilliant Victory",
    )

// Movement tables (dry/frozen/mud). 12 legacy movement methods x 18 columns (terrain 0-16 +
// road at [17]) reference osada-legacy-2.3.14.js exactly (see ConstantsConsistencyTest).
// Row 12 (RAIL) is OSADA-added and has no legacy reference: it is intentionally all-255
// (impassable via this table) because a train's real legality gate is the isTrain + hex.rail
// check in MovementRules.getMoveRange (and the same-shaped checks in getDisembarkPositions,
// getReinforcementDeployPositions, CombatResolver.getRetreatPosition) — this row only exists so
// movTable[RAIL.value] stays in-bounds for any caller that indexes it without that guard.
val movTableDry: List<List<Int>> =
    listOf(
        listOf(1, 1, 1, 2, 4, 2, 254, 1, 4, 255, 254, 1, 1, 2, 255, 255, 2, 1),
        listOf(1, 1, 1, 2, 254, 2, 254, 1, 4, 255, 254, 1, 1, 2, 255, 255, 2, 1),
        listOf(2, 1, 1, 4, 254, 3, 254, 3, 254, 255, 254, 2, 1, 4, 255, 255, 2, 1),
        listOf(1, 1, 1, 2, 2, 2, 254, 2, 2, 255, 254, 1, 1, 1, 255, 255, 2, 1),
        listOf(1, 1, 1, 1, 1, 1, 254, 1, 255, 255, 254, 1, 1, 254, 255, 255, 1, 1),
        listOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
        listOf(255, 255, 255, 255, 255, 255, 255, 255, 255, 1, 255, 255, 1, 255, 255, 255, 255, 255),
        listOf(255, 255, 255, 255, 255, 255, 255, 255, 255, 2, 1, 255, 1, 255, 255, 255, 255, 255),
        listOf(1, 1, 1, 2, 3, 3, 254, 2, 254, 255, 254, 1, 1, 1, 255, 255, 3, 1),
        listOf(1, 1, 1, 2, 4, 2, 254, 1, 3, 254, 3, 1, 1, 2, 255, 255, 2, 1),
        listOf(255, 255, 255, 255, 255, 255, 255, 255, 255, 1, 255, 255, 1, 255, 255, 255, 255, 255),
        listOf(1, 1, 1, 1, 2, 1, 1, 2, 2, 255, 254, 1, 1, 1, 255, 255, 1, 1),
        listOf(255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255),
    )

val movTableFrozen: List<List<Int>> =
    listOf(
        listOf(1, 1, 1, 2, 4, 2, 254, 1, 2, 255, 2, 1, 1, 2, 255, 255, 2, 1),
        listOf(1, 1, 1, 2, 254, 3, 254, 1, 2, 255, 2, 1, 1, 2, 255, 255, 3, 1),
        listOf(2, 2, 2, 254, 254, 254, 254, 3, 3, 255, 3, 3, 2, 4, 255, 255, 4, 2),
        listOf(1, 1, 1, 2, 2, 2, 254, 2, 1, 255, 2, 1, 1, 1, 255, 255, 2, 1),
        listOf(1, 1, 1, 1, 1, 1, 254, 1, 1, 255, 254, 1, 1, 254, 255, 255, 1, 1),
        listOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
        listOf(255, 255, 255, 255, 255, 255, 255, 255, 255, 1, 255, 255, 1, 255, 255, 255, 255, 255),
        listOf(255, 255, 255, 255, 255, 255, 255, 255, 255, 2, 255, 255, 1, 255, 255, 255, 255, 255),
        listOf(1, 1, 1, 2, 3, 3, 254, 2, 3, 255, 2, 1, 1, 1, 255, 255, 3, 1),
        listOf(1, 1, 1, 2, 4, 3, 254, 1, 3, 254, 2, 2, 1, 2, 255, 255, 3, 1),
        listOf(255, 255, 255, 255, 255, 255, 255, 255, 255, 1, 255, 255, 1, 255, 255, 255, 255, 255),
        listOf(1, 1, 1, 1, 2, 1, 2, 2, 1, 255, 2, 1, 1, 1, 255, 255, 2, 1),
        listOf(255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255),
    )

val movTableMud: List<List<Int>> =
    listOf(
        listOf(2, 1, 1, 2, 4, 3, 254, 1, 254, 255, 254, 2, 1, 2, 255, 255, 3, 2),
        listOf(3, 1, 1, 2, 254, 3, 254, 1, 254, 255, 254, 2, 1, 2, 255, 255, 3, 2),
        listOf(4, 2, 2, 254, 254, 254, 254, 3, 254, 255, 254, 4, 2, 4, 255, 255, 254, 2),
        listOf(2, 1, 1, 2, 2, 2, 254, 2, 1, 255, 254, 2, 1, 1, 255, 255, 3, 1),
        listOf(2, 1, 1, 1, 1, 2, 254, 1, 255, 255, 254, 21, 1, 254, 255, 255, 2, 2),
        listOf(2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
        listOf(255, 255, 255, 255, 255, 255, 255, 255, 255, 1, 255, 255, 1, 255, 255, 255, 255, 255),
        listOf(255, 255, 255, 255, 255, 255, 255, 255, 255, 2, 255, 255, 1, 255, 255, 255, 255, 255),
        listOf(2, 1, 1, 2, 3, 3, 254, 2, 3, 255, 255, 2, 1, 1, 255, 255, 4, 2),
        listOf(1, 1, 1, 2, 4, 3, 254, 1, 3, 254, 3, 2, 1, 2, 255, 255, 3, 1),
        listOf(255, 255, 255, 255, 255, 255, 255, 255, 255, 1, 255, 255, 1, 255, 255, 255, 255, 255),
        listOf(2, 1, 1, 1, 3, 1, 3, 2, 1, 255, 254, 2, 1, 1, 255, 255, 3, 1),
        listOf(255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255),
    )

var movTable: List<List<Int>> = movTableDry

val directionToRadians =
    listOf(
        kotlin.math.PI,
        5 * kotlin.math.PI / 6,
        3 * kotlin.math.PI / 4,
        2 * kotlin.math.PI / 3,
        kotlin.math.PI / 2,
        kotlin.math.PI / 3,
        kotlin.math.PI / 4,
        kotlin.math.PI / 6,
        0.0,
        11 * kotlin.math.PI / 6,
        7 * kotlin.math.PI / 4,
        5 * kotlin.math.PI / 3,
        3 * kotlin.math.PI / 2,
        4 * kotlin.math.PI / 3,
        5 * kotlin.math.PI / 4,
        7 * kotlin.math.PI / 6,
    )

const val UNIT_MAX_EXPERIENCE = 500

/** Max length of a player-given unit name (rename feature). */
const val UNIT_NAME_MAX_LENGTH = 24
const val UNIT_RETREAT_THRESHOLD = 0.6

/**
 * Whether a defender that must retreat but has nowhere legal to go is destroyed as surrendered.
 *
 * DIVERGENCE FROM PM 3.2.14 (deliberate, user-approved 2026-07-20). PM silently does nothing in
 * this case — the unit stays put, unharmed — which makes encirclement meaningless. Both source
 * games destroy it: OG surrenders the unit unless it has Ferocious Defense / No Surrender, and
 * Panzer Corps 2 surrenders a unit that cannot complete a legal retreat. Flag kept so the PM
 * behaviour can be restored if an imported campaign turns out to depend on it.
 */
const val SURRENDER_ON_FAILED_RETREAT = true

/**
 * Share of the surrendered unit's surviving value paid to the captor as prestige.
 *
 * Panzer Corps 2 awards the full cost of the strength points still standing when a unit
 * surrenders, which is what makes encirclement an economic choice rather than just a faster kill:
 * shelling a unit to death destroys that value, forcing its surrender banks it. 1.0 reproduces PC2.
 * Tunable because it is a real balance lever over imported OG campaigns, which were designed with
 * no such income; drop it if surrender proves too lucrative.
 */
const val SURRENDER_PRESTIGE_FRACTION = 1.0
const val CURRENCY_MULTIPLIER = 12
const val UPGRADE_PENALTY = 1.2
const val OVERSTRENGTH_PENALTY = 1.2
const val SCENARIO_START_PRESTIGE = 2000
const val PROTOTYPE_MIN_COST = 200
const val DEBUG_CAMPAIGN = false
const val DEBUG_AI_MOVES = false

// Bumped to 3.3.x for the eqp-united equipment merge: every eqid/country code was renumbered
// (see tools/eqp-merge/), so autosaves under the old "3.2" localStorage key namespace must not
// be picked up by GameStatePersistence.restore() -- they'd reference ids that no longer exist.
const val VERSION = "3.3.0"
const val NATIVE_PLATFORM = "generic"

val prestigeGains =
    mapOf(
        "flagCapture" to 50,
        "objectiveCapture" to 150,
    )

val scoreGains =
    mapOf(
        "coreUnit" to -15,
        "normalUnit" to -5,
        "objectivePerTurn" to 1000,
        "flagCapture" to 50,
        "objectiveCapture" to 100,
        "endTurn" to -10,
        "damage" to 10,
        "casualty" to -5,
        "casualtyCore" to -10,
        "reinforce" to -5,
        "resupply" to -10,
    )

val difficultyModifiers =
    mapOf(
        0 to DifficultyModifier(0.0, 0.0, 1.0, 1.0),
        1 to DifficultyModifier(0.2, 0.1, 1.2, 0.8),
        2 to DifficultyModifier(0.5, 0.25, 1.5, 0.5),
    )

data class DifficultyModifier(
    val startPrestige: Double,
    val turnPrestige: Double,
    val extraTurns: Double,
    val scoreCoef: Double,
)

// UI settings object skeleton
class UiSettings {
    var airMode: Boolean = false
    var strategicZoom: Boolean = false
    var strategicZoomLevel: Double = 1.0
    var mapZoom: Boolean = false
    var zoomLevel: Double = 1.0
    var uiScale: Double = 1.0
    var uiSize: Int = 840
    var uiSmallSize: Int = 470
    var hexGrid: Boolean = false
    var showGridTerrain: Boolean = false
    var muteUnitSounds: Boolean = false

    // Default 0.5, not 1.0: unit/fire sound clips play at full native volume with no
    // attenuation anywhere (Sound.kt), which was reported as simply too loud.
    var soundVolume: Double = 0.5

    // Continuous background loops (weather ambience) get their own level, separate from the
    // discrete unit/fire cues — replaces the old hardcoded soundVolume*0.8 coupling.
    var ambientVolume: Double = 0.4
    var deployMode: Boolean = false

    /** Whether the selected formation is choosing a hex to shell (OG 9.2, `rules/Barrage`).
     *  Set by the Barrage chip, cleared by firing, by pressing it again, or by selecting anything
     *  else -- a mode the player can enter by accident must be one they can leave the same way. */
    var barrageMode: Boolean = false
    var markCombatUnits: Boolean = true
    var markOwnUnits: Boolean = false
    var markEnemyUnits: Boolean = false
    var markFOW: Boolean = false

    /**
     * Accessibility: adds a star to friendly and a skull to enemy strategic unit flags
     * (`docs/design/accessible-side-identification.md`). Device-local, opt-in, and deliberately
     * NOT part of the Observer badge -- it adds no information the player could not already read
     * from the flag, it only adds a second channel for reading it.
     */
    var enhancedSideMarkers: Boolean = false
    var noFOW: Boolean = false
    var quickAnimation: Boolean = false
    var hasTouch: Boolean = false
    var use3D: Boolean = false
    var useRetina: Boolean = false
    var allowZoom: Boolean = false
    var isAI: MutableList<Int> = MutableList(4) { if (it == 0) 0 else 1 }
    var shownEndTurnTip: Boolean = false

    // OSADA UX: selecting a unit shows its info panel by default (modern HUD behavior);
    // the Inspect Unit toggle can still hide it.
    var unitInfoVisibility: Boolean = true
    var showInfoToolTips: Boolean = true
    var showDetailInfoToolTips: Boolean = false
    var showHiddenVictoryHexes: Boolean = false

    // OSADA: gate the top-bar inline End-Turn confirm (Task 1 state machine); default on.
    var confirmEndTurn: Boolean = true

    // Optional player-only power mode. Kept in UI settings so it survives scenario transitions.
    var stalinRegime: Boolean = false

    // ---- Mobile browser experience ----
    // Only real user PREFERENCES live here. The layout mode itself is derived from the measured
    // viewport every frame and is deliberately NOT persisted: a temporary viewport (a rotated
    // phone, a split screen, a resized window) must never become a stored setting.

    /** Mobile interface: "auto" (measure the device), "on" (force), "off" (force desktop). */
    var mobileUiMode: String = "auto"

    /** Confirm attacks before they execute: "auto" (on for a coarse pointer), "on", "off". */
    var confirmAttacks: String = "auto"

    /** Control density on touch layouts: "compact", "standard", "large". */
    var interfaceDensity: String = "standard"

    /** Highest gesture-tutorial version the player has dismissed; 0 means never shown. */
    var gestureTutorialVersion: Int = 0

    /** Shorter animations and less decorative work, for large scenarios on modest phones. */
    var reducedEffects: Boolean = false

    /** Get/set a boolean setting by its string key. The settings menu is data-driven by key, but
     *  this is a typed Kotlin object — its properties compile to mangled getters/setters, NOT plain
     *  JS keys, so `asDynamic()[key]` does NOT reach them (reads undefined, writes a dead property).
     *  These map the checkbox keys to the real typed properties so the menu actually toggles them. */
    @Suppress("CyclomaticComplexMethod")
    fun getFlag(key: String): Boolean =
        when (key) {
            "hexGrid" -> hexGrid
            "showGridTerrain" -> showGridTerrain
            "muteUnitSounds" -> muteUnitSounds
            "noFOW" -> noFOW
            "useRetina" -> useRetina
            "markOwnUnits" -> markOwnUnits
            "markEnemyUnits" -> markEnemyUnits
            "enhancedSideMarkers" -> enhancedSideMarkers
            "quickAnimation" -> quickAnimation
            "showDetailInfoToolTips" -> showDetailInfoToolTips
            "showHiddenVictoryHexes" -> showHiddenVictoryHexes
            "confirmEndTurn" -> confirmEndTurn
            "stalinRegime" -> stalinRegime
            "reducedEffects" -> reducedEffects
            else -> false
        }

    @Suppress("CyclomaticComplexMethod") // one branch per checkbox key, mirroring getFlag exactly
    fun setFlag(
        key: String,
        value: Boolean,
    ) {
        when (key) {
            "hexGrid" -> hexGrid = value
            "showGridTerrain" -> showGridTerrain = value
            "muteUnitSounds" -> muteUnitSounds = value
            "noFOW" -> noFOW = value
            "useRetina" -> useRetina = value
            "markOwnUnits" -> markOwnUnits = value
            "markEnemyUnits" -> markEnemyUnits = value
            "enhancedSideMarkers" -> enhancedSideMarkers = value
            "quickAnimation" -> quickAnimation = value
            "showDetailInfoToolTips" -> showDetailInfoToolTips = value
            "showHiddenVictoryHexes" -> showHiddenVictoryHexes = value
            "confirmEndTurn" -> confirmEndTurn = value
            "stalinRegime" -> stalinRegime = value
            "reducedEffects" -> reducedEffects = value
        }
    }

    // Not dead code despite zero Kotlin callers: `toJSON` is the standard JS serialization
    // hook — JSON.stringify(uiSettings) (GameStatePersistence's settings save) invokes it
    // by name at runtime, which is also why the @JsName must stay exactly "toJSON".
    @Suppress("unused")
    @JsName("toJSON")
    fun toJSON(): dynamic {
        val o = js("{}")
        o.airMode = airMode
        o.strategicZoom = strategicZoom
        o.strategicZoomLevel = strategicZoomLevel
        o.mapZoom = mapZoom
        o.zoomLevel = zoomLevel
        o.uiScale = uiScale
        o.uiSize = uiSize
        o.uiSmallSize = uiSmallSize
        o.hexGrid = hexGrid
        o.showGridTerrain = showGridTerrain
        o.muteUnitSounds = muteUnitSounds
        o.deployMode = deployMode
        o.markCombatUnits = markCombatUnits
        o.markOwnUnits = markOwnUnits
        o.markEnemyUnits = markEnemyUnits
        o.markFOW = markFOW
        o.enhancedSideMarkers = enhancedSideMarkers
        o.noFOW = noFOW
        o.quickAnimation = quickAnimation
        o.hasTouch = hasTouch
        o.use3D = use3D
        o.useRetina = useRetina
        o.allowZoom = allowZoom
        o.isAI = isAI.toTypedArray()
        o.shownEndTurnTip = shownEndTurnTip
        o.unitInfoVisibility = unitInfoVisibility
        o.showInfoToolTips = showInfoToolTips
        o.showDetailInfoToolTips = showDetailInfoToolTips
        o.showHiddenVictoryHexes = showHiddenVictoryHexes
        o.confirmEndTurn = confirmEndTurn
        o.stalinRegime = stalinRegime
        o.mobileUiMode = mobileUiMode
        o.confirmAttacks = confirmAttacks
        o.interfaceDensity = interfaceDensity
        o.gestureTutorialVersion = gestureTutorialVersion
        o.reducedEffects = reducedEffects
        return o
    }
}

val uiSettings = UiSettings()
