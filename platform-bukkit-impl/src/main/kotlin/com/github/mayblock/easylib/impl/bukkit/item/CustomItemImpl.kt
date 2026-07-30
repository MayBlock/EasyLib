package com.github.mayblock.easylib.impl.bukkit.item

import com.github.mayblock.easylib.api.bukkit.item.CustomItem
import com.github.mayblock.easylib.api.bukkit.item.CustomItemClick
import com.github.mayblock.easylib.api.bukkit.item.CustomItemInteraction
import com.github.mayblock.easylib.impl.bukkit.util.stack
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType

internal class CustomItemImpl(
    override val key: NamespacedKey,
    override val type: Material,
    private val idKey: NamespacedKey,
    metadata: List<ItemMeta.() -> Unit>,
    internal val interactHandler: (CustomItemInteraction.() -> Unit)?,
    internal val clickHandler: (CustomItemClick.() -> Unit)?,
) : CustomItem {

    // 身份写在用户 meta 之后，保证不会被 scope 里的定制覆盖掉。
    private val template: ItemStack = stack(type) {
        metadata.forEach { it(this) }
        persistentDataContainer.set(idKey, PersistentDataType.STRING, key.toString())
    }

    override fun createStack(amount: Int): ItemStack =
        template.clone().also { it.amount = amount }

    override fun matches(stack: ItemStack?): Boolean =
        stack?.itemMeta?.persistentDataContainer
            ?.get(idKey, PersistentDataType.STRING) == key.toString()

    override fun give(player: Player, amount: Int) {
        val leftover = player.inventory.addItem(createStack(amount))
        leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
    }

    override fun take(player: Player, amount: Int): Boolean {
        val contents = player.inventory.contents
        val owned = contents.filterNotNull().filter(::matches).sumOf { it.amount }
        if (owned < amount) return false
        var remaining = amount
        for (i in contents.indices) {
            val s = contents[i] ?: continue
            if (!matches(s)) continue
            val delta = minOf(s.amount, remaining)
            if (s.amount - delta <= 0) player.inventory.setItem(i, null) else s.amount -= delta
            remaining -= delta
            if (remaining == 0) break
        }
        return true
    }
}
