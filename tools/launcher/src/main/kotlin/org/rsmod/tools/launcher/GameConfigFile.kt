package org.rsmod.tools.launcher

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

class GameConfigFile(val path: Path, private val fallback: Path? = null) {
    val exists: Boolean
        get() = path.isRegularFile()

    fun readRates(): RateSettings {
        val gameplay = readTree().path("gameplay")
        val xp = gameplay.path("xp-rates")
        val gather = gameplay.path("gather-rates")

        val skills = mutableMapOf<String, SkillRateOverride>()
        for ((name, value) in xp.path("skills").properties()) {
            val skill = normalizeSkill(name)
            val current = skills.getOrDefault(skill, SkillRateOverride())
            skills[skill] = current.copy(xp = value.asDouble())
        }
        for ((name, value) in gather.path("skills").properties()) {
            val skill = normalizeSkill(name)
            skills[skill] =
                skills.getOrDefault(skill, SkillRateOverride()).copy(
                    gatherSuccess = value.optionalDouble("success-multiplier"),
                    gatherYield = value.optionalDouble("yield-multiplier"),
                )
        }

        return RateSettings(
            xpMultiplier = xp.path("multiplier").asDouble(1.0),
            gatherSuccessMultiplier = gather.path("success-multiplier").asDouble(1.0),
            gatherYieldMultiplier = gather.path("yield-multiplier").asDouble(1.0),
            dropMultiplier = gameplay.path("drop-rates").path("multiplier").asDouble(1.0),
            skills = skills.filterValues { !it.isEmpty },
        )
    }

    fun writeRates(rates: RateSettings) {
        val root = readTree() as? ObjectNode ?: YAML.createObjectNode()
        val gameplay = root.withObjectProperty("gameplay")

        val xpSkills = YAML.createObjectNode()
        val gatherSkills = YAML.createObjectNode()
        for ((skill, override) in rates.skills.toSortedMap()) {
            override.xp?.let { xpSkills.put(skill, it) }
            if (override.gatherSuccess != null || override.gatherYield != null) {
                val node = gatherSkills.putObject(skill)
                override.gatherSuccess?.let { node.put("success-multiplier", it) }
                override.gatherYield?.let { node.put("yield-multiplier", it) }
            }
        }

        gameplay.withObjectProperty("xp-rates").apply {
            put("multiplier", rates.xpMultiplier)
            set<JsonNode>("skills", xpSkills)
        }
        gameplay.withObjectProperty("gather-rates").apply {
            put("success-multiplier", rates.gatherSuccessMultiplier)
            put("yield-multiplier", rates.gatherYieldMultiplier)
            set<JsonNode>("skills", gatherSkills)
        }
        gameplay.withObjectProperty("drop-rates").put("multiplier", rates.dropMultiplier)
        write(root)
    }

    /** Copies `revision` from [source]; returns `true` when this file was on another revision. */
    fun syncRevisionFrom(source: GameConfigFile): Boolean {
        val revision = source.readTree().get("revision") ?: return false
        val root = readTree() as? ObjectNode ?: return false
        if (root.get("revision") == revision) {
            return false
        }
        root.set<JsonNode>("revision", revision)
        write(root)
        return true
    }

    fun revision(): String = readTree().path("revision").asText(DEFAULT_REVISION)

    fun gamePort(): Int = readTree().path("game-port").asInt(DEFAULT_GAME_PORT)

    fun serverName(): String = readTree().path("name").asText(DEFAULT_NAME)

    private fun write(root: JsonNode) {
        val temp = Files.createTempFile(path.toAbsolutePath().parent, "game", ".yml.tmp")
        temp.writeText(HEADER + YAML.writeValueAsString(root))
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun readTree(): JsonNode {
        val source = path.takeIf { it.isRegularFile() } ?: fallback?.takeIf { it.isRegularFile() }
        val text = source?.readText()?.takeIf { it.isNotBlank() } ?: return YAML.createObjectNode()
        return YAML.readTree(text) ?: YAML.createObjectNode()
    }

    private fun JsonNode.optionalDouble(field: String): Double? =
        get(field)?.takeIf { it.isNumber }?.asDouble()

    companion object {
        const val DEFAULT_REVISION: String = "241"
        const val DEFAULT_GAME_PORT: Int = 43594
        const val DEFAULT_NAME: String = "OpenRune"

        private const val HEADER: String =
            "# Rates in this file are managed by the launcher (tools/launcher).\n" +
                "# Every key is documented in game.example.yml.\n"

        val YAML: ObjectMapper =
            ObjectMapper(
                YAMLFactory.builder()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                    .enable(YAMLGenerator.Feature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)
                    .enable(YAMLGenerator.Feature.INDENT_ARRAYS_WITH_INDICATOR)
                    .build()
            )
    }
}
