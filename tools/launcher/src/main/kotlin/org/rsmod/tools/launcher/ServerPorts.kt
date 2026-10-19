package org.rsmod.tools.launcher

import java.net.InetSocketAddress
import java.net.ServerSocket

data class PortMove(val setting: String, val from: Int, val to: Int)

sealed interface PortClaim {
    data class Ready(val moves: List<PortMove>) : PortClaim

    data class GamePortInUse(val port: Int) : PortClaim

    data class NoFreePort(val setting: String, val port: Int) : PortClaim
}

/**
 * Checks the ports the server listens on before it starts. The embedded account server (OpenRune
 * Central) only talks to the game server, so its ports are moved in game.yml when another program
 * already uses them. The game port is what RuneLite connects to, so a busy one is reported instead.
 */
class ServerPorts(
    private val config: GameConfigFile,
    private val isFree: (Int) -> Boolean = ::isPortFree,
) {
    fun claim(): PortClaim {
        val gamePort = config.gamePort()
        if (!isFree(gamePort)) {
            return PortClaim.GamePortInUse(gamePort)
        }
        if (!config.hasEmbeddedCentral()) {
            return PortClaim.Ready(emptyList())
        }

        val reserved = mutableSetOf(gamePort)
        val moves = mutableListOf<PortMove>()
        for ((setting, default) in CENTRAL_PORTS) {
            val port = config.centralPort(setting, default)
            if (port !in reserved && isFree(port)) {
                reserved += port
                continue
            }
            val replacement =
                (port + 1..minOf(port + SEARCH_RANGE, MAX_PORT)).firstOrNull {
                    it !in reserved && isFree(it)
                } ?: return PortClaim.NoFreePort(setting, port)
            config.setCentralPort(setting, replacement)
            reserved += replacement
            moves += PortMove(setting, port, replacement)
        }
        return PortClaim.Ready(moves)
    }

    companion object {
        private const val SEARCH_RANGE = 100
        private const val MAX_PORT = 65535

        private val CENTRAL_PORTS = listOf("http-port" to 8080, "link-port" to 9091)

        fun isPortFree(port: Int): Boolean =
            runCatching { ServerSocket().use { it.bind(InetSocketAddress(port)) } }.isSuccess
    }
}
