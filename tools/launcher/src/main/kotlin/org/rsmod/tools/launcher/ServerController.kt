package org.rsmod.tools.launcher

import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

enum class ServerState {
    NotInstalled,
    Stopped,
    Busy,
    Starting,
    Running,
    Stopping,
}

class ServerController(private val root: Path, private val log: (String) -> Unit) {
    private val listeners = CopyOnWriteArrayList<(ServerState) -> Unit>()

    private val process = AtomicReference<Process?>(null)

    private val exampleConfig = GameConfigFile(root.resolve("game.example.yml"))

    val gameConfig: GameConfigFile =
        GameConfigFile(root.resolve("game.yml"), fallback = exampleConfig.path)

    @Volatile
    var state: ServerState = idleState()
        private set

    val isInstalled: Boolean
        get() =
            root.resolve(".data/cache/LIVE").isDirectory() &&
                root.resolve(".data/cache/SERVER").isDirectory() &&
                gameConfig.exists

    val isServerAlive: Boolean
        get() = process.get()?.isAlive == true

    fun onStateChange(listener: (ServerState) -> Unit) {
        listeners += listener
    }

    /** Moves game.yml to the revision in game.example.yml; returns `true` if it was behind. */
    fun syncRevision(): Boolean {
        if (!gameConfig.exists || !gameConfig.syncRevisionFrom(exampleConfig)) {
            return false
        }
        log("The game moved to OSRS revision ${gameConfig.revision()}.")
        return true
    }

    fun install(rates: RateSettings): Boolean {
        setState(ServerState.Busy)
        log("Installing the server. The first install downloads the OSRS cache and takes a while.")
        syncRevision()
        val previousConfig = gameConfig.path.takeIf { it.isRegularFile() }?.readBytes()
        val success = gradle("install") && gradle(":or-cache:mergePluginGamevals")
        previousConfig?.let(gameConfig.path::writeBytes)
        if (gameConfig.exists) {
            gameConfig.writeRates(rates)
        }
        log(if (success) "Install finished." else "Install failed - see the log above.")
        setState(idleState())
        return success && isInstalled
    }

    fun rebuildCache(): Boolean {
        setState(ServerState.Busy)
        log("Rebuilding the game cache with the updated content...")
        val success = gradle(":or-cache:buildCache", ":or-cache:mergePluginGamevals")
        log(if (success) "Cache rebuilt." else "Cache rebuild failed - see the log above.")
        setState(idleState())
        return success
    }

    fun start(rates: RateSettings): Boolean {
        if (isServerAlive) {
            return true
        }
        setState(ServerState.Busy)
        gameConfig.writeRates(rates)
        log("Building the server...")
        if (!gradle(":server:app:shadowJar")) {
            log("Server build failed - see the log above.")
            setState(idleState())
            return false
        }

        setState(ServerState.Starting)
        log("Starting the server...")
        val command = listOf(javaExecutable()) + SERVER_JVM_ARGS + listOf("-jar", serverJar())
        val started =
            ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start()
        process.set(started)

        val ready = CountDownLatch(1)
        thread(isDaemon = true, name = "server-output") {
            started.inputStream.bufferedReader().forEachLine { line ->
                log(line)
                if (READY_MARKER in line) {
                    setState(ServerState.Running)
                    ready.countDown()
                }
            }
        }
        started.onExit().thenRun {
            log("Server stopped (exit code ${started.exitValue()}).")
            if (process.compareAndSet(started, null)) {
                setState(idleState())
            }
            ready.countDown()
        }

        ready.await(START_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        return state == ServerState.Running
    }

    fun stop() {
        val running = process.get() ?: return
        setState(ServerState.Stopping)
        log("Stopping the server and saving players...")
        runCatching {
            running.outputStream.bufferedWriter().apply {
                write(STOP_COMMAND)
                newLine()
                flush()
            }
        }
        if (!running.waitFor(GRACEFUL_STOP_SECONDS, TimeUnit.SECONDS)) {
            log("Server did not stop in time; terminating it.")
            running.destroy()
            if (!running.waitFor(FORCE_STOP_SECONDS, TimeUnit.SECONDS)) {
                running.destroyForcibly().waitFor()
            }
        }
    }

    fun clientModulus(): String? {
        val keyFile = root.resolve(".data/client.key")
        if (!keyFile.exists()) {
            return null
        }
        return keyFile.toFile().readLines().firstNotNullOfOrNull { line ->
            line.substringAfter("Modulus:", missingDelimiterValue = "").trim().ifEmpty { null }
        }
    }

    private fun gradle(vararg tasks: String): Boolean {
        val wrapper = root.resolve(if (IS_WINDOWS) "gradlew.bat" else "gradlew")
        if (!IS_WINDOWS) {
            wrapper.toFile().setExecutable(true)
        }
        val command =
            buildList {
                if (IS_WINDOWS) {
                    addAll(listOf("cmd.exe", "/c"))
                }
                add(wrapper.absolutePathString())
                add("--console=plain")
                addAll(tasks)
            }
        log("> gradlew ${tasks.joinToString(" ")}")
        val builder = ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
        builder.environment()["JAVA_HOME"] = System.getProperty("java.home")
        val gradle = builder.start()
        gradle.outputStream.close()
        gradle.inputStream.bufferedReader().forEachLine(log)
        return gradle.waitFor() == 0
    }

    private fun serverJar(): String =
        root.resolve("server/app/build/libs/server.jar").absolutePathString()

    private fun idleState(): ServerState =
        when {
            isServerAlive -> state
            isInstalled -> ServerState.Stopped
            else -> ServerState.NotInstalled
        }

    private fun setState(newState: ServerState) {
        state = newState
        listeners.forEach { it(newState) }
    }

    companion object {
        const val READY_MARKER: String = "Server ready in"
        const val STOP_COMMAND: String = "stop"

        private const val START_TIMEOUT_MINUTES = 10L
        private const val GRACEFUL_STOP_SECONDS = 60L
        private const val FORCE_STOP_SECONDS = 15L

        val IS_WINDOWS: Boolean = System.getProperty("os.name").lowercase().contains("win")

        private val SERVER_JVM_ARGS: List<String> =
            listOf(
                "-XX:AutoBoxCacheMax=65535",
                "-Xms512m",
                "-Xmx4g",
                "-XX:MinHeapFreeRatio=5",
                "-XX:MaxHeapFreeRatio=20",
                "-XX:+G1PeriodicGCInvokesConcurrent",
                "-XX:G1PeriodicGCInterval=15000",
            )

        fun javaExecutable(): String {
            val bin = Path.of(System.getProperty("java.home"), "bin")
            return bin.resolve(if (IS_WINDOWS) "java.exe" else "java").absolutePathString()
        }
    }
}
