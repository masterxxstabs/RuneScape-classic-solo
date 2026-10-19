package org.rsmod.api.player.skilling

import org.rsmod.api.invtx.invAdd
import org.rsmod.api.player.events.skilling.SkillingActionCompleteEvent
import org.rsmod.api.player.events.skilling.SkillingActionContext
import org.rsmod.api.player.events.skilling.SkillingProduct
import org.rsmod.api.player.events.skilling.SkillingProductPrepareEvent
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.SkillRates
import org.rsmod.api.player.stat.statAdvance
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player

public enum class SkillingAwardResult {
    Success,
    InventoryFull,
    Cancelled,
}

public fun ProtectedAccess.awardSkillingProduct(product: SkillingProduct): SkillingAwardResult {
    if (!product.isBonus) {
        publish(SkillingProductPrepareEvent(product))
    }
    if (product.cancelled) {
        return SkillingAwardResult.Cancelled
    }

    val transaction = invAdd(inv, product.item, product.count)
    if (!transaction.success) {
        return SkillingAwardResult.InventoryFull
    }

    val awarded = product.count + awardBonusYield(product)

    if (product.grantsExperience && product.experience > 0.0) {
        statAdvance(product.skill, product.experience)
    }

    player.recordSkillingProduct(product.skill, product.item, awarded)
    publish(product.toCompleteEvent(awarded))
    return SkillingAwardResult.Success
}

private fun ProtectedAccess.awardBonusYield(product: SkillingProduct): Int {
    if (product.isBonus) {
        return 0
    }
    val bonus = SkillRates.bonusYield(product.skill, product.count, random)
    if (bonus <= 0) {
        return 0
    }
    return invAdd(inv, product.item, bonus, strict = false).completed()
}

public fun Player.awardSkillingProduct(
    eventBus: EventBus,
    product: SkillingProduct,
): SkillingAwardResult {
    if (!product.isBonus) {
        eventBus.publish(SkillingProductPrepareEvent(product))
    }
    if (product.cancelled) {
        return SkillingAwardResult.Cancelled
    }

    val transaction = invAdd(inv, product.item, product.count)
    if (!transaction.success) {
        return SkillingAwardResult.InventoryFull
    }

    if (product.grantsExperience && product.experience > 0.0) {
        statAdvance(product.skill, product.experience)
    }

    recordSkillingProduct(product.skill, product.item, product.count)
    eventBus.publish(product.toCompleteEvent())
    return SkillingAwardResult.Success
}

private fun SkillingProduct.toCompleteEvent(awarded: Int = count): SkillingActionCompleteEvent =
    SkillingActionCompleteEvent(
        player = player,
        context =
            SkillingActionContext.Product(
                skill = skill,
                item = item,
                count = awarded,
                experienceGranted = if (grantsExperience) experience else 0.0,
                source = source,
                isBonus = isBonus,
            ),
    )
