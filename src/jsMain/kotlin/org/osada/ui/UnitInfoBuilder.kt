package org.osada.ui

/**
 * Builds the static unit-info panel (image placeholder + the grid of stat slots). The
 * runtime values are filled in elsewhere; this only lays out the DOM from the shared
 * [UIBuilder.unitStats] metadata. Extracted from the former `UIBuilder` god-object.
 */
internal object UnitInfoBuilder {

    fun buildUnitInfoWindow() {
        // Portrait + big flag live directly under #unit-info (not inside #statsRow) so the
        // OSADA card can absolutely position them into the frame's portrait window / flag
        // shelf, independently of the stat grid. Ids are unchanged, so UnitInfoPanel keeps
        // finding them by id.
        val imageBg = addTag("unit-info", "div")
        imageBg.id = "uImageBg"
        val image = addTag("uImageBg", "div")
        image.id = "uImage"

        fun buildStatDiv(parent: dynamic, stat: UIBuilder.UnitStatEntry) {
            var div = addTag(parent, "div")
            stat.glyph?.let { glyph ->
                div.title = stat.title
                div.className = "statsGlyph"
                div.textContent = glyph
                div = addTag(div, "div")
            }
            div.id = stat.id
            div.className = "statsText"
        }

        // #statsRow's flat ~19-chip grid is grouped into labeled sections (Status/Attack/Defence/
        // Mobility & Recon — UIBuilder.unitStats' own `group` field) for the "All stats" expander.
        // Same ids/values as before; UnitInfoPanel fills them exactly as it always did, only the
        // surrounding markup is new.
        UIBuilder.unitStats.filter { it.group.isNotEmpty() }.groupBy { it.group }.forEach { (groupName, stats) ->
            val section = addTag("statsRow", "div")
            section.className = "osada-stat-group"
            val label = addTag(section, "div")
            label.className = "osada-stat-group__label"
            label.textContent = groupName
            val grid = addTag(section, "div")
            grid.className = "osada-stat-group__grid"
            stats.forEach { stat -> buildStatDiv(grid, stat) }
        }

        // Entries with no group (uFlag, uLeader/uTransport/uCarrier) keep their original routing:
        // uFlag straight into #unit-info, the rest into the now-hidden #statsRowTop shell (they're
        // individually reparented into the name-line sockets by BottomZoneBuilder afterwards).
        UIBuilder.unitStats.forEach { stat ->
            if (stat.id == "uCost" || stat.group.isNotEmpty()) return@forEach
            buildStatDiv(if (stat.id == "uFlag") "unit-info" else "statsRowTop", stat)
        }
    }
}
