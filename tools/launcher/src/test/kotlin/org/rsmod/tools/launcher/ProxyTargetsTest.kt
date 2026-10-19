package org.rsmod.tools.launcher

import java.nio.file.Path
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProxyTargetsTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `upsert keeps other targets and replaces the one with the same name`() {
        dir.resolve(ProxyTargets.FILE_NAME)
            .writeText(
                """
                config:
                  - name: Another Server
                    jav_config_url: https://example.com/jav_config.ws
                    modulus: abc123
                  - name: OpenRune Solo
                    jav_config_url: https://client.blurite.io/jav_local_240.ws
                    modulus: old
                """
                    .trimIndent()
            )
        val targets = ProxyTargets(dir)

        targets.upsert(target(revision = "241", modulus = "f00d", port = 43594))

        val entries = targets.readEntries()
        assertEquals(listOf("Another Server", "OpenRune Solo"), entries.map { it["name"].asText() })
        assertEquals("abc123", entries[0]["modulus"].asText())
        val solo = entries[1]
        assertEquals("https://client.blurite.io/jav_local_241.ws", solo["jav_config_url"].asText())
        assertEquals("241", solo["revision"].asText())
        assertEquals("f00d", solo["modulus"].asText())
        assertEquals(43594, solo["game_server_port"].asInt())
        assertEquals(false, solo["export_binaries"].asBoolean())
    }

    @Test
    fun `bare list files and the yml extension are supported`() {
        dir.resolve(ProxyTargets.ALT_FILE_NAME)
            .writeText("- name: Old\n  jav_config_url: https://example.com/a.ws\n")
        val targets = ProxyTargets(dir)

        targets.upsert(target(revision = "241.2", modulus = "aa", port = 50000))

        assertEquals(dir.resolve(ProxyTargets.ALT_FILE_NAME), targets.file)
        val names = targets.readEntries().map { it["name"].asText() }
        assertEquals(listOf("Old", "OpenRune Solo"), names)
        assertEquals(
            "https://client.blurite.io/jav_local_241.ws",
            targets.readEntries()[1]["jav_config_url"].asText(),
        )
    }

    @Test
    fun `creates the rsprox folder when it does not exist`() {
        val targets = ProxyTargets(dir.resolve(".rsprox"))

        targets.upsert(target(revision = "241", modulus = "aa", port = 43594))

        assertEquals(listOf("OpenRune Solo"), targets.readEntries().map { it["name"].asText() })
    }

    private fun target(revision: String, modulus: String, port: Int): ProxyTarget =
        ProxyTarget("OpenRune Solo", revision = revision, modulus = modulus, gameServerPort = port)
}
