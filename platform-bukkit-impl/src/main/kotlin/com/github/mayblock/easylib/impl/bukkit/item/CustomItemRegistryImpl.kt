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
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.UUID

class CustomItemRegistryImpl(
    plugin: Plugin,
) : CustomItemRegistry, Listener {

    /** 库自有的固定 PDC 键；值为物品自身的 key 字符串，跨重启稳定。 */
    private val idKey = NamespacedKey(plugin, "custom_item_key")

    private val items = mutableMapOf<String, CustomItemImpl>()

    /**
     * interact 时点的身份备忘：放置手势中若 interact 阶段的回调/其他插件改写了手部物品，
     * BlockPlaceEvent 的物品快照会变空（AIR），PDC 识别随之失效；此时按「同玩家 + 同手 + 同 tick」
     * 从本备忘恢复身份，保证 onBlockPlace 分发与未注册时的默认禁不被击穿。
     * 主线程单写单读；单字段覆盖写、按 tick 比对自然过期，无泄漏。
     */
    private class InteractMemo(val player: UUID, val world: UUID, val key: String, val hand: EquipmentSlot, val tick: Long)
    private var interactMemo: InteractMemo? = null

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
    override fun unregisterAll() {
        items.clear()
        interactMemo = null
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
        val item = lookup(e.item) ?: return
        // 放置手势的身份备忘必须先于 handler 判空：未注册 onInteract 的物品同样需要 place 兜底识别。
        if (e.action == Action.RIGHT_CLICK_BLOCK) {
            // 默认禁前移：未注册 onBlockPlace 的可放置自定义物品，交互阶段即拒绝物品使用——
            // 原版放置流程不会启动（也不产生 BlockPlaceEvent），身份保护不再依赖放置事件的物品快照。
            // place 阶段的默认取消与备忘兜底保留为纵深防御（如第三方插件在更高优先级改回 ALLOW）。
            if (item.handlers.place == null && item.type.isBlock) {
                e.setUseItemInHand(Event.Result.DENY)
            }
            // RIGHT_CLICK_BLOCK 下 e.hand 按 Bukkit 契约非空（仅 PHYSICAL 可空）；?: 仅为类型兜底。
            interactMemo = InteractMemo(
                e.player.uniqueId, e.player.world.uid, item.key.toString(),
                e.hand ?: EquipmentSlot.HAND, e.player.world.gameTime,
            )
        }
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
        val item = lookup(e.itemInHand) ?: memoLookup(e) ?: return
        // 未注册：默认取消放置，防止可放置材质的自定义物品被放置后丢失 PDC 身份。
        val handler = item.handlers.place ?: run { e.isCancelled = true; return }
        PlaceContext(e, item).handler()
    }

    /** 放置快照识别落空时的备忘回退：同玩家、同手、同 tick 才命中（见 [interactMemo]）。 */
    private fun memoLookup(e: BlockPlaceEvent): CustomItemImpl? {
        if (!e.itemInHand.type.isAir) return null   // 兜底仅针对「快照被改写清空」的失效形态
        val memo = interactMemo ?: return null
        if (memo.player != e.player.uniqueId) return null
        if (memo.world != e.player.world.uid) return null   // 多世界 gameTime 齐步走，须校验同世界
        if (memo.hand != e.hand) return null
        if (memo.tick != e.player.world.gameTime) return null
        interactMemo = null   // 一次手势至多一次放置：命中即清，收窄任何未来回归的暴露窗口
        return items[memo.key]
    }
}
