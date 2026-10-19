package org.rsmod.tools.launcher

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.prefs.Preferences
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import javax.swing.text.DefaultCaret
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.system.exitProcess

class LauncherWindow(private val root: Path) {
    private val frame = JFrame("OpenRune Solo Launcher")
    private val logArea = JTextArea()

    private val server = ServerController(root, ::appendLog)
    private val rsprox = RsproxClient(root.resolve(".launcher"), ::appendLog)
    private val proxyTargets = ProxyTargets.default()
    private val updater = GameUpdater.fromResources(root, ::appendLog)
    private val prefs = Preferences.userNodeForPackage(LauncherWindow::class.java)

    private val worker =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "launcher-worker").apply { isDaemon = true }
        }
    private val taskRunning = AtomicBoolean(false)
    private val checkingUpdates = AtomicBoolean(false)

    @Volatile private var lastUpdateCheck: UpdateCheck? = null

    private val xpRate = rateSpinner(min = 0.1, max = 10_000.0, step = 1.0)
    private val gatherSuccess = rateSpinner(min = 0.1, max = 100.0, step = 0.5)
    private val gatherYield = rateSpinner(min = 1.0, max = 100.0, step = 0.5)
    private val dropRate = rateSpinner(min = 0.1, max = 1_000.0, step = 0.5)
    private val xpPreset = JComboBox(XP_PRESETS.keys.toTypedArray())
    private var skillOverrides: Map<String, SkillRateOverride> = emptyMap()

    private val statusLabel = JLabel()
    private val reinstallButton = JButton("Reinstall")
    private val updateButton = JButton("Check for updates")
    private val startButton = JButton("Start server")
    private val stopButton = JButton("Stop server")
    private val playButton = JButton("Play")
    private val saveButton = JButton("Save rates")
    private val skillsButton = JButton("Per-skill rates...")

    init {
        loadRates(server.gameConfig.readRates())
        server.onStateChange { SwingUtilities.invokeLater(::refreshControls) }

        reinstallButton.toolTipText = "Download the OSRS cache again and rebuild everything."
        reinstallButton.addActionListener { confirmReinstall() }
        updateButton.addActionListener { onUpdateClicked() }
        startButton.addActionListener {
            val rates = currentRates()
            runTask { server.start(rates) }
        }
        stopButton.addActionListener { stopAsync() }
        playButton.addActionListener {
            val rates = currentRates()
            runTask { play(rates) }
        }
        saveButton.addActionListener { saveRates() }
        skillsButton.addActionListener { editSkillRates() }
        xpPreset.addActionListener { selectedPreset()?.let { xpRate.value = it } }

        playButton.font = playButton.font.deriveFont(Font.BOLD, 16f)
        playButton.preferredSize = Dimension(160, 44)

        logArea.isEditable = false
        logArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        (logArea.caret as DefaultCaret).updatePolicy = DefaultCaret.ALWAYS_UPDATE

        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, ratesPanel(), JScrollPane(logArea))
        split.resizeWeight = 0.0
        split.border = null

        frame.contentPane.layout = BorderLayout()
        frame.contentPane.add(headerPanel(), BorderLayout.NORTH)
        frame.contentPane.add(split, BorderLayout.CENTER)
        frame.contentPane.add(buttonPanel(), BorderLayout.SOUTH)
        frame.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        frame.addWindowListener(
            object : WindowAdapter() {
                override fun windowClosing(e: WindowEvent) = exit()
            }
        )
        frame.size = Dimension(1100, 680)
        frame.setLocationRelativeTo(null)
        refreshControls()
    }

    fun show() {
        frame.isVisible = true
        appendLog("Server folder: ${server.gameConfig.path.toAbsolutePath().parent}")
        if (!server.isInstalled) {
            appendLog("The server is not installed yet. Press Play to set everything up.")
        }
        checkForUpdatesAsync()
    }

    private fun confirmReinstall() {
        val choice =
            JOptionPane.showConfirmDialog(
                frame,
                "Reinstall downloads the OSRS cache again and rebuilds it, which takes a while.\n" +
                    "Your rates and characters are kept. Continue?",
                "Reinstall",
                JOptionPane.OK_CANCEL_OPTION,
            )
        if (choice == JOptionPane.OK_OPTION) {
            val rates = currentRates()
            runTask { server.install(rates) }
        }
    }

    private fun checkForUpdatesAsync() {
        if (!checkingUpdates.compareAndSet(false, true)) {
            return
        }
        refreshControls()
        thread(isDaemon = true, name = "update-check") {
            val check = updater.check()
            if (check is UpdateCheck.Failed) {
                appendLog("Could not check for updates: ${check.message}")
            }
            if (check is UpdateCheck.Available) {
                appendLog("Update available: ${check.newCommits} new change(s).")
            }
            lastUpdateCheck = check
            checkingUpdates.set(false)
            SwingUtilities.invokeLater(::refreshControls)
        }
    }

    private fun onUpdateClicked() {
        when (val check = lastUpdateCheck) {
            is UpdateCheck.Available,
            UpdateCheck.NotLinked -> confirmUpdate(check)
            else -> checkForUpdatesAsync()
        }
    }

    private fun confirmUpdate(check: UpdateCheck?) {
        val message = buildString {
            if (check is UpdateCheck.Available) {
                append("${check.newCommits} new change(s) are available.\n")
                append("Latest: ${check.latestChange}\n\n")
            } else {
                append("This folder was downloaded as a ZIP. Updating links it to the online ")
                append("repository and replaces the game files with the newest version.\n\n")
            }
            append("Your rates, settings and characters are kept.")
            if (server.isServerAlive) {
                append("\nThe server stops first, so log out in-game to save your progress.")
            }
            append("\n\nUpdate now?")
        }
        val choice =
            JOptionPane.showConfirmDialog(frame, message, "Update", JOptionPane.YES_NO_OPTION)
        if (choice == JOptionPane.YES_OPTION) {
            val rates = currentRates()
            runTask { performUpdate(rates) }
        }
    }

    private fun performUpdate(rates: RateSettings) {
        server.stop()
        when (val result = updater.update()) {
            UpdateResult.UpToDate -> {
                appendLog("Already up to date.")
                lastUpdateCheck = UpdateCheck.UpToDate
            }
            is UpdateResult.Failed -> {
                appendLog("Update failed: ${result.message}")
                showError("Update failed: ${result.message}")
            }
            is UpdateResult.Updated -> finishUpdate(result, rates)
        }
    }

    private fun finishUpdate(result: UpdateResult.Updated, rates: RateSettings) {
        lastUpdateCheck = UpdateCheck.UpToDate
        val revisionChanged = server.syncRevision()
        val rebuilt =
            when {
                revisionChanged || !server.isInstalled -> server.install(rates)
                result.needsCacheRebuild -> server.rebuildCache()
                else -> true
            }
        rsprox.updateIfOutdated()
        SwingUtilities.invokeLater {
            if (result.localChangesStashed) {
                showWarning(
                    "Your own code changes clash with the update, so they were set aside in a " +
                        "git stash. Run `git stash pop` in the server folder to get them back."
                )
            }
            when {
                !rebuilt ->
                    showError(
                        "The update was downloaded, but rebuilding the game failed. " +
                            "Check the log, then press Reinstall."
                    )
                result.launcherChanged -> offerRestart()
                else -> showInfo("Update complete. Press Play to start the updated game.")
            }
        }
    }

    private fun offerRestart() {
        val choice =
            JOptionPane.showConfirmDialog(
                frame,
                "Update complete. The launcher itself was updated too; restart it now?",
                "Restart launcher",
                JOptionPane.YES_NO_OPTION,
            )
        if (choice == JOptionPane.YES_OPTION) {
            relaunch()
        }
    }

    private fun relaunch() {
        frame.title = "Restarting launcher..."
        thread(name = "relaunch") {
            server.stop()
            val command =
                if (ServerController.IS_WINDOWS) {
                    listOf("cmd.exe", "/c", "start", "OpenRune Launcher", "Launcher.bat")
                } else {
                    listOf("sh", "launcher.sh")
                }
            val logFile = root.resolve(".launcher").createDirectories().resolve("relaunch.log")
            ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start()
            exitProcess(0)
        }
    }

    private fun play(rates: RateSettings) {
        if (!server.isInstalled && !server.install(rates)) {
            showError("Install failed. Check the log for details.")
            return
        }
        if (!server.isServerAlive && !server.start(rates)) {
            showError("The server did not start. Check the log for details.")
            return
        }
        val modulus = server.clientModulus()
        if (modulus == null) {
            showError("Could not read .data/client.key. Try Install / Update.")
            return
        }
        val config = server.gameConfig
        val target =
            ProxyTarget(
                name = TARGET_NAME,
                revision = config.revision(),
                modulus = modulus,
                gameServerPort = config.gamePort(),
            )
        proxyTargets.upsert(target)
        appendLog("Wrote RSProx target '$TARGET_NAME' to ${proxyTargets.file}")
        rsprox.launch()
        SwingUtilities.invokeLater(::showPlayHelp)
    }

    private fun saveRates() {
        val rates = currentRates()
        server.gameConfig.writeRates(rates)
        appendLog("Saved rates to ${server.gameConfig.path.fileName}.")
        if (!server.isServerAlive) {
            return
        }
        val restart =
            JOptionPane.showConfirmDialog(
                frame,
                "Rates saved. Restart the server now so they take effect?\n" +
                    "Log out in-game first so your progress is saved.",
                "Restart server",
                JOptionPane.YES_NO_OPTION,
            )
        if (restart == JOptionPane.YES_OPTION) {
            runTask {
                server.stop()
                server.start(rates)
            }
        }
    }

    private fun editSkillRates() {
        val dialog = SkillRatesDialog(frame, skillOverrides)
        dialog.isVisible = true
        dialog.result?.let {
            skillOverrides = it
            refreshSkillsButton()
        }
    }

    private fun stopAsync() {
        thread(isDaemon = true, name = "server-stop") { server.stop() }
    }

    private fun exit() {
        if (!server.isServerAlive) {
            val closeAnyway =
                !taskRunning.get() ||
                    JOptionPane.showConfirmDialog(
                        frame,
                        "The launcher is still working. Close it anyway?",
                        "Close launcher",
                        JOptionPane.OK_CANCEL_OPTION,
                    ) == JOptionPane.OK_OPTION
            if (closeAnyway) {
                exitProcess(0)
            }
            return
        }
        val choice =
            JOptionPane.showConfirmDialog(
                frame,
                "The server is still running. Stop it and close the launcher?",
                "Close launcher",
                JOptionPane.OK_CANCEL_OPTION,
            )
        if (choice == JOptionPane.OK_OPTION) {
            frame.title = "Stopping server..."
            thread(name = "server-stop") {
                server.stop()
                exitProcess(0)
            }
        }
    }

    private fun runTask(task: () -> Unit) {
        if (!taskRunning.compareAndSet(false, true)) {
            return
        }
        refreshControls()
        worker.execute {
            try {
                task()
            } catch (t: Throwable) {
                appendLog("Error: ${t.message ?: t::class.simpleName}")
                showError(t.message ?: "Unexpected error: ${t::class.simpleName}")
            } finally {
                taskRunning.set(false)
                SwingUtilities.invokeLater(::refreshControls)
            }
        }
    }

    private fun refreshControls() {
        val state = server.state
        val busy = taskRunning.get()
        val idle = state == ServerState.NotInstalled || state == ServerState.Stopped
        reinstallButton.isEnabled = !busy && idle
        startButton.isEnabled = !busy && state == ServerState.Stopped
        stopButton.isEnabled = state == ServerState.Starting || state == ServerState.Running
        playButton.isEnabled = !busy && (idle || state == ServerState.Running)
        saveButton.isEnabled = !busy
        updateButton.isEnabled =
            !busy && !checkingUpdates.get() && (idle || state == ServerState.Running)
        refreshUpdateButton()

        val (text, color) =
            when (state) {
                ServerState.NotInstalled -> "Not installed" to Color(0xE0A030)
                ServerState.Stopped -> "Stopped" to Color(0x9F9F9F)
                ServerState.Busy -> "Working..." to Color(0x5AA0FF)
                ServerState.Starting -> "Starting..." to Color(0x5AA0FF)
                ServerState.Running -> "Online" to Color(0x3CC85A)
                ServerState.Stopping -> "Stopping..." to Color(0xE0A030)
            }
        statusLabel.text = "● Server: $text"
        statusLabel.foreground = color
    }

    private fun refreshUpdateButton() {
        val check = lastUpdateCheck
        updateButton.text =
            when {
                checkingUpdates.get() -> "Checking for updates..."
                check is UpdateCheck.Available -> "Update (${check.newCommits} new)"
                check == UpdateCheck.NotLinked -> "Enable updates"
                check == UpdateCheck.UpToDate -> "Up to date"
                else -> "Check for updates"
            }
        updateButton.toolTipText =
            when (check) {
                is UpdateCheck.Available -> "Latest change: ${check.latestChange}"
                UpdateCheck.NotLinked -> "Link this folder to the online repository to get updates."
                UpdateCheck.UpToDate -> "Click to check again."
                is UpdateCheck.Failed -> "Last check failed: ${check.message}"
                null -> null
            }
        updateButton.foreground = if (check is UpdateCheck.Available) UPDATE_COLOR else null
    }

    private fun headerPanel(): JComponent {
        val title = JLabel("OpenRune Solo")
        title.font = title.font.deriveFont(Font.BOLD, 20f)
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createEmptyBorder(10, 14, 10, 14)
        panel.add(title, BorderLayout.WEST)
        statusLabel.font = statusLabel.font.deriveFont(Font.BOLD, 14f)
        panel.add(statusLabel, BorderLayout.EAST)
        return panel
    }

    private fun ratesPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createEmptyBorder(8, 14, 8, 14)
        var row = 0

        fun addRow(label: String, field: JComponent, tooltip: String, extra: JComponent? = null) {
            val name = JLabel(label)
            name.toolTipText = tooltip
            field.toolTipText = tooltip
            panel.add(name, constraints(0, row, anchor = GridBagConstraints.WEST))
            panel.add(field, constraints(1, row, fill = GridBagConstraints.HORIZONTAL))
            if (extra != null) {
                panel.add(extra, constraints(2, row, fill = GridBagConstraints.HORIZONTAL))
            }
            row++
        }

        val heading = JLabel("Rates")
        heading.font = heading.font.deriveFont(Font.BOLD, 15f)
        panel.add(heading, constraints(0, row++, width = 3, anchor = GridBagConstraints.WEST))

        addRow(
            "XP rate (x)",
            xpRate,
            "Experience multiplier for every skill. 1 = live OSRS.",
            xpPreset,
        )
        addRow(
            "Gather success (x)",
            gatherSuccess,
            "How likely each chop, mine, fish or pickpocket attempt is to succeed. " +
                "High values make every attempt succeed.",
        )
        addRow(
            "Gather yield (x)",
            gatherYield,
            "Resources per successful woodcutting, mining or fishing action. " +
                "1.5 gives an extra resource half of the time.",
        )
        addRow(
            "Drop rate (x)",
            dropRate,
            "Multiplier for boosted rare drops (2 turns 1/512 into 1/256).",
        )

        panel.add(skillsButton, fullWidth(row++))

        val help =
            JLabel(
                "<html><body style='width:250px'>Rates are saved to game.yml. Changes apply " +
                    "the next time the server starts.</body></html>"
            )
        help.foreground = Color(0x9F9F9F)
        panel.add(help, constraints(0, row++, width = 3, anchor = GridBagConstraints.WEST))
        panel.add(saveButton, fullWidth(row++))

        val filler = constraints(0, row, width = 3)
        filler.weighty = 1.0
        panel.add(JPanel(), filler)
        return panel
    }

    private fun buttonPanel(): JComponent {
        val left = JPanel(FlowLayout(FlowLayout.LEFT))
        left.add(startButton)
        left.add(stopButton)
        left.add(reinstallButton)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT))
        right.add(updateButton)
        right.add(playButton)

        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createEmptyBorder(6, 8, 8, 8)
        panel.add(left, BorderLayout.WEST)
        panel.add(right, BorderLayout.EAST)
        return panel
    }

    private fun showPlayHelp() {
        if (prefs.getBoolean(PREF_HIDE_PLAY_HELP, false)) {
            return
        }
        val dontShow = JCheckBox("Don't show this again")
        val message =
            arrayOf<Any>(
                "<html>RSProx is opening. In the RSProx window:<ol>" +
                    "<li>Set the client to <b>RuneLite</b>.</li>" +
                    "<li>Set the proxy target to <b>$TARGET_NAME</b>.</li>" +
                    "<li>Click <b>Launch</b>. RuneLite opens after a short download.</li>" +
                    "<li>Log in with any username and password.<br>" +
                    "Your account is created on first login.</li>" +
                    "</ol>RSProx remembers these choices for next time.</html>",
                dontShow,
            )
        val info = JOptionPane.INFORMATION_MESSAGE
        JOptionPane.showMessageDialog(frame, message, "Connecting RuneLite", info)
        if (dontShow.isSelected) {
            prefs.putBoolean(PREF_HIDE_PLAY_HELP, true)
        }
    }

    private fun showInfo(message: String) {
        val info = JOptionPane.INFORMATION_MESSAGE
        JOptionPane.showMessageDialog(frame, message, "OpenRune Launcher", info)
    }

    private fun showWarning(message: String) {
        val warning = JOptionPane.WARNING_MESSAGE
        JOptionPane.showMessageDialog(frame, message, "OpenRune Launcher", warning)
    }

    private fun showError(message: String) {
        SwingUtilities.invokeLater {
            val error = JOptionPane.ERROR_MESSAGE
            JOptionPane.showMessageDialog(frame, message, "OpenRune Launcher", error)
        }
    }

    private fun loadRates(rates: RateSettings) {
        xpRate.value = rates.xpMultiplier.coerceAtLeast(0.1)
        gatherSuccess.value = rates.gatherSuccessMultiplier.coerceAtLeast(0.1)
        gatherYield.value = rates.gatherYieldMultiplier.coerceAtLeast(1.0)
        dropRate.value = rates.dropMultiplier.coerceAtLeast(0.1)
        skillOverrides = rates.skills
        val preset = XP_PRESETS.entries.firstOrNull { it.value == rates.xpMultiplier }
        xpPreset.selectedItem = preset?.key ?: CUSTOM
        refreshSkillsButton()
    }

    private fun currentRates(): RateSettings =
        RateSettings(
            xpMultiplier = xpRate.doubleValue(),
            gatherSuccessMultiplier = gatherSuccess.doubleValue(),
            gatherYieldMultiplier = gatherYield.doubleValue(),
            dropMultiplier = dropRate.doubleValue(),
            skills = skillOverrides,
        )

    private fun refreshSkillsButton() {
        val count = skillOverrides.size
        skillsButton.text =
            if (count == 0) "Per-skill rates..." else "Per-skill rates... ($count set)"
    }

    private fun appendLog(line: String) {
        val clean = line.replace(ANSI_ESCAPE, "")
        SwingUtilities.invokeLater {
            logArea.append(clean)
            logArea.append("\n")
            val overflow = logArea.document.length - MAX_LOG_CHARS
            if (overflow > 0) {
                logArea.replaceRange("", 0, overflow)
            }
        }
    }

    private fun selectedPreset(): Double? = XP_PRESETS[xpPreset.selectedItem as String]

    private fun JSpinner.doubleValue(): Double = (value as Number).toDouble()

    private fun rateSpinner(min: Double, max: Double, step: Double): JSpinner {
        val spinner = JSpinner(SpinnerNumberModel(1.0, min, max, step))
        spinner.editor = JSpinner.NumberEditor(spinner, "0.##")
        spinner.preferredSize = Dimension(110, spinner.preferredSize.height)
        spinner.addChangeListener {
            if (spinner === xpRate && selectedPreset() != spinner.value) {
                xpPreset.selectedItem = CUSTOM
            }
        }
        return spinner
    }

    private fun fullWidth(y: Int): GridBagConstraints =
        constraints(0, y, width = 3, fill = GridBagConstraints.HORIZONTAL)

    private fun constraints(
        x: Int,
        y: Int,
        width: Int = 1,
        fill: Int = GridBagConstraints.NONE,
        anchor: Int = GridBagConstraints.CENTER,
    ): GridBagConstraints =
        GridBagConstraints().apply {
            gridx = x
            gridy = y
            gridwidth = width
            this.fill = fill
            this.anchor = anchor
            insets = Insets(4, 4, 4, 4)
        }

    private companion object {
        const val TARGET_NAME = "OpenRune Solo"
        const val PREF_HIDE_PLAY_HELP = "hidePlayHelp"
        const val MAX_LOG_CHARS = 400_000
        const val CUSTOM = "Custom"

        val UPDATE_COLOR = Color(0x3CC85A)

        val ANSI_ESCAPE = Regex("\u001B\\[[;\\d]*[A-Za-z]")

        val XP_PRESETS: Map<String, Double?> =
            linkedMapOf(
                CUSTOM to null,
                "1x (live)" to 1.0,
                "5x" to 5.0,
                "10x" to 10.0,
                "25x" to 25.0,
                "50x" to 50.0,
                "100x" to 100.0,
            )
    }
}
