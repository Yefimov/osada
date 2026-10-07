package org.osada.ui

import org.osada.multiplayer.coop.CoopSeatSymbols

/** Canvas `Path2D` objects for the cooperative seat symbols, built once per symbol. */
internal object CoopSeatGlyphs {
    private val cache = mutableMapOf<String, dynamic>()

    /** Null for the seat number (no symbol), an unknown id, or a browser without `Path2D`. */
    fun path2d(symbol: String): dynamic {
        val path = CoopSeatSymbols.pathOf(symbol)
        val ctor = js("typeof Path2D === 'function' ? Path2D : null")
        if (path == null || ctor == null) return null
        return cache.getOrPut(symbol) { js("new ctor(path)") }
    }
}
