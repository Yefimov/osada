package org.osada.ui.music

import kotlin.random.Random

/** Personal shuffle bag. Its RNG and history never touch the game's random stream. */
internal class MusicQueue(
    private val random: Random =
        Random(
            kotlin.js.Date
                .now()
                .toLong(),
        ),
) {
    val upcoming = mutableListOf<String>()
    val played = mutableSetOf<String>()
    val history = mutableListOf<String>()
    var cursor = -1
    private var eligible = emptySet<String>()
    private var shuffle = true
    private var works = emptyMap<String, String>()

    fun reconcile(
        ids: Set<String>,
        workIds: Map<String, String> = emptyMap(),
        shuffled: Boolean = true,
    ) {
        val initialBag = upcoming.isEmpty() && played.isEmpty()
        if (shuffle != shuffled) upcoming.clear()
        shuffle = shuffled
        eligible = ids
        works = workIds
        upcoming.removeAll { it !in ids }
        (ids - played - upcoming.toSet()).let { if (shuffle) it.shuffled(random) else it.toList() }.forEach {
            if (shuffle) upcoming.add(random.nextInt(upcoming.size + 1), it) else upcoming.add(it)
        }
        if (initialBag && shuffle) avoidAdjacentWorks()
    }

    fun next(
        validHistory: Set<String>,
        current: String?,
    ): String? {
        while (cursor + 1 < history.size) {
            cursor++
            if (history[cursor] in validHistory) return history[cursor]
        }
        if (upcoming.isEmpty() && eligible.size > 1) newCycle(current)
        val id = upcoming.removeFirstOrNull()
        if (id != null) record(id)
        // Materialize the actual next cycle, so the queue view never invents an order.
        if (id != null && upcoming.isEmpty() && eligible.size > 1) newCycle(id)
        return id
    }

    fun direct(id: String) {
        upcoming.remove(id)
        record(id)
    }

    fun previous(validHistory: Set<String>): String? {
        var index = cursor - 1
        while (index >= 0) {
            if (history[index] in validHistory) {
                cursor = index
                return history[index]
            }
            index--
        }
        return null
    }

    fun scheduled(validHistory: Set<String>): List<String> =
        history.drop(cursor + 1).filter { it in validHistory } + upcoming

    private fun record(id: String) {
        while (history.size > cursor + 1) history.removeAt(history.lastIndex)
        history.add(id)
        if (history.size > HISTORY_LIMIT) history.removeAt(0)
        cursor = history.lastIndex
        played.add(id)
    }

    private fun newCycle(last: String?) {
        played.clear()
        upcoming.clear()
        upcoming.addAll(if (shuffle) eligible.shuffled(random) else eligible.toList())
        if (upcoming.firstOrNull() == last) {
            val other = upcoming.indexOfFirst { it != last }
            if (other > 0) upcoming.add(0, upcoming.removeAt(other))
        }
        if (shuffle) avoidAdjacentWorks(last)
    }

    private fun avoidAdjacentWorks(last: String? = history.getOrNull(cursor)) {
        var previous = last
        for (index in upcoming.indices) {
            val work = works[previous]
            if (work != null && works[upcoming[index]] == work) {
                val other = (index + 1 until upcoming.size).firstOrNull { works[upcoming[it]] != work }
                if (other != null) upcoming.add(index, upcoming.removeAt(other))
            }
            previous = upcoming[index]
        }
    }

    companion object {
        const val HISTORY_LIMIT = 100
    }
}
