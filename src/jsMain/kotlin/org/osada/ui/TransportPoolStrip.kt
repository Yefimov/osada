package org.osada.ui

import org.osada.GameHolder
import org.osada.PlayerType
import org.osada.i18n.I18n
import org.osada.model.Player
import org.osada.model.getPlayers

/**
 * The player's three non-organic transport pools -- air, naval, rail -- under the minimap, as
 * "free / authored size" with a tooltip each.
 *
 * The scenario author sets how many transports of each kind the side may have in use at once
 * (`model/TransportPools`), and embarking refuses with *"no transport left in reserve"* once the
 * pool is spent, but nothing on screen said what the pools were: the player met the number only
 * as a refusal (2026-09-29 owner request). A pool the scenario does not grant is left out; a
 * scenario that grants none shows one muted chip saying so, so the absence is itself explained.
 */
internal object TransportPoolStrip {
    private const val STRIP_ID = "osadaTransportPools"

    /** [menuFont]: drawn from osada-menu.ttf, like the action chips. */
    private data class Glyph(
        val text: String,
        val menuFont: Boolean,
    )

    /** Rail is the "Board train" chip's own track glyph (`UIBuilder.unitContextButtons["rail_move"]`),
     *  so the pool and the action that spends it read as one thing. */
    private val GLYPHS =
        mapOf(
            "air" to Glyph("✈", false),
            "naval" to Glyph("⚓", false),
            "rail" to Glyph(UIBuilder.unitContextButtons["rail_move"] ?: "~", true),
        )

    private data class Pool(
        val key: String,
        val free: Int,
        val size: Int,
    )

    fun build(parent: dynamic) {
        if (parent == null || byId(STRIP_ID) != null) return
        val strip = addTag(parent, "div")
        strip.id = STRIP_ID
        strip.className = "osada-transport-pools"
        refresh()
    }

    fun refresh() {
        val strip = byId(STRIP_ID) ?: return
        clearTag(strip)
        val player = viewingPlayer()
        val pools =
            listOfNotNull(player)
                .flatMap {
                    listOf(
                        Pool("air", it.airTransports, it.airTransportsMax),
                        Pool("naval", it.navalTransports, it.navalTransportsMax),
                        Pool("rail", it.railTransports, it.railTransportsMax),
                    )
                }.filter { it.size > 0 }
        when {
            player == null -> Unit
            pools.isEmpty() ->
                chip(strip, I18n.t("hud.transport_pools.none.label"), I18n.t("hud.transport_pools.none.help"), true)
            else -> pools.forEach { pool -> poolChip(strip, pool) }
        }
    }

    private fun poolChip(
        strip: org.w3c.dom.HTMLElement,
        pool: Pool,
    ) {
        val values = mapOf("free" to pool.free, "size" to pool.size)
        chip(
            strip,
            I18n.t("hud.transport_pools.${pool.key}.label"),
            I18n.t("hud.transport_pools.${pool.key}.help", values),
            muted = pool.free == 0,
            glyph = GLYPHS[pool.key],
            count = "${pool.free}/${pool.size}",
        )
    }

    /**
     * One chip: glyph, name, count. The phone layout hides the name (`mobile.css`) to fit the zoom
     * row. [help] is the desktop `title` and, on a touch screen, the panel a long press opens
     * ([TapTip.attachHold]): mobile browsers never show `title` (2026-09-29 owner request).
     */
    private fun chip(
        strip: org.w3c.dom.HTMLElement,
        name: String,
        help: String,
        muted: Boolean,
        glyph: Glyph? = null,
        count: String? = null,
    ) {
        val chip = addTag(strip, "span")
        chip.className = "osada-transport-pool" + if (muted) " osada-transport-pool--empty" else ""
        chip.title = help
        glyph?.let {
            val icon = addTag(chip, "span")
            icon.className =
                "osada-transport-pool__glyph" + if (it.menuFont) " osada-transport-pool__glyph--menu" else ""
            icon.textContent = it.text
        }
        val label = addTag(chip, "span")
        label.className = if (count == null) "" else "osada-transport-pool__name"
        label.textContent = name
        count?.let { addTag(chip, "span").textContent = it }
        TapTip.attachHold(chip, { name }) { help }
    }

    /** The human side being looked at: during the AI's turn the strip keeps showing the player's. */
    private fun viewingPlayer(): Player? {
        val game = GameHolder.instance
        val map = game?.scenario?.map
        val current = map?.currentPlayer
        return when {
            map == null -> null
            current?.type == PlayerType.HUMAN_LOCAL -> current
            else ->
                map.getPlayers().firstOrNull { it.type == PlayerType.HUMAN_LOCAL && it.side == game.spotSide }
                    ?: current
        }
    }
}
