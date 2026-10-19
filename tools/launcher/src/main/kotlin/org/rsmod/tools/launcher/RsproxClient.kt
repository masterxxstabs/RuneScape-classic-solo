package org.rsmod.tools.launcher

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

class RsproxClient(private val workDir: Path, private val log: (String) -> Unit) {
    private val jar: Path = workDir.resolve("rsprox-launcher.jar")
    private val versionFile: Path = workDir.resolve("rsprox-version.txt")

    private val http: HttpClient = httpClient(HttpClient.Redirect.NORMAL)
    private val noRedirects: HttpClient = httpClient(HttpClient.Redirect.NEVER)

    val isDownloaded: Boolean
        get() = jar.isRegularFile()

    val installedVersion: String?
        get() = versionFile.takeIf { it.isRegularFile() }?.readText()?.trim()?.ifEmpty { null }

    /** Downloads the newest RSProx release when it differs from the installed one. */
    fun updateIfOutdated() {
        val latest = runCatching(::latestVersion).getOrNull()
        if (latest == null) {
            log("Could not check for a newer RSProx; keeping the current one.")
            return
        }
        if (isDownloaded && latest == installedVersion) {
            log("RSProx $latest is up to date.")
            return
        }
        download(latest)
    }

    fun launch(): Process {
        if (!isDownloaded) {
            download(runCatching(::latestVersion).getOrNull())
        }
        log("Opening RSProx...")
        return ProcessBuilder(ServerController.javaExecutable(), "-jar", jar.absolutePathString())
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(workDir.resolve("rsprox.log").toFile())
            .start()
    }

    private fun download(version: String?) {
        workDir.createDirectories()
        val url = version?.let { "$RELEASES_URL/download/$it/$JAR_NAME" } ?: LATEST_JAR_URL
        log("Downloading RSProx ${version.orEmpty()} from $url ...")
        val temp = Files.createTempFile(workDir, "rsprox", ".part")
        try {
            val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofFile(temp))
            check(response.statusCode() == 200) {
                "RSProx download failed with HTTP ${response.statusCode()}"
            }
            Files.move(temp, jar, StandardCopyOption.REPLACE_EXISTING)
            version?.let(versionFile::writeText)
            log("RSProx downloaded to $jar")
        } finally {
            temp.deleteIfExists()
        }
    }

    private fun latestVersion(): String {
        val request =
            HttpRequest.newBuilder(URI.create("$RELEASES_URL/latest"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build()
        val response = noRedirects.send(request, HttpResponse.BodyHandlers.discarding())
        val location =
            response.headers().firstValue("location").orElse(null)
                ?: error("No redirect for the latest RSProx release")
        return location.substringAfterLast("/tag/").ifEmpty { error("Unexpected $location") }
    }

    companion object {
        private const val RELEASES_URL = "https://github.com/blurite/rsprox/releases"
        private const val JAR_NAME = "rsprox-launcher.jar"
        private const val LATEST_JAR_URL = "$RELEASES_URL/latest/download/$JAR_NAME"

        private fun httpClient(redirect: HttpClient.Redirect): HttpClient =
            HttpClient.newBuilder()
                .followRedirects(redirect)
                .connectTimeout(Duration.ofSeconds(30))
                .build()
    }
}
