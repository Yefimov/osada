package org.osada.multiplayer.coop

/** Only the public, condition-filtered dialogue graph of the current mission, never future branches. */
data class CoopDialogueNode(
    val id: String,
    val next: String?,
    val choices: Map<String, String?>,
)

/** Votes belong to stable commander identities and survive reconnect/checkpoint recovery. */
class CoopBriefing(
    val nodes: List<CoopDialogueNode>,
) {
    var open: Boolean = true
    val votes: MutableMap<String, MutableMap<String, String>> = linkedMapOf()
    val decisions: MutableMap<String, String> = linkedMapOf()
    val ready: MutableSet<String> = linkedSetOf()

    fun pending(): CoopDialogueNode? {
        var node = nodes.firstOrNull()
        val visited = mutableSetOf<String>()
        while (node != null && visited.add(node.id)) {
            val selected = decisions[node.id]
            if (node.choices.isNotEmpty() && selected == null) return node
            val target = if (selected == null) node.next else node.choices[selected]
            node = target?.let { id -> nodes.firstOrNull { it.id == id } }
        }
        return null
    }

    fun vote(
        lineId: String,
        optionId: String,
        commanderId: String,
        required: List<String>,
    ): Boolean {
        val line = pending()
        val offered = line?.id == lineId && optionId in line.choices
        val accepting = open && winners(required).isEmpty()
        if (!accepting || !offered || commanderId !in required) return false
        votes.getOrPut(lineId) { linkedMapOf() }[commanderId] = optionId
        ready.clear()
        return true
    }

    /** Empty until every active seat voted; a tie is resolved by a separate host command. */
    @Suppress("ReturnCount")
    fun winners(required: List<String>): Set<String> {
        val line = pending() ?: return emptySet()
        val cast = votes[line.id].orEmpty()
        if (required.isEmpty() || required.any { it !in cast }) return emptySet()
        val counts = required.mapNotNull(cast::get).groupingBy { it }.eachCount()
        val maximum = counts.values.maxOrNull() ?: return emptySet()
        return counts.filterValues { it == maximum }.keys
    }

    @Suppress("ReturnCount")
    fun decide(
        optionId: String,
        required: List<String>,
    ): Boolean {
        val line = pending() ?: return false
        if (!open || optionId !in winners(required)) return false
        decisions[line.id] = optionId
        ready.clear()
        return true
    }

    fun markReady(
        commanderId: String,
        required: List<String>,
    ): Boolean {
        if (!open || pending() != null || commanderId !in required) return false
        ready += commanderId
        if (required.isNotEmpty() && required.all { it in ready }) open = false
        return true
    }
}
