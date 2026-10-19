package org.rsmod.tools.launcher

import com.formdev.flatlaf.FlatDarkLaf
import java.nio.file.Path
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.io.path.isRegularFile
import kotlin.system.exitProcess

private const val REQUIRED_JAVA = 21

fun main(args: Array<String>) {
    FlatDarkLaf.setup()

    if (Runtime.version().feature() < REQUIRED_JAVA) {
        fail(
            "Java $REQUIRED_JAVA or newer is required, but this is Java ${Runtime.version()}.\n" +
                "Install Java $REQUIRED_JAVA from https://adoptium.net and try again."
        )
    }

    val root =
        findServerRoot(args)
            ?: fail("Could not find the server folder. Start the launcher from inside it.")

    SwingUtilities.invokeLater { LauncherWindow(root).show() }
}

private fun findServerRoot(args: Array<String>): Path? {
    val jarLocation =
        runCatching {
                Path.of(LauncherWindow::class.java.protectionDomain.codeSource.location.toURI())
            }
            .getOrNull()
    val candidates =
        listOfNotNull(args.firstOrNull()?.let(Path::of), Path.of(""), jarLocation)
            .map { it.toAbsolutePath() }
    return candidates.firstNotNullOfOrNull { start ->
        generateSequence(start) { it.parent }.firstOrNull(::isServerRoot)
    }
}

private fun isServerRoot(dir: Path): Boolean =
    dir.resolve("game.example.yml").isRegularFile() &&
        dir.resolve("settings.gradle.kts").isRegularFile()

private fun fail(message: String): Nothing {
    JOptionPane.showMessageDialog(null, message, "OpenRune Launcher", JOptionPane.ERROR_MESSAGE)
    exitProcess(1)
}
