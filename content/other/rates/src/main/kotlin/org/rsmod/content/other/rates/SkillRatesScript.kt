package org.rsmod.content.other.rates

import com.github.michaelbull.logging.InlineLogger
import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCMType
import jakarta.inject.Inject
import org.rsmod.api.player.stat.SkillMultipliers
import org.rsmod.api.player.stat.SkillRates
import org.rsmod.api.server.config.ServerConfig
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class SkillRatesScript @Inject constructor(private val config: ServerConfig) : PluginScript() {
    private val logger = InlineLogger()

    override fun ScriptContext.startup() {
        val xp = config.gameplay.xpRates
        val gather = config.gameplay.gatherRates

        xp.multiplier?.let { requirePositive("gameplay.xp-rates.multiplier", it) }
        requirePositive("gameplay.gather-rates.success-multiplier", gather.successMultiplier)
        requireYield("gameplay.gather-rates.yield-multiplier", gather.yieldMultiplier)

        val xpSkills = xp.skills.toStatMap("gameplay.xp-rates.skills", ::requirePositive)
        val successSkills =
            gather.skills
                .mapNotNullValues { it.successMultiplier }
                .toStatMap("gameplay.gather-rates.skills.*.success-multiplier", ::requirePositive)
        val yieldSkills =
            gather.skills
                .mapNotNullValues { it.yieldMultiplier }
                .toStatMap("gameplay.gather-rates.skills.*.yield-multiplier", ::requireYield)

        SkillRates.xp = SkillMultipliers(xp.multiplier, xpSkills)
        SkillRates.gatherSuccess = SkillMultipliers(gather.successMultiplier, successSkills)
        SkillRates.gatherYield = SkillMultipliers(gather.yieldMultiplier, yieldSkills)

        logger.info {
            "Skill rates: xp=${SkillRates.xp}, gather success=${SkillRates.gatherSuccess}, " +
                "gather yield=${SkillRates.gatherYield}"
        }
    }

    private fun Map<String, Double>.toStatMap(
        path: String,
        validate: (String, Double) -> Unit,
    ): Map<String, Double> =
        entries.associate { (name, multiplier) ->
            val stat = "stat." + name.trim().lowercase().removePrefix("stat.")
            requireNotNull(RSCM.getRSCMOrNull(stat, RSCMType.STAT)) {
                "Unknown skill '$name' in $path. Use names like woodcutting, mining or prayer."
            }
            validate("$path.$name", multiplier)
            stat to multiplier
        }

    private fun <K, V, R : Any> Map<K, V>.mapNotNullValues(transform: (V) -> R?): Map<K, R> =
        mapNotNull { (key, value) -> transform(value)?.let { key to it } }.toMap()

    private fun requirePositive(path: String, value: Double) {
        require(value > 0.0) { "$path must be greater than 0, was $value" }
    }

    private fun requireYield(path: String, value: Double) {
        require(value >= 1.0) { "$path must be at least 1.0, was $value" }
    }
}
