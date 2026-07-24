package org.osada.hero

/**
 * The four command competencies a category feeds when a trait justified by it is chosen (§8.1).
 * Meaning is deliberately broad rather than class-specific — the brief's own examples split the
 * same category across classes (Coordination is spotting for recon and support fire for artillery
 * alike), so the attribute a category feeds is a coarser signal than the category itself.
 */
enum class CommandAttribute {
    OFFENSE,
    DEFENSE,
    MANEUVER,
    COORDINATION,
}

/**
 * Thematic evidence categories (§8.4) a hero accumulates proof in. Names match the brief's list
 * verbatim so a future phase can add rules under an existing category rather than inventing one.
 *
 * Only a subset has an [EvidenceRule] producing it in Phase 3 — see that file for which. The rest
 * are declared now so the save shape (a plain `Map<String, Int>` keyed by [name]) never needs to
 * change when a later phase wires a new source; this mirrors Phase 1's "reserved field" approach
 * applied to enum entries instead of data-class fields.
 */
enum class EvidenceCategory(
    val title: String,
    val attribute: CommandAttribute,
) {
    OFFENSIVE_OPERATIONS("Offensive Operations", CommandAttribute.OFFENSE),
    DEFENSIVE_OPERATIONS("Defensive Operations", CommandAttribute.DEFENSE),
    MOBILE_WARFARE("Mobile Warfare", CommandAttribute.MANEUVER),
    RIVER_OPERATIONS("River Operations", CommandAttribute.MANEUVER),
    URBAN_COMBAT("Urban Combat", CommandAttribute.OFFENSE),
    FOREST_OPERATIONS("Forest Operations", CommandAttribute.MANEUVER),
    MOUNTAIN_OPERATIONS("Mountain Operations", CommandAttribute.MANEUVER),
    ARMORED_COMBAT("Armored Combat", CommandAttribute.OFFENSE),
    ANTI_ARMOR("Anti-Armor", CommandAttribute.OFFENSE),
    RECONNAISSANCE("Reconnaissance", CommandAttribute.COORDINATION),
    FIRE_SUPPORT("Fire Support", CommandAttribute.COORDINATION),
    AIR_DEFENSE("Air Defense", CommandAttribute.DEFENSE),
    AIR_INTERCEPTION("Air Interception", CommandAttribute.OFFENSE),
    GROUND_ATTACK("Ground Attack", CommandAttribute.OFFENSE),
    LOGISTICS("Logistics", CommandAttribute.COORDINATION),
    REPAIR_AND_RECOVERY("Repair and Recovery", CommandAttribute.COORDINATION),
    ENCIRCLEMENT("Encirclement", CommandAttribute.DEFENSE),
    WITHDRAWAL("Withdrawal", CommandAttribute.MANEUVER),
    WEATHER_OPERATIONS("Weather Operations", CommandAttribute.MANEUVER),
    NAVAL_GUNNERY("Naval Gunnery", CommandAttribute.OFFENSE),
    SUBMARINE_WARFARE("Submarine Warfare", CommandAttribute.OFFENSE),
    ;

    companion object {
        /** Defaulting lookup by [name], for reading a save's evidence map keys back to an enum. */
        fun byName(name: String?): EvidenceCategory? = entries.firstOrNull { it.name == name }
    }
}
