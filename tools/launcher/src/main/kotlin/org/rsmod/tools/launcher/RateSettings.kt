package org.rsmod.tools.launcher

data class RateSettings(
    val xpMultiplier: Double = 1.0,
    val gatherSuccessMultiplier: Double = 1.0,
    val gatherYieldMultiplier: Double = 1.0,
    val dropMultiplier: Double = 1.0,
    val skills: Map<String, SkillRateOverride> = emptyMap(),
)

data class SkillRateOverride(
    val xp: Double? = null,
    val gatherSuccess: Double? = null,
    val gatherYield: Double? = null,
) {
    val isEmpty: Boolean
        get() = xp == null && gatherSuccess == null && gatherYield == null
}

val ALL_SKILLS: List<String> =
    listOf(
        "attack",
        "strength",
        "defence",
        "ranged",
        "prayer",
        "magic",
        "runecrafting",
        "construction",
        "hitpoints",
        "agility",
        "herblore",
        "thieving",
        "crafting",
        "fletching",
        "slayer",
        "hunter",
        "mining",
        "smithing",
        "fishing",
        "cooking",
        "firemaking",
        "woodcutting",
        "farming",
        "sailing",
    )

val GATHER_SUCCESS_SKILLS: Set<String> = setOf("woodcutting", "mining", "fishing", "thieving")

val GATHER_YIELD_SKILLS: Set<String> = setOf("woodcutting", "mining", "fishing")

fun normalizeSkill(name: String): String = name.trim().lowercase().removePrefix("stat.")
