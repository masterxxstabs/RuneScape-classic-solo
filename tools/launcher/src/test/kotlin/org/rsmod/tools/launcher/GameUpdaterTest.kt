package org.rsmod.tools.launcher

import java.nio.file.Path
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class GameUpdaterTest {
    @TempDir lateinit var dir: Path

    private lateinit var remote: Path
    private lateinit var local: Path

    @BeforeEach
    fun setUp() {
        remote = dir.resolve("remote")
        Git.init().setDirectory(remote.toFile()).setInitialBranch(BRANCH).call().use { git ->
            write(remote, "game.example.yml", "revision: 241\n")
            write(remote, ".data/gamevals/obj.rscm", "coins=995\n")
            write(remote, "content/Woodcutting.kt", "class Woodcutting\n")
            write(remote, "README.md", "hello\n")
            commit(git, "Initial")
        }
        local = dir.resolve("local")
        Git.cloneRepository()
            .setURI(remote.toUri().toString())
            .setDirectory(local.toFile())
            .setBranch(BRANCH)
            .call()
            .close()
    }

    @Test
    fun `check reports new commits`() {
        assertEquals(UpdateCheck.UpToDate, updater(local).check())

        remoteCommit("Add fishing", "content/Fishing.kt" to "class Fishing\n")

        assertEquals(UpdateCheck.Available(1, "Add fishing"), updater(local).check())
    }

    @Test
    fun `documentation only updates need no cache rebuild`() {
        remoteCommit("Docs", "README.md" to "hello world\n")

        val result = updater(local).update()

        assertEquals(
            UpdateResult.Updated(
                newCommits = 1,
                changedPaths = listOf("README.md"),
                launcherChanged = false,
                needsCacheRebuild = false,
                localChangesStashed = false,
            ),
            result,
        )
        assertEquals("hello world\n", local.resolve("README.md").readText())
        assertEquals(UpdateCheck.UpToDate, updater(local).check())
    }

    @Test
    fun `content and launcher changes are flagged`() {
        remoteCommit(
            "Content",
            "content/Woodcutting.kt" to "class Woodcutting2\n",
            "tools/launcher/Launcher.kt" to "fun main() {}\n",
        )

        val result = updater(local).update() as UpdateResult.Updated

        assertTrue(result.needsCacheRebuild)
        assertTrue(result.launcherChanged)
    }

    @Test
    fun `already up to date does nothing`() {
        assertEquals(UpdateResult.UpToDate, updater(local).update())
    }

    @Test
    fun `generated gameval edits are discarded`() {
        write(local, ".data/gamevals/obj.rscm", "coins=995\ncustom=65000\n")
        remoteCommit("Gamevals", ".data/gamevals/obj.rscm" to "coins=995\nlogs=1511\n")

        val result = updater(local).update() as UpdateResult.Updated

        assertFalse(result.localChangesStashed)
        assertEquals("coins=995\nlogs=1511\n", local.resolve(".data/gamevals/obj.rscm").readText())
    }

    @Test
    fun `untracked generated files that block the update are replaced`() {
        write(local, ".data/gamevals/npc.rscm", "generated=1\n")
        remoteCommit("Track npc gamevals", ".data/gamevals/npc.rscm" to "man=3106\n")

        assertInstanceOf(UpdateResult.Updated::class.java, updater(local).update())
        assertEquals("man=3106\n", local.resolve(".data/gamevals/npc.rscm").readText())
    }

    @Test
    fun `untracked files of your own stop the update without being overwritten`() {
        write(local, "content/Mining.kt", "class MyMining\n")
        remoteCommit("Add mining", "content/Mining.kt" to "class Mining\n")

        val result = updater(local).update()

        assertInstanceOf(UpdateResult.Failed::class.java, result)
        assertTrue("content/Mining.kt" in (result as UpdateResult.Failed).message)
        assertEquals("class MyMining\n", local.resolve("content/Mining.kt").readText())
    }

    @Test
    fun `local code edits survive the update`() {
        write(local, "content/Woodcutting.kt", "class MyWoodcutting\n")
        remoteCommit("Docs", "README.md" to "hello world\n")

        val result = updater(local).update() as UpdateResult.Updated

        assertFalse(result.localChangesStashed)
        assertEquals("class MyWoodcutting\n", local.resolve("content/Woodcutting.kt").readText())
        assertEquals("hello world\n", local.resolve("README.md").readText())
    }

    @Test
    fun `clashing local edits are kept in a stash`() {
        write(local, "README.md", "my notes\n")
        remoteCommit("Docs", "README.md" to "hello world\n")

        val result = updater(local).update() as UpdateResult.Updated

        assertTrue(result.localChangesStashed)
        assertEquals("hello world\n", local.resolve("README.md").readText())
        Git.open(local.toFile()).use { assertEquals(1, it.stashList().call().size) }
    }

    @Test
    fun `local commits are merged with the update`() {
        Git.open(local.toFile()).use { git ->
            write(local, "content/Mining.kt", "class Mining\n")
            commit(git, "My mining")
        }
        remoteCommit("Docs", "README.md" to "hello world\n")

        assertInstanceOf(UpdateResult.Updated::class.java, updater(local).update())
        assertEquals("class Mining\n", local.resolve("content/Mining.kt").readText())
        assertEquals("hello world\n", local.resolve("README.md").readText())
    }

    @Test
    fun `zip downloads are linked to the repository`() {
        val zip = dir.resolve("zip")
        remote.copyToRecursively(zip, followLinks = false, overwrite = false)
        zip.resolve(".git").deleteRecursively()
        write(zip, "game.yml", "revision: 241\n")
        remoteCommit("Newer content", "content/Woodcutting.kt" to "class Woodcutting2\n")
        val updater = updater(zip)

        assertEquals(UpdateCheck.NotLinked, updater.check())
        val result = updater.update() as UpdateResult.Updated

        assertTrue(result.launcherChanged && result.needsCacheRebuild)
        assertEquals("class Woodcutting2\n", zip.resolve("content/Woodcutting.kt").readText())
        assertTrue(zip.resolve("game.yml").exists())
        assertEquals(UpdateCheck.UpToDate, updater.check())
    }

    @Test
    fun `unreachable repository is reported`() {
        remote.deleteRecursively()

        assertInstanceOf(UpdateCheck.Failed::class.java, updater(local).check())
        assertInstanceOf(UpdateResult.Failed::class.java, updater(local).update())
    }

    private fun updater(root: Path): GameUpdater =
        GameUpdater(root, repositoryUrl = remote.toUri().toString(), branch = BRANCH, log = {})

    private fun remoteCommit(message: String, vararg files: Pair<String, String>) {
        Git.open(remote.toFile()).use { git ->
            files.forEach { (path, text) -> write(remote, path, text) }
            commit(git, message)
        }
    }

    private fun write(root: Path, path: String, text: String) {
        val file = root.resolve(path)
        file.parent.createDirectories()
        file.writeText(text)
    }

    private fun commit(git: Git, message: String) {
        git.add().addFilepattern(".").call()
        git.commit().setMessage(message).setAuthor(IDENT).setCommitter(IDENT).setSign(false).call()
    }

    private companion object {
        const val BRANCH = "main"
        val IDENT = PersonIdent("Test", "test@example.com")
    }
}
