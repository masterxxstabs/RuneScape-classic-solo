package org.rsmod.api.player.stat

import kotlin.math.floor
import kotlin.math.min
import org.rsmod.api.random.GameRandom
import org.rsmod.game.entity.Player

/** Server-wide skill rate multipliers, keyed by stat (`"stat.woodcutting"`). */
public object SkillRates {
    @Volatile public var xp: SkillMultipliers = SkillMultipliers(base = null)

    @Volatile public var gatherSuccess: SkillMultipliers = SkillMultipliers(base = 1.0)

    @Volatile public var gatherYield: SkillMultipliers = SkillMultipliers(base = 1.0)

    public fun xpRate(player: Player, stat: String): Double = xp[stat] ?: player.xpRate

    public fun successChance(stat: String, chance: Double): Double =
        chance * (gatherSuccess[stat] ?: 1.0)

    public fun bonusYield(stat: String, count: Int, random: GameRandom): Int =
        rollBonusYield(gatherYield[stat], count, random)
}

public class SkillMultipliers(
    private val base: Double?,
    private val overrides: Map<String, Double> = emptyMap(),
) {
    public operator fun get(stat: String): Double? = overrides[stat] ?: base

    override fun toString(): String =
        if (overrides.isEmpty()) "${base ?: "default"}" else "${base ?: "default"} $overrides"
}

/**
 * Rolls how many resources to award on top of [count]. The whole part of `count * multiplier` is
 * always awarded and the fractional part is the chance of one more, so a `1.5` multiplier averages
 * one and a half resources per success.
 */
internal fun rollBonusYield(multiplier: Double?, count: Int, random: GameRandom): Int {
    if (multiplier == null || multiplier <= 1.0 || count <= 0) {
        return 0
    }
    val scaled = min(count * multiplier, Int.MAX_VALUE.toDouble())
    val whole = floor(scaled)
    val roundUp = scaled > whole && random.randomDouble() < scaled - whole
    val total = whole.toInt() + if (roundUp) 1 else 0
    return (total - count).coerceAtLeast(0)
}
