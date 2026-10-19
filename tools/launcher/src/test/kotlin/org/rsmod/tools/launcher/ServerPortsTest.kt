package org.rsmod.tools.launcher

import java.net.ServerSocket
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ServerPortsTest {
    @TempDir lateinit var dir: Path

    private fun config(central: String = "central:\n  same-instance: true\n  link-port: 9091\n") =
        GameConfigFile(dir.resolve("game.yml")).apply {
            path.writeText("name: Solo\ngame-port: 43594\n$central")
        }

    private fun claim(config: GameConfigFile, busy: Set<Int>): PortClaim =
        ServerPorts(config, isFree = { it !in busy }).claim()

    @Test
    fun `free ports leave game yml untouched`() {
        val config = config()
        val before = config.path.readText()

        assertEquals(PortClaim.Ready(emptyList()), claim(config, busy = emptySet()))
        assertEquals(before, config.path.readText())
    }

    @Test
    fun `busy central ports move to the next free ports`() {
        val config = config()

        val result = claim(config, busy = setOf(8080, 8081, 9091))

        assertEquals(
            PortClaim.Ready(
                listOf(PortMove("http-port", 8080, 8082), PortMove("link-port", 9091, 9092))
            ),
            result,
        )
        assertEquals(8082, config.centralPort("http-port", 0))
        assertEquals(9092, config.centralPort("link-port", 0))
        assertEquals("Solo", config.serverName())
        assertTrue(config.hasEmbeddedCentral())
    }

    @Test
    fun `central port that clashes with the game port is moved`() {
        val config = config("central:\n  same-instance: true\n  http-port: 43594\n")

        val result = claim(config, busy = emptySet())

        assertEquals(PortClaim.Ready(listOf(PortMove("http-port", 43594, 43595))), result)
    }

    @Test
    fun `busy game port is reported and nothing is moved`() {
        val config = config()
        val before = config.path.readText()

        assertEquals(PortClaim.GamePortInUse(43594), claim(config, busy = setOf(43594, 8080)))
        assertEquals(before, config.path.readText())
    }

    @Test
    fun `remote central keeps its ports`() {
        val config = config("central:\n  same-instance: false\n  link-port: 9091\n")

        assertEquals(PortClaim.Ready(emptyList()), claim(config, busy = setOf(8080, 9091)))
    }

    @Test
    fun `no free port in range is reported`() {
        val config = config("central:\n  same-instance: true\n  http-port: 65535\n")

        assertEquals(PortClaim.NoFreePort("http-port", 65535), claim(config, busy = setOf(65535)))
    }

    @Test
    fun `a listening socket is seen as busy`() {
        ServerSocket(0).use { socket ->
            assertFalse(ServerPorts.isPortFree(socket.localPort))
        }
    }
}
