package com.github.mayblock.easylib.base.impl.bukkit.item

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
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

class CustomItemRegistryImpl(
    plugin: Plugin,
) : CustomItemRegistry, Listener {

    private val items = mutableMapOf<NamespacedKey, CustomItemImpl>()

    override fun define(
        type: Material,
        key: NamespacedKey,
        block: (CustomItemScope.() -> Unit)?
    ): CustomItem {
        val scope = CustomItemScopeImpl().apply {
            block?.invoke(this)
        }
        val item = CustomItemImpl(key, type, scope.metadata, scope.handlers())
        // 先建后注册：失败路径（重复 key）不产生任何副作用。
        require(items.putIfAbsent(key, item) == null) { "Custom item already defined: $key" }
        return item
    }

    override fun get(key: NamespacedKey): CustomItem? = items[key]
    override fun fromStack(stack: ItemStack): CustomItem? = lookup(stack)

    internal fun lookup(stack: ItemStack): CustomItemImpl? =
        stack.itemMeta?.itemModel?.let(items::get)

    override fun isRegistered(key: NamespacedKey): Boolean = items.containsKey(key)
    override fun unregister(key: NamespacedKey): Boolean = items.remove(key) != null
    override fun unregisterAll() {
        items.clear()
    }

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
        val item = e.item?.let(::lookup) ?: return
        // 本库自定义物品不可被放置为方块：可放置材质右键方块时，交互阶段即拒绝物品使用，
        // 原版放置流程不会启动（也不产生 BlockPlaceEvent）。「放置类」需求由调用方在 onInteract 中自行实现。
        if (e.action == Action.RIGHT_CLICK_BLOCK && item.type.isBlock) {
            e.setUseItemInHand(Event.Result.DENY)
        }
        val handler = item.handlers.interact ?: return
        InteractionContext(e, item).handler()
    }

    @EventHandler(ignoreCancelled = true)
    private fun onInventoryClick(e: InventoryClickEvent) {
        if (e.whoClicked !is Player) return
        val item = e.currentItem?.let(::lookup) ?: return
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
    private fun onConsume(e: PlayerItemConsumeEvent) {
        val item = lookup(e.item) ?: return
        val handler = item.handlers.consume ?: return       // 未注册：默认允许食用
        ConsumeContext(e, item).handler()
    }

    @EventHandler(ignoreCancelled = true)
    private fun onBlockPlace(e: BlockPlaceEvent) {
        // 纵深防御：交互阶段的 DENY 被第三方插件放行时，放置事件仍一律取消，
        // 防止可放置材质的自定义物品被放置后丢失 PDC 身份。
        if (lookup(e.itemInHand) == null) return
        e.isCancelled = true
    }
}
