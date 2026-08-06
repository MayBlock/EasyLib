package com.github.mayblock.easylib.base.impl.bukkit.menu.type.chest

import com.github.mayblock.easylib.api.bukkit.menu.type.chest.ChestMenuType
import com.github.mayblock.easylib.base.api.util.Disposable
import com.github.mayblock.easylib.base.impl.bukkit.util.isEmptyStack
import com.github.mayblock.easylib.base.impl.bukkit.util.stack
import com.github.mayblock.easylib.packetevents.api.PacketManager
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

/**
 * 真实容器版箱子菜单的容器视图：chest 与客户端的全部交互（真实容器持有、物品读写、hide
 * 遮罩发包）收敛于此，对标 [com.github.mayblock.easylib.base.impl.bukkit.overlay.transport.OverlayTransport] 的职责位。
 *
 * 刻意是具体类而非接口——与 overlay 侧不同的取舍：MockBukkit 已能直接测真实容器，
 * [PacketManager] 也已可注入 mock，测试接缝已经存在，无需再引入接口这一层抽象；
 * 未来铁砧等新 UI 类型的扩展点在 [com.github.mayblock.easylib.base.impl.bukkit.menu.BukkitMenu] 接口
 * （新协调者 + 新视图类），不在本类的抽象层级上。
 */
internal class RealChestView(
    holder: InventoryHolder,
    type: ChestMenuType,
    title: Component,
    private val packetManager: PacketManager<*>,
) {
    /** 顶部容器尺寸，供范围校验与背包区（>= topSize）判定使用。 */
    val topSize: Int = type.size

    val inventory: Inventory =
        Bukkit.createInventory(holder, type.size, LegacyComponentSerializer.legacySection().serialize(title))

    fun open(player: Player) {
        player.openInventory(inventory) // 触发 InventoryOpenEvent → 监听器 handleOpen
    }

    /** 含 clone：调用方修改返回值不应波及真实容器，写入请走 [setItem]（原 RealChestMenu.getItem 语义与范围校验）。 */
    fun getItem(index: Int): ItemStack? {
        require(index in 0 until topSize) { "slot $index out of range [0, $topSize)" }
        return inventory.getItem(index)?.takeUnless { it.isEmptyStack() }?.clone()
    }

    fun setItem(index: Int, item: ItemStack?) {
        require(index in 0 until topSize) { "slot $index out of range [0, $topSize)" }
        inventory.setItem(index, item ?: stack(Material.AIR))
    }

    /**
     * CraftBukkit 的 Inventory.getViewers() 返回的是底层容器持有的 live 列表：closeInventory()
     * 会同步地把玩家从该列表移除，若直接 forEach 遍历原列表，会在遍历过程中发生结构性修改，
     * 抛出 ConcurrentModificationException。因此先复制一份快照再关闭。
     */
    fun closeAll() {
        inventory.viewers.toList().forEach { it.closeInventory() }
    }

    fun refreshBottom(player: Player) {
        player.updateInventory()
    }

    /**
     * hide 模式：注册发包拦截，对容器窗口（windowId != 0）的 WINDOW_ITEMS / SET_SLOT 包中
     * 玩家背包区（>= topSize）的物品替换为空气，只对 [isViewer] 判定为真的玩家生效。
     * 与 PacketOverlayTransport 相同模式，但 windowId 判定相反。
     *
     * 不再懒初始化：packetManager 已经过构造注入，MockBukkit 环境下可直接 mock，
     * 不存在原实现「避免构造期访问 BukkitEasyLib.api」需要规避的问题。
     */
    fun attachHideMask(isViewer: (Player) -> Boolean): Disposable =
        packetManager.registerListener(object : PacketListener {
            override fun onPacketSend(e: PacketSendEvent) {
                val player = e.getPlayer() as? Player ?: return
                if (!isViewer(player)) return
                when (e.packetType) {
                    PacketType.Play.Server.WINDOW_ITEMS -> {
                        val packet = WrapperPlayServerWindowItems(e)
                        if (packet.windowId == 0) return
                        val items = packet.items.toMutableList()
                        for (i in hiddenBottomIndices(topSize, items.size)) {
                            items[i] = com.github.retrooper.packetevents.protocol.item.ItemStack.EMPTY
                        }
                        packet.items = items
                    }
                    PacketType.Play.Server.SET_SLOT -> {
                        val packet = WrapperPlayServerSetSlot(e)
                        if (packet.windowId == 0) return
                        if (packet.slot >= topSize) {
                            packet.item = com.github.retrooper.packetevents.protocol.item.ItemStack.EMPTY
                        }
                    }
                }
            }
        })

    /**
     * 显示层改写（spec §7，方案 A 的唯一显示通道）：对 viewer 的容器窗口（windowId != 0）
     * WINDOW_ITEMS / SET_SLOT 包，顶部声明槽（< topSize）查 [lookup]——命中换假物品，
     * 未命中透传真实。carriedItem/光标与底部区不碰（底部归 hideMask；光标必须真实——
     * 放行取出时拿到真身正是契约）。与 [attachHideMask] 同模式：改写不自发包，
     * stateId/windowId 原样透传，天然无递归与失步风险。
     */
    fun attachDisplayMask(
        isViewer: (Player) -> Boolean,
        lookup: (viewerId: java.util.UUID, slot: Int) -> com.github.retrooper.packetevents.protocol.item.ItemStack?,
    ): Disposable =
        packetManager.registerListener(object : PacketListener {
            override fun onPacketSend(e: PacketSendEvent) {
                val player = e.getPlayer() as? Player ?: return
                if (!isViewer(player)) return
                when (e.packetType) {
                    PacketType.Play.Server.WINDOW_ITEMS -> {
                        val packet = WrapperPlayServerWindowItems(e)
                        if (packet.windowId == 0) return
                        val items = packet.items.toMutableList()
                        var changed = false
                        for (slot in 0 until minOf(topSize, items.size)) {
                            val display = lookup(player.uniqueId, slot) ?: continue
                            items[slot] = display
                            changed = true
                        }
                        if (changed) packet.items = items
                    }
                    PacketType.Play.Server.SET_SLOT -> {
                        val packet = WrapperPlayServerSetSlot(e)
                        if (packet.windowId == 0) return
                        if (packet.slot in 0 until topSize) {
                            lookup(player.uniqueId, packet.slot)?.let { packet.item = it }
                        }
                    }
                }
            }
        })
}
