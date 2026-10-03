package org.osada.multiplayer.coop

import kotlin.js.json

internal object CoopBriefingJson {
    private const val MAX_NODES = 500

    fun decodeOptional(
        value: dynamic,
        commanders: List<String>,
    ): CoopBriefing? = if (value == null || value == undefined) null else decode(value, commanders)

    fun encode(briefing: CoopBriefing): dynamic =
        json(
            "open" to briefing.open,
            "nodes" to
                briefing.nodes
                    .map { node ->
                        val choices = json()
                        node.choices.forEach { (id, next) -> choices[id] = next }
                        json("id" to node.id, "next" to node.next, "choices" to choices)
                    }.toTypedArray(),
            "votes" to stringMaps(briefing.votes),
            "decisions" to strings(briefing.decisions),
            "ready" to briefing.ready.sorted().toTypedArray(),
        )

    fun decode(
        value: dynamic,
        commanders: List<String>,
    ): CoopBriefing {
        val nodes =
            (0 until (value.nodes.length as Number).toInt()).map { index ->
                val item = value.nodes[index]
                val choices = keys(item.choices).associateWith { item.choices[it] as? String }
                CoopDialogueNode(item.id as String, item.next as? String, choices)
            }
        check(nodes.size <= MAX_NODES && nodes.map { it.id }.distinct().size == nodes.size) { "Invalid briefing graph" }
        val briefing = CoopBriefing(nodes)
        briefing.open = value.open as? Boolean ?: error("Missing briefing status")
        keys(value.votes).forEach { line ->
            val node = nodes.firstOrNull { it.id == line } ?: error("Unknown dialogue vote")
            briefing.votes[line] =
                keys(value.votes[line])
                    .associateWith { who ->
                        check(who in commanders) { "Unknown voter" }
                        (value.votes[line][who] as String).also { check(it in node.choices) { "Unknown choice" } }
                    }.toMutableMap()
        }
        keys(value.decisions).forEach { line ->
            val choice = value.decisions[line] as String
            check(nodes.any { it.id == line && choice in it.choices }) { "Invalid dialogue decision" }
            briefing.decisions[line] = choice
        }
        for (index in 0 until (value.ready.length as Number).toInt()) {
            val who = value.ready[index] as String
            check(who in commanders) { "Unknown ready seat" }
            briefing.ready += who
        }
        return briefing
    }

    private fun strings(values: Map<String, String>): dynamic =
        json().also { result ->
            values.forEach { (key, value) -> result[key] = value }
        }

    private fun stringMaps(values: Map<String, Map<String, String>>): dynamic =
        json().also { result ->
            values.forEach { (key, value) -> result[key] = strings(value) }
        }

    @Suppress("UnusedParameter") // Referenced in the literal JavaScript expression.
    private fun keys(value: dynamic): Array<String> = js("Object.keys(value)") as Array<String>
}
