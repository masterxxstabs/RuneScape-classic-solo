package org.rsmod.tools.launcher

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GameConfigFileTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `rates round trip and other settings are kept`() {
        val file = dir.resolve("game.yml")
        file.writeText(
            """
            name: "My Server"
            game-port: 43595
            revision: 241
            world-types: ["main"]
            gameplay:
              level-up-popups: true
              drop-rates:
                multiplier: 1.0
            """
                .trimIndent()
        )
        val config = GameConfigFile(file)
        val rates =
            RateSettings(
                xpMultiplier = 25.0,
                gatherSuccessMultiplier = 2.0,
                gatherYieldMultiplier = 1.5,
                dropMultiplier = 3.0,
                skills =
                    mapOf(
                        "prayer" to SkillRateOverride(xp = 5.0),
                        "woodcutting" to SkillRateOverride(gatherSuccess = 4.0, gatherYield = 2.0),
                    ),
            )

        config.writeRates(rates)

        assertEquals(rates, config.readRates())
        assertEquals("241", config.revision())
        assertEquals(43595, config.gamePort())
        assertEquals("My Server", config.serverName())
        val text = file.readText()
        assertTrue("level-up-popups: true" in text)
        assertTrue("- main" in text)
    }

    @Test
    fun `missing file reads defaults from the fallback and writing creates it`() {
        val example = dir.resolve("game.example.yml")
        example.writeText("revision: 241\ngameplay:\n  xp-rates:\n    multiplier: 1.0\n")
        val file = dir.resolve("game.yml")
        val config = GameConfigFile(file, fallback = example)

        assertFalse(config.exists)
        assertEquals(RateSettings(), config.readRates())

        config.writeRates(RateSettings(xpMultiplier = 10.0))

        assertTrue(config.exists)
        assertEquals(10.0, config.readRates().xpMultiplier)
        assertEquals("241", config.revision())
    }

    @Test
    fun `stat prefixed skill names are normalised`() {
        val file = dir.resolve("game.yml")
        file.writeText(
            """
            gameplay:
              xp-rates:
                skills: { stat.Prayer: 3.0 }
              gather-rates:
                skills:
                  stat.mining: { yield-multiplier: 2.0 }
            """
                .trimIndent()
        )

        val skills = GameConfigFile(file).readRates().skills

        assertEquals(SkillRateOverride(xp = 3.0), skills["prayer"])
        assertEquals(SkillRateOverride(gatherYield = 2.0), skills["mining"])
    }
}
