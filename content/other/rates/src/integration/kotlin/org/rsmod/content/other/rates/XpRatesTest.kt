package org.rsmod.content.other.rates

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.Isolated
import org.rsmod.api.player.stat.SkillMultipliers
import org.rsmod.api.player.stat.SkillRates
import org.rsmod.api.player.stat.XpSource
import org.rsmod.api.player.stat.statAdvance
import org.rsmod.api.testing.GameTestState
import org.rsmod.api.testing.scope.GameTestScope

@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class XpRatesTest {
    @Test
    fun GameTestState.`configured rate replaces the character rate`() =
        runWithXp(SkillMultipliers(base = 10.0, overrides = mapOf("stat.prayer" to 2.0))) {
            player.xpRate = 150.0
            assertEquals(250, player.statAdvance("stat.woodcutting", 25.0))
            assertEquals(50, player.statAdvance("stat.prayer", 25.0))
        }

    @Test
    fun GameTestState.`unset rate keeps the character rate`() =
        runWithXp(SkillMultipliers(base = null)) {
            player.xpRate = 3.0
            assertEquals(75, player.statAdvance("stat.mining", 25.0))
        }

    @Test
    fun GameTestState.`explicit rates such as admin commands are not multiplied`() =
        runWithXp(SkillMultipliers(base = 50.0)) {
            val added =
                player.statAdvance("stat.cooking", 100.0, rate = 1.0, source = XpSource.Manual)
            assertEquals(100, added)
        }

    private fun GameTestState.runWithXp(
        multipliers: SkillMultipliers,
        test: GameTestScope.() -> Unit,
    ) {
        val previous = SkillRates.xp
        SkillRates.xp = multipliers
        try {
            runGameTest { test() }
        } finally {
            SkillRates.xp = previous
        }
    }
}
