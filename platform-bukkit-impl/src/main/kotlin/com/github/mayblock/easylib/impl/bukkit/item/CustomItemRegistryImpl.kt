package com.github.mayblock.easylib.impl.bukkit.item

import com.github.mayblock.easylib.api.bukkit.item.CustomItem
import com.github.mayblock.easylib.api.bukkit.item.CustomItemRegistry
import com.github.mayblock.easylib.api.bukkit.item.CustomItemScope
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin

class CustomItemRegistryImpl(
    plugin: Plugin,
) : CustomItemRegistry, Listener {

    /** 库自有的固定 PDC 键；值为物品自身的 key 字符串，跨重启稳定。 */
    private val idKey = NamespacedKey(plugin, "custom_item_key")

    private val items = mutableMapOf<String, CustomItemImpl>()

    override fun define(
        type: Material,
        key: NamespacedKey,
        block: (CustomItemScope.() -> Unit)?
    ): CustomItem {
        val scope = CustomItemScopeImpl().apply {
            block?.invoke(this)
        }
        val item = CustomItemImpl(key, type, idKey, scope.metadata, scope.handlers())
        // 先建后注册：失败路径（重复 key）不产生任何副作用。
        require(items.putIfAbsent(key.toString(), item) == null) { "Custom item already defined: $key" }
        return item
    }

    override fun get(key: NamespacedKey): CustomItem? = items[key.toString()]
    override fun fromStack(stack: ItemStack?): CustomItem? = lookup(stack)

    internal fun lookup(stack: ItemStack?): CustomItemImpl? =
        stack?.itemMeta?.persistentDataContainer
            ?.get(idKey, PersistentDataType.STRING)?.let(items::get)

    override fun isRegistered(key: NamespacedKey): Boolean = items.containsKey(key.toString())
    override fun unregister(key: NamespacedKey): Boolean = items.remove(key.toString()) != null
    override fun unregisterAll() = items.clear()

    /** 关停：清空注册并注销 Bukkit 监听器。仅供 BukkitEasyLib.close() 调用。 */
    internal fun shutdown() {
        unregisterAll()
        HandlerList.unregisterAll(this)
    }

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    @EventHandler
    private fun onInteract(e: PlayerInteractEvent) {
        if (e.useItemInHand() == Event.Result.DENY) return
        val item = lookup(e.item) ?: return
        val handler = item.handlers.interact ?: return
        InteractionContext(e, item).handler()
    }

    @EventHandler(ignoreCancelled = true)
    private fun onInventoryClick(e: InventoryClickEvent) {
        if (e.whoClicked !is Player) return
        val item = lookup(e.currentItem) ?: return
        val handler = item.handlers.click ?: return
        ClickContext(e, item).handler()
    }

    @EventHandler(ignoreCancelled = true)
    private fun onDrop(e: PlayerDropItemEvent) {
        val item = lookup(e.itemDrop.itemStack) ?: return
        val handler = item.handlers.drop ?: return          // 未注册：默认允许丢弃
        DropContext(e, item).handler()
    }

    @EventHandler(ignoreCancelled = true)
    private fun onBlockPlace(e: BlockPlaceEvent) {
        val item = lookup(e.itemInHand) ?: return
        // 未注册：默认取消放置，防止可放置材质的自定义物品被放置后丢失 PDC 身份。
        val handler = item.handlers.place ?: run { e.isCancelled = true; return }
        PlaceContext(e, item).handler()
    }
}
