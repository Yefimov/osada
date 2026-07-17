package org.osada.ui

/**
 * Canvas rendering styles ported from the legacy `style.js`.
 */

data class HexStyle(val fillColor: String?, val lineColor: String, val lineWidth: Double, val lineJoin: String)

object HexStyles {
    val move = HexStyle(
        fillColor = "rgba(128,128,128,0.5)",
        lineColor = "rgba(0,0,0,0.4)",
        lineWidth = 1.0,
        lineJoin = "miter",
    )

    val attack = HexStyle(
        fillColor = null,
        lineColor = "rgba(239,0,0,0.8)",
        lineWidth = 3.0,
        lineJoin = "miter",
    )

    val current = HexStyle(
        fillColor = null,
        lineColor = "rgba(240,240,240,0.8)",
        lineWidth = 3.0,
        lineJoin = "round",
    )

    val generic = HexStyle(
        fillColor = null,
        lineColor = "rgba(39,44,47,0.9)",
        lineWidth = 0.4,
        lineJoin = "miter",
    )

    val deploy = HexStyle(
        fillColor = "rgba(128,128,128,0.8)",
        lineColor = "rgba(0,0,0,0.4)",
        lineWidth = 1.0,
        lineJoin = "miter",
    )

    val ownunit = HexStyle(
        fillColor = "rgba(30,144,255,0.3)",
        lineColor = "rgba(0,0,0,0.4)",
        lineWidth = 0.0,
        lineJoin = "miter",
    )

    fun byName(name: String): HexStyle? = when (name) {
        "move" -> move
        "attack" -> attack
        "current" -> current
        "generic" -> generic
        "deploy" -> deploy
        "ownunit" -> ownunit
        else -> null
    }
}

data class UnitStyle(
    val axisBox: String,
    val alliedBox: String,
    val playerText: String,
    val alliedPlayerText: String,
    val movedUnitText: String,
)

val unitStyle = UnitStyle(
    axisBox = "#383838",
    alliedBox = "#808000",
    playerText = "white",
    alliedPlayerText = "#696969",
    movedUnitText = "#A9A9A9",
)
