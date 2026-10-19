package org.rsmod.tools.launcher

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

data class ProxyTarget(
    val name: String,
    val revision: String,
    val modulus: String,
    val gameServerPort: Int,
) {
    val javConfigUrl: String
        get() = "https://client.blurite.io/jav_local_${revision.substringBefore('.')}.ws"
}

class ProxyTargets(private val directory: Path) {
    val file: Path
        get() {
            val alternative = directory.resolve(ALT_FILE_NAME)
            val primary = directory.resolve(FILE_NAME)
            val useAlternative = !primary.isRegularFile() && alternative.isRegularFile()
            return if (useAlternative) alternative else primary
        }

    fun upsert(target: ProxyTarget) {
        val existing = readEntries().filterNot { it.path("name").asText() == target.name }
        val entries = YAML.createArrayNode()
        existing.forEach(entries::add)
        entries.add(target.toNode())

        val root = YAML.createObjectNode()
        root.set<JsonNode>("config", entries)

        directory.createDirectories()
        val temp = Files.createTempFile(directory, "proxy-targets", ".tmp")
        temp.writeText(YAML.writeValueAsString(root))
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
    }

    fun readEntries(): List<JsonNode> {
        val text = file.takeIf { it.isRegularFile() }?.readText()?.takeIf { it.isNotBlank() }
        val root = text?.let(YAML::readTree) ?: return emptyList()
        val list = if (root is ArrayNode) root else root.path("config")
        return list.filterIsInstance<ObjectNode>()
    }

    private fun ProxyTarget.toNode(): ObjectNode =
        YAML.createObjectNode().apply {
            put("name", name)
            put("jav_config_url", javConfigUrl)
            put("revision", revision)
            put("modulus", modulus)
            put("varp_count", VARP_COUNT)
            put("game_server_port", gameServerPort)
            put("export_binaries", false)
        }

    companion object {
        const val FILE_NAME: String = "proxy-targets.yaml"
        const val ALT_FILE_NAME: String = "proxy-targets.yml"

        private const val VARP_COUNT = 15000

        private val YAML = GameConfigFile.YAML

        fun default(): ProxyTargets =
            ProxyTargets(Path.of(System.getProperty("user.home"), ".rsprox"))
    }
}
