package org.osada.ui

/**
 * Inline SVG glyphs for the unit-action chips `osada-menu.ttf` has no right picture for (2026-09-28).
 *
 * The font carries no hammer, no bridge, no anchor-with-intent and nothing for Port or Repair at all:
 * `P`, `R` and `S` are not in it, so those chips rendered a bare fallback letter, and several others
 * borrowed an unrelated picture (Build Bridge showed a coin, Demolish a silhouette, Lay Mines a gun,
 * Barrage an armoured car, Clear Mines a left arrow, Undo a no-entry sign). Each Build chip is
 * the facility plus a small hammer, so the five read as one family and as "build" at a glance.
 *
 * Drawn on a 24x24 grid in `currentColor`, so a chip colours them exactly as it colours a font glyph;
 * `osada-theme.css` sizes them to the glyph box. The hammer carries a dark halo (`paint-order=stroke`)
 * to separate it from the facility it overlaps.
 */
internal object ActionIcons {
    /** A two-span arched bridge. */
    const val BUILD_BRIDGE: String =
        """<svg viewBox="0 0 24 24" aria-hidden="true"><g transform="scale(.86)">""" +
            """<path d="M0.5 6.8h19v2.6H.5z"/>""" +
            """<path d="M1.5 9.4h17v9.1h-1.2a3.3 3.3 0 0 0-6.6 0h-1.4a3.3 3.3 0 0 0-6.6 0H1.5z"/></g>""" +
            """<g transform="translate(10.5 10.5) scale(.56) rotate(45 12 12)" stroke="#16120d"""" +
            """ stroke-width="3" stroke-linejoin="round" paint-order="stroke">""" +
            """<rect x="4" y="2.5" width="16" height="6.5" rx="1.2"/>""" +
            """<rect x="10.3" y="8" width="3.4" height="14" rx="1.2"/></g></svg>"""

    /** A bunker with its embrasure. */
    const val BUILD_FORTIFICATION: String =
        """<svg viewBox="0 0 24 24" aria-hidden="true"><g transform="scale(.86)">""" +
            """<path fill-rule="evenodd" d="M1.5 19V9.2L10 3.5l8.5 5.7V19zM5.5 11.4v2.2h9v-2.2z"/></g>""" +
            """<g transform="translate(10.5 10.5) scale(.56) rotate(45 12 12)" stroke="#16120d"""" +
            """ stroke-width="3" stroke-linejoin="round" paint-order="stroke">""" +
            """<rect x="4" y="2.5" width="16" height="6.5" rx="1.2"/>""" +
            """<rect x="10.3" y="8" width="3.4" height="14" rx="1.2"/></g></svg>"""

    /** An aircraft. */
    const val BUILD_AIRFIELD: String =
        """<svg viewBox="0 0 24 24" aria-hidden="true"><g transform="scale(.86)">""" +
            """<path d="M10 1c.9 0 1.5.8 1.5 1.9v4.4l7 4v2l-7-2v4.6l2.1 1.6V19l-3.6-1.1L6.4""" +
            """ 19v-1.5l2.1-1.6v-4.6l-7 2v-2l7-4V2.9C8.5 1.8 9.1 1 10 1z"/></g>""" +
            """<g transform="translate(10.5 10.5) scale(.56) rotate(45 12 12)" stroke="#16120d"""" +
            """ stroke-width="3" stroke-linejoin="round" paint-order="stroke">""" +
            """<rect x="4" y="2.5" width="16" height="6.5" rx="1.2"/>""" +
            """<rect x="10.3" y="8" width="3.4" height="14" rx="1.2"/></g></svg>"""

    /** An anchor. */
    const val BUILD_PORT: String =
        """<svg viewBox="0 0 24 24" aria-hidden="true"><g transform="scale(.86)">""" +
            """<path fill-rule="evenodd" d="M10 1a2.4 2.4 0 0 1 1 4.6v1.5h3.2v1.8H11v8.3c2.5-.3 4.4-2""" +
            """ 5-4.3l-1.7.4 2.4-3.5 1.4 4-1.3-.6c-.9 3.7-4.3 6.3-8.8 6.3s-7.9-2.6-8.8-6.3l-1.3.6 1.4-4 2.4""" +
            """ 3.5-1.7-.4c.6 2.3 2.5 4 5 4.3V8.9H5.8V7.1H9V5.6A2.4 2.4 0 0 1 10 1zm0 1.5a.9.9 0 1 0 0""" +
            """ 1.8.9.9 0 0 0 0-1.8z"/></g>""" +
            """<g transform="translate(10.5 10.5) scale(.56) rotate(45 12 12)" stroke="#16120d"""" +
            """ stroke-width="3" stroke-linejoin="round" paint-order="stroke">""" +
            """<rect x="4" y="2.5" width="16" height="6.5" rx="1.2"/>""" +
            """<rect x="10.3" y="8" width="3.4" height="14" rx="1.2"/></g></svg>"""

    /** A station building over the track. */
    const val BUILD_STATION: String =
        """<svg viewBox="0 0 24 24" aria-hidden="true"><g transform="scale(.86)">""" +
            """<path fill-rule="evenodd" d="M.8 8.2 10 2l9.2 6.2zM2.8 8.2h14.4v6.2H2.8zm5.7 2v4.2h3v-4.2z"/>""" +
            """<path d="M.5 15.6h19v1.1H.5zM.5 18h19v1.1H.5zM2.5 15h1.1v4.6H2.5zM7 15h1.1v4.6H7zM11.9""" +
            """ 15H13v4.6h-1.1zM16.4 15h1.1v4.6h-1.1z"/></g>""" +
            """<g transform="translate(10.5 10.5) scale(.56) rotate(45 12 12)" stroke="#16120d"""" +
            """ stroke-width="3" stroke-linejoin="round" paint-order="stroke">""" +
            """<rect x="4" y="2.5" width="16" height="6.5" rx="1.2"/>""" +
            """<rect x="10.3" y="8" width="3.4" height="14" rx="1.2"/></g></svg>"""

    /** Hammer and wrench, crossed. */
    const val REPAIR: String =
        """<svg viewBox="0 0 24 24" aria-hidden="true">""" +
            """<path d="M20.7 3.3a5.2 5.2 0 0 0-6.9 6.2L3.2 20.1a1.9 1.9 0 0 0 2.7 2.7l10.6-10.6a5.2 5.2 0 0""" +
            """ 0 6.2-6.9l-3.2 3.2-2.7-.5-.5-2.7z"/><g transform="rotate(-90 12 12)">""" +
            """<g transform="rotate(45 12 12)" stroke="#16120d" stroke-width="2.2" paint-order="stroke"""" +
            """ stroke-linejoin="round"><rect x="5" y="2" width="14" height="5.5" rx="1"/>""" +
            """<rect x="10.5" y="7" width="3" height="15" rx="1"/></g></g></svg>"""

    /** A burst. */
    const val DEMOLISH: String =
        """<svg viewBox="0 0 24 24" aria-hidden="true">""" +
            """<path d="M12 .8l2.3 6.4 6.4-2.8-3.3 6 5.8 2.3-6.3 1.7 2.2 6.5-6-3.6-2.2 6.3-1.8-6.4-6.3 2.6""" +
            """ 3.5-5.9L1 11.2l6.3-1.6-2.3-6.1 5.2 3.3z"/></svg>"""

    /** A burst over the track. */
    const val BLOW_RAIL: String =
        """<svg viewBox="0 0 24 24" aria-hidden="true"><g transform="translate(3.5 0) scale(.72)">""" +
            """<path d="M12 .8l2.3 6.4 6.4-2.8-3.3 6 5.8 2.3-6.3 1.7 2.2 6.5-6-3.6-2.2 6.3-1.8-6.4-6.3 2.6""" +
            """ 3.5-5.9L1 11.2l6.3-1.6-2.3-6.1 5.2 3.3z"/></g>""" +
            """<path d="M1 17.2h22v1.3H1zM1 20.3h22v1.3H1zM3 16.5h1.3v5.8H3zM8.4 16.5h1.3v5.8H8.4zM14.3""" +
            """ 16.5h1.3v5.8h-1.3zM19.7 16.5H21v5.8h-1.3z"/></svg>"""

    /** A mine with an arrow laying it. */
    const val LAY_MINES: String =
        """<svg viewBox="0 0 24 24" aria-hidden="true">""" +
            """<path d="M2 16.2c0-2.9 4.5-4.9 10-4.9s10 2 10 4.9v1.6c0 1.9-4.5 3.4-10 3.4S2 19.7 2 17.8z"/>""" +
            """<path d="M10 10h4v1.8h-4z"/>""" +
            """<path d="M6.2 11.6 4.8 9.4l1.1-.7 1.5 2.4zM17.8 11.6l1.4-2.2-1.1-.7-1.5 2.4z"/>""" +
            """<path d="M12 9.2 7.4 4.6h3.1V.8h3v3.8h3.1z"/></svg>"""

    /** A mine with an arrow lifting it out -- the pair reads as lay / clear. */
    const val CLEAR_MINES: String =
        """<svg viewBox="0 0 24 24" aria-hidden="true">""" +
            """<path d="M2 16.2c0-2.9 4.5-4.9 10-4.9s10 2 10 4.9v1.6c0 1.9-4.5 3.4-10 3.4S2 19.7 2 17.8z"/>""" +
            """<path d="M10 10h4v1.8h-4z"/>""" +
            """<path d="M6.2 11.6 4.8 9.4l1.1-.7 1.5 2.4zM17.8 11.6l1.4-2.2-1.1-.7-1.5 2.4z"/>""" +
            """<path d="M12 .8l4.6 4.6h-3.1v3.8h-3V5.4H7.4z"/></svg>"""

    /** Shells falling on the ground: fire at hexes nobody has seen. */
    const val BARRAGE: String =
        """<svg viewBox="0 0 24 24" aria-hidden="true">""" +
            """<path d="M1.9000000000000004 2.2h4.6v6.2c0 2.1-1 3.9-2.3 5.3-1.3-1.4-2.3-3.2-2.3-5.3z"/>""" +
            """<path d="M1.9000000000000004 0.6000000000000001h4.6v1h-4.6z"/>""" +
            """<path d="M9.7 2.2h4.6v6.2c0 2.1-1 3.9-2.3 5.3-1.3-1.4-2.3-3.2-2.3-5.3z"/>""" +
            """<path d="M9.7 0.6000000000000001h4.6v1h-4.6z"/>""" +
            """<path d="M17.5 2.2h4.6v6.2c0 2.1-1 3.9-2.3 5.3-1.3-1.4-2.3-3.2-2.3-5.3z"/>""" +
            """<path d="M17.5 0.6000000000000001h4.6v1h-4.6z"/><path d="M1 20.4h22v2.1H1z"/>""" +
            """<path d="M12 13.4l1.4 3.1 3-1.7-.9 3.4 3.1-.2-2.3 2.4H7.7l-2.3-2.4 3.1.2-.9-3.4 3 1.7z"/></svg>"""

    /** A curved arrow back. */
    const val UNDO: String =
        """<svg viewBox="0 0 24 24" aria-hidden="true">""" +
            """<path d="M9.5 3.5 2.5 10l7 6.5v-4.2c4.9 0 8.6 1.5 11.2 6 .3-6.6-3.5-11.3-11.2-11.7z"/></svg>"""
}
