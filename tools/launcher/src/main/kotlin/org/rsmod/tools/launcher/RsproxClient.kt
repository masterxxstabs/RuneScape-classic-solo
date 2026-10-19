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

class RsproxClient(private val workDir: Path, private val log: (String) -> Unit) {
    private val jar: Path = workDir.resolve("rsprox-launcher.jar")

    val isDownloaded: Boolean
        get() = jar.isRegularFile()

    fun download() {
        workDir.createDirectories()
        log("Downloading RSProx from $DOWNLOAD_URL ...")
        val client =
            HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build()
        val request = HttpRequest.newBuilder(URI.create(DOWNLOAD_URL)).GET().build()
        val temp = Files.createTempFile(workDir, "rsprox", ".part")
        try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofFile(temp))
            check(response.statusCode() == 200) {
                "RSProx download failed with HTTP ${response.statusCode()}"
            }
            Files.move(temp, jar, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.deleteIfExists()
        }
        log("RSProx downloaded to $jar")
    }

    fun launch(): Process {
        if (!isDownloaded) {
            download()
        }
        log("Opening RSProx...")
        return ProcessBuilder(ServerController.javaExecutable(), "-jar", jar.absolutePathString())
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(workDir.resolve("rsprox.log").toFile())
            .start()
    }

    companion object {
        const val DOWNLOAD_URL: String =
            "https://github.com/blurite/rsprox/releases/latest/download/rsprox-launcher.jar"
    }
}
