package org.rsmod.content.other.console

import com.github.michaelbull.logging.InlineLogger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Lets whoever started the server (a terminal or the launcher) stop it cleanly by writing `stop`
 * to its standard input. Exiting runs the JVM shutdown hook, which saves players and stops the
 * embedded database - something a forced kill on Windows would skip.
 */
class ConsoleStopScript : PluginScript() {
    override fun ScriptContext.startup() {
        if (readerStarted.compareAndSet(false, true)) {
            thread(isDaemon = true, name = "console-stop") { awaitStopCommand() }
        }
    }

    private fun awaitStopCommand() {
        val input = System.`in`.bufferedReader()
        while (true) {
            val line = input.readLine() ?: return
            if (line.trim().equals(STOP_COMMAND, ignoreCase = true)) {
                logger.info { "Stop requested from the console. Shutting down..." }
                exitProcess(0)
            }
        }
    }

    private companion object {
        const val STOP_COMMAND = "stop"

        val logger = InlineLogger()
        val readerStarted = AtomicBoolean(false)
    }
}
