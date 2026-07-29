package org.osada.multiplayer.sync

interface GameRandom {
    fun nextInt(bound: Int): Int

    fun nextDouble(): Double

    fun cursor(): Long
}

class SeededGameRandom(
    seed: Long,
) : GameRandom {
    private var state = if (seed == 0L) NON_ZERO_DEFAULT_SEED else seed
    private var draws = 0L

    override fun nextInt(bound: Int): Int {
        require(bound > 0)
        return (nextPositiveLong() % bound.toLong()).toInt()
    }

    override fun nextDouble(): Double {
        val value = nextPositiveLong().ushr(11)
        return value.toDouble() / DOUBLE_UNIT
    }

    override fun cursor(): Long = draws

    private fun nextPositiveLong(): Long {
        var value = state
        value = value xor (value shl SHIFT_LEFT_FIRST)
        value = value xor (value ushr SHIFT_RIGHT)
        value = value xor (value shl SHIFT_LEFT_SECOND)
        state = value
        draws++
        return value and Long.MAX_VALUE
    }

    private companion object {
        const val NON_ZERO_DEFAULT_SEED = -7046029254386353131L
        const val DOUBLE_UNIT = 9007199254740992.0
        const val SHIFT_LEFT_FIRST = 13
        const val SHIFT_RIGHT = 7
        const val SHIFT_LEFT_SECOND = 17
    }
}
