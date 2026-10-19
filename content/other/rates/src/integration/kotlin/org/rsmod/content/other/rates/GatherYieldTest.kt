package org.rsmod.content.other.rates

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.Isolated
import org.rsmod.api.player.righthand
import org.rsmod.api.player.stat.SkillMultipliers
import org.rsmod.api.player.stat.SkillRates
import org.rsmod.api.testing.GameTestState
import org.rsmod.api.testing.scope.GameTestScope
import org.rsmod.content.skills.woodcutting.scripts.Woodcutting
import org.rsmod.content.skills.woodcutting.scripts.Woodcutting.Companion.treeLevelReq
import org.rsmod.content.skills.woodcutting.scripts.Woodcutting.Companion.treeLogs
import org.rsmod.game.inv.InvObj
import org.rsmod.map.CoordGrid

@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class GatherYieldTest {
    @Test
    fun GameTestState.`yield multiplier awards extra logs`() =
        runWithYield(3.0) { chopOnce(freeSlots = 28, expectedLogs = 3) }

    @Test
    fun GameTestState.`extra logs only fill the free inventory space`() =
        runWithYield(3.0) { chopOnce(freeSlots = 2, expectedLogs = 2) }

    @Test
    fun GameTestState.`base log is still awarded when no extra fits`() =
        runWithYield(3.0) { chopOnce(freeSlots = 1, expectedLogs = 1) }

    @Test
    fun GameTestState.`default yield awards a single log`() =
        runWithYield(1.0) { chopOnce(freeSlots = 28, expectedLogs = 1) }

    private fun GameTestState.runWithYield(multiplier: Double, test: GameTestScope.() -> Unit) {
        val previous = SkillRates.gatherYield
        SkillRates.gatherYield = SkillMultipliers(base = multiplier)
        try {
            runGameTest(Woodcutting::class) { test() }
        } finally {
            SkillRates.gatherYield = previous
        }
    }

    private fun GameTestScope.chopOnce(freeSlots: Int, expectedLogs: Int) {
        val type = findLocType("content.tree") { it.treeLevelReq == 1 }
        val tree = placeMapLoc(TREE_COORDS, type)
        val logs = type.treeLogs
        player.teleport(tree.coords.translateX(-1))
        player.fillInv()
        repeat(freeSlots) { player.inv[player.inv.size - 1 - it] = null }
        val fillerCount = player.inv.count { it != null }

        player.righthand = InvObj("obj.bronze_axe")
        player.opLoc1(tree)
        advance(ticks = 3)
        assertEquals(0, player.inv.count(logs))

        random.next = 0 // Guarantees the log roll succeeds.
        random.then = 0 // Keeps the tree from turning into a stump.
        advance(ticks = 1)

        assertEquals(expectedLogs, player.inv.count(logs))
        assertEquals(fillerCount, player.inv.count { it != null && it.id != logs.id })
    }

    private companion object {
        val TREE_COORDS = CoordGrid(0, 50, 50, 34, 31)
    }
}
