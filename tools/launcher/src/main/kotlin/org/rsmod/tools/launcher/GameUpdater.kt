package org.rsmod.tools.launcher

import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.CheckoutConflictException
import org.eclipse.jgit.api.errors.StashApplyFailureException
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.treewalk.CanonicalTreeParser

sealed interface UpdateCheck {
    data object UpToDate : UpdateCheck

    data class Available(val newCommits: Int, val latestChange: String) : UpdateCheck

    /** The folder was downloaded as a ZIP, so updating first links it to the repository. */
    data object NotLinked : UpdateCheck

    data class Failed(val message: String) : UpdateCheck
}

sealed interface UpdateResult {
    data object UpToDate : UpdateResult

    data class Updated(
        val newCommits: Int,
        val changedPaths: List<String>,
        val launcherChanged: Boolean,
        val needsCacheRebuild: Boolean,
        val localChangesStashed: Boolean,
    ) : UpdateResult

    data class Failed(val message: String) : UpdateResult
}

class GameUpdater(
    private val root: Path,
    private val repositoryUrl: String,
    private val branch: String,
    private val log: (String) -> Unit,
) {
    private val remoteRef = "refs/remotes/$REMOTE/$branch"

    val isLinked: Boolean
        get() = root.resolve(".git").exists() && runCatching(::headCommit).getOrNull() != null

    private fun headCommit(): ObjectId? =
        Git.open(root.toFile()).use { it.repository.resolve(Constants.HEAD) }

    @Synchronized
    fun check(): UpdateCheck {
        if (!isLinked) {
            return UpdateCheck.NotLinked
        }
        return runCatching {
                Git.open(root.toFile()).use { git ->
                    fetch(git)
                    val repo = git.repository
                    val head = repo.resolve(Constants.HEAD)
                    val remote = repo.resolve(remoteRef) ?: error("Branch $branch not found")
                    val behind = countCommits(repo, include = remote, exclude = head)
                    if (behind == 0) {
                        UpdateCheck.UpToDate
                    } else {
                        UpdateCheck.Available(behind, latestSubject(repo, remote))
                    }
                }
            }
            .getOrElse { UpdateCheck.Failed(it.message ?: it::class.simpleName.orEmpty()) }
    }

    @Synchronized
    fun update(): UpdateResult =
        runCatching { if (isLinked) updateCheckout() else linkDownload() }
            .getOrElse { UpdateResult.Failed(it.message ?: it::class.simpleName.orEmpty()) }

    private fun updateCheckout(): UpdateResult =
        Git.open(root.toFile()).use { git ->
            log("Checking ${updateSource(git)} for updates...")
            fetch(git)
            val repo = git.repository
            val head = repo.resolve(Constants.HEAD)
            val remote = repo.resolve(remoteRef) ?: error("Branch $branch not found")
            val behind = countCommits(repo, include = remote, exclude = head)
            if (behind == 0) {
                return UpdateResult.UpToDate
            }

            discardGeneratedChanges(git)
            val stash = if (git.status().call().hasUncommittedChanges()) stash(git) else null

            val failure =
                try {
                    mergeReplacingGenerated(git).takeUnless { it.mergeStatus.isSuccessful }?.let {
                        mergeFailure(it.mergeStatus.toString(), it.conflicts?.keys.orEmpty())
                    }
                } catch (e: CheckoutConflictException) {
                    mergeFailure("files in the way", e.conflictingPaths)
                }
            if (failure != null) {
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef(head.name).call()
                stash?.let { restoreStash(git) }
                return UpdateResult.Failed(failure)
            }

            val stashKept = stash != null && !restoreStash(git)
            val newHead = repo.resolve(Constants.HEAD)
            val changed = changedPaths(repo, head, newHead)
            log("Updated ${head.abbreviate(7).name()} -> ${newHead.abbreviate(7).name()}.")
            updated(behind, changed, stashKept)
        }

    private fun linkDownload(): UpdateResult =
        Git.init().setDirectory(root.toFile()).setInitialBranch(branch).call().use { git ->
            log("Linking this folder to $repositoryUrl. The first download can take a minute...")
            git.repository.config.apply {
                setString("remote", REMOTE, "url", repositoryUrl)
                setString("remote", REMOTE, "fetch", "+refs/heads/*:refs/remotes/$REMOTE/*")
                save()
            }
            fetch(git)
            val repo = git.repository
            val remote = repo.resolve(remoteRef) ?: error("Branch $branch not found")

            repo.updateRef(Constants.HEAD).link(Constants.R_HEADS + branch)
            val branchRef = repo.updateRef(Constants.R_HEADS + branch)
            branchRef.setNewObjectId(remote)
            branchRef.forceUpdate()
            git.reset().setMode(ResetCommand.ResetType.HARD).call()

            repo.config.apply {
                setString("branch", branch, "remote", REMOTE)
                setString("branch", branch, "merge", Constants.R_HEADS + branch)
                save()
            }
            log("Linked to ${remote.abbreviate(7).name()}. Future updates only download changes.")
            updated(newCommits = 1, changed = emptyList(), stashKept = false, everything = true)
        }

    private fun updated(
        newCommits: Int,
        changed: List<String>,
        stashKept: Boolean,
        everything: Boolean = false,
    ): UpdateResult.Updated =
        UpdateResult.Updated(
            newCommits = newCommits,
            changedPaths = changed,
            launcherChanged = everything || changed.any(::isLauncherPath),
            needsCacheRebuild = everything || changed.any(::affectsGame),
            localChangesStashed = stashKept,
        )

    private fun merge(git: Git): MergeResult {
        val result =
            git.merge()
                .include(git.repository.exactRef(remoteRef))
                .setFastForward(MergeCommand.FastForwardMode.FF)
                .setCommit(false)
                .call()
        if (result.mergeStatus == MergeResult.MergeStatus.MERGED_NOT_COMMITTED) {
            // Unsigned on purpose: a global commit.gpgsign setting would otherwise fail the update.
            git.commit().setMessage("Merge updates from $branch").setSign(false).call()
        }
        return result
    }

    private fun updateSource(git: Git): String =
        git.repository.config.getString("remote", REMOTE, "url")?.takeIf(::isAnonymousTransport)
            ?: repositoryUrl

    private fun fetch(git: Git) {
        git.fetch()
            .setRemote(updateSource(git))
            .setRefSpecs(RefSpec("+refs/heads/$branch:$remoteRef"))
            .setTimeout(FETCH_TIMEOUT_SECONDS)
            .call()
    }

    private fun discardGeneratedChanges(git: Git) {
        val generated =
            git.status().addPath(GENERATED_GAMEVALS).call().run { modified + missing }
        if (generated.isNotEmpty()) {
            git.checkout().addPaths(generated.toList()).call()
        }
    }

    private fun stash(git: Git): ObjectId? {
        log("Setting your local changes aside while updating...")
        return git.stashCreate().setWorkingDirectoryMessage("Launcher update").call()
    }

    private fun restoreStash(git: Git): Boolean =
        try {
            git.stashApply().call()
            git.stashDrop().setStashRef(0).call()
            true
        } catch (_: StashApplyFailureException) {
            git.reset().setMode(ResetCommand.ResetType.HARD).call()
            log(
                "Your local code changes clash with the update and were kept in a git stash. " +
                    "Run `git stash pop` to get them back."
            )
            false
        }

    private fun mergeReplacingGenerated(git: Git): MergeResult =
        try {
            merge(git)
        } catch (e: CheckoutConflictException) {
            val blocking = e.conflictingPaths
            if (blocking.isEmpty() || !blocking.all(::isGenerated)) {
                throw e
            }
            blocking.forEach { root.resolve(it).deleteIfExists() }
            merge(git)
        }

    private fun mergeFailure(reason: String, files: Collection<String>): String = buildString {
        append("The update could not be applied to your local files ($reason).")
        if (files.isNotEmpty()) {
            append(" Conflicting files: ")
            append(files.sorted().take(MAX_LISTED_FILES).joinToString())
        }
    }

    private fun countCommits(repo: Repository, include: ObjectId, exclude: ObjectId?): Int =
        RevWalk(repo).use { walk ->
            walk.markStart(walk.parseCommit(include))
            exclude?.let { walk.markUninteresting(walk.parseCommit(it)) }
            walk.count()
        }

    private fun latestSubject(repo: Repository, commit: ObjectId): String =
        RevWalk(repo).use { it.parseCommit(commit).shortMessage }

    private fun changedPaths(repo: Repository, from: ObjectId, to: ObjectId): List<String> =
        repo.newObjectReader().use { reader ->
            RevWalk(repo).use { walk ->
                val oldTree = CanonicalTreeParser(null, reader, walk.parseCommit(from).tree)
                val newTree = CanonicalTreeParser(null, reader, walk.parseCommit(to).tree)
                Git(repo).diff().setOldTree(oldTree).setNewTree(newTree).call().map {
                    if (it.newPath == DiffEntry.DEV_NULL) it.oldPath else it.newPath
                }
            }
        }

    companion object {
        const val REMOTE: String = "origin"

        private const val GENERATED_GAMEVALS = ".data/gamevals"
        private const val MAX_LISTED_FILES = 10
        private const val FETCH_TIMEOUT_SECONDS = 60

        private val LAUNCHER_PATHS = listOf("tools/launcher/", "Launcher.bat", "launcher.sh")
        private val NON_GAME_PREFIXES = listOf(".github/", "openwiki/", "docs/", "tools/launcher/")

        fun isLauncherPath(path: String): Boolean = LAUNCHER_PATHS.any(path::startsWith)

        private fun isGenerated(path: String): Boolean = path.startsWith("$GENERATED_GAMEVALS/")

        fun affectsGame(path: String): Boolean =
            !path.endsWith(".md") && NON_GAME_PREFIXES.none(path::startsWith) &&
                !isLauncherPath(path)

        private fun isAnonymousTransport(url: String): Boolean =
            url.startsWith("https://") || url.startsWith("http://") || url.startsWith("file:") ||
                !url.contains(':') || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(url)

        fun fromResources(root: Path, log: (String) -> Unit): GameUpdater {
            val properties = Properties()
            val resource = GameUpdater::class.java.getResourceAsStream("/launcher.properties")
            resource?.use(properties::load)
            return GameUpdater(
                root = root,
                repositoryUrl = properties.getProperty("update.repository", DEFAULT_REPOSITORY),
                branch = properties.getProperty("update.branch", "main"),
                log = log,
            )
        }

        private const val DEFAULT_REPOSITORY =
            "https://github.com/masterxxstabs/RuneScape-classic-solo.git"
    }
}
