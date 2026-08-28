package com.github.mayblock.easylib.platform.bukkit.impl.item

import com.github.mayblock.easylib.platform.bukkit.api.item.CustomItem
import com.github.mayblock.easylib.platform.bukkit.impl.util.stack
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

internal class CustomItemImpl(
    override val key: NamespacedKey,
    override val type: Material,
    metadata: List<MetaRule>,
    internal val handlers: CustomItemHandlers,
) : CustomItem {

    // 身份写在用户 meta 之后，保证不会被 scope 里的定制覆盖掉。
    private val template: ItemStack = stack(type) {
        metadata.forEach { rule ->
            require(rule.type.isInstance(this)) {
                "meta<${rule.type.simpleName}> is not applicable to ${type.name} (actual ItemMeta: ${this::class.simpleName})"
            }
            rule.block(this)
        }
        itemModel = key
    }

    override fun createStack(amount: Int): ItemStack {
        require(amount > 0) { "amount must be positive: $amount" }
        return template.clone().also { it.amount = amount }
    }

    override fun matches(stack: ItemStack): Boolean =
        stack.itemMeta?.itemModel == key

    override fun give(player: Player, amount: Int) {
        require(amount > 0) { "amount must be positive: $amount" }
        val leftover = player.inventory.addItem(createStack(amount))
        leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
    }

    override fun take(player: Player, amount: Int): Boolean {
        require(amount > 0) { "amount must be positive: $amount" }
        val inventory = player.inventory
        val owned = (0 until inventory.size).sumOf { i ->
            inventory.getItem(i)?.takeIf(::matches)?.amount ?: 0
        }
        if (owned < amount) return false
        var remaining = amount
        for (i in 0 until inventory.size) {
            val s = inventory.getItem(i) ?: continue
            if (!matches(s)) continue
            val delta = minOf(s.amount, remaining)
            if (s.amount - delta <= 0) inventory.setItem(i, null)
            else inventory.setItem(i, s.clone().apply { this.amount = s.amount - delta })
            remaining -= delta
            if (remaining == 0) break
        }
        return true
    }
}
