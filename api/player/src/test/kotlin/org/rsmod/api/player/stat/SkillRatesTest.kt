package org.rsmod.api.player.stat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.rsmod.api.random.GameRandom

class SkillRatesTest {
    @Test
    fun `per-skill override replaces the base multiplier`() {
        val multipliers = SkillMultipliers(base = 10.0, overrides = mapOf("stat.prayer" to 2.0))
        assertEquals(2.0, multipliers["stat.prayer"])
        assertEquals(10.0, multipliers["stat.woodcutting"])
    }

    @Test
    fun `missing base falls through to the caller default`() {
        val multipliers = SkillMultipliers(base = null, overrides = mapOf("stat.prayer" to 5.0))
        assertNull(multipliers["stat.attack"])
        assertEquals(5.0, multipliers["stat.prayer"])
    }

    @ParameterizedTest
    @CsvSource(
        "1.0, 1, 0.0, 0",
        "0.5, 1, 0.0, 0",
        "2.0, 1, 0.99, 1",
        "3.0, 2, 0.99, 4",
        "2.5, 2, 0.99, 3",
        "1.5, 1, 0.49, 1",
        "1.5, 1, 0.5, 0",
        "1.5, 3, 0.49, 2",
        "1.5, 3, 0.5, 1",
        "4.0, 0, 0.0, 0",
    )
    fun `bonus yield awards the whole part and rolls the fraction`(
        multiplier: Double,
        count: Int,
        roll: Double,
        expectedBonus: Int,
    ) {
        assertEquals(expectedBonus, rollBonusYield(multiplier, count, FixedDouble(roll)))
    }

    @Test
    fun `bonus yield is zero when no multiplier is configured`() {
        assertEquals(0, rollBonusYield(null, 5, FixedDouble(0.0)))
    }

    @Test
    fun `bonus yield never overflows the stack limit`() {
        val bonus = rollBonusYield(1_000.0, Int.MAX_VALUE / 2, FixedDouble(0.0))
        assertEquals(Int.MAX_VALUE - Int.MAX_VALUE / 2, bonus)
    }

    private class FixedDouble(private val value: Double) : GameRandom {
        override fun of(maxExclusive: Int): Int = error("Unused")

        override fun of(minInclusive: Int, maxInclusive: Int): Int = error("Unused")

        override fun randomDouble(): Double = value
    }
}
