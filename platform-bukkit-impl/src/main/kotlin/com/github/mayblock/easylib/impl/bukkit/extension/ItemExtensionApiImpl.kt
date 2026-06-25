package com.github.mayblock.easylib.impl.bukkit.extension

import com.github.mayblock.easylib.api.bukkit.extension.ItemExtensionApi
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ItemExtensionApiImpl(
    plugin: Plugin
) : ItemExtensionApi, Listener {

    // 用写入物品 PDC 的唯一 ID 作为 handler 的键。
    // 不能用 ItemStack 本身做键：ItemStack 的 equals/hashCode 按内容比较，
    // 会导致外观相同的物品互相串触发，且数量变化后又匹配不上。
    private val idKey = NamespacedKey(plugin, "easylib_item_id")

    private val invHandlers = ConcurrentHashMap<Pair<InventoryHolder, String>, InventoryClickEvent.() -> Unit>()
    private val interactHandlers = ConcurrentHashMap<String, PlayerInteractEvent.() -> Unit>()

    override fun onClick(
        holder: InventoryHolder,
        item: ItemStack,
        block: InventoryClickEvent.() -> Unit
    ) {
        invHandlers[holder to item.tagId()] = block
    }

    override fun onInteract(
        item: ItemStack,
        block: PlayerInteractEvent.() -> Unit
    ) {
        interactHandlers[item.tagId()] = block
    }

    @EventHandler
    private fun onPlayerInteract(e: PlayerInteractEvent) {
        val id = e.item?.idOrNull() ?: return
        interactHandlers[id]?.invoke(e)
    }

    @EventHandler
    private fun onInventoryClick(e: InventoryClickEvent) {
        val holder = e.clickedInventory?.holder ?: return
        val id = e.currentItem?.idOrNull() ?: return
        invHandlers[holder to id]?.invoke(e)
    }

    /**
     * 确保物品带有唯一 ID（写入 PDC），返回该 ID。
     * 若已存在则复用，保证同一物品多次注册得到相同的键。
     */
    private fun ItemStack.tagId(): String {
        val meta = itemMeta
            ?: throw IllegalArgumentException("Cannot attach a handler to an item without meta (e.g. AIR)")
        meta.persistentDataContainer.get(idKey, PersistentDataType.STRING)?.let { return it }
        val id = UUID.randomUUID().toString()
        meta.persistentDataContainer.set(idKey, PersistentDataType.STRING, id)
        itemMeta = meta
        return id
    }

    private fun ItemStack.idOrNull(): String? =
        itemMeta?.persistentDataContainer?.get(idKey, PersistentDataType.STRING)

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }
}
