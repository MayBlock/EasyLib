package com.github.mayblock.easylib.impl.bukkit.extension

import com.github.mayblock.easylib.api.bukkit.extension.ItemExtensionApi
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

class ItemExtensionApiImpl(
    plugin: Plugin
) : ItemExtensionApi, Listener {

    private val invHandlers = mutableMapOf<Pair<InventoryHolder, ItemStack>, InventoryClickEvent.() -> Unit>()
    private val interactHandlers = mutableMapOf<ItemStack, PlayerInteractEvent.() -> Unit>()

    override fun onClick(
        holder: InventoryHolder,
        item: ItemStack,
        block: InventoryClickEvent.() -> Unit
    ) {
        invHandlers[Pair(holder, item)] = block
    }

    override fun onInteract(
        item: ItemStack,
        block: PlayerInteractEvent.() -> Unit
    ) {
        interactHandlers[item] = block
    }

    @EventHandler
    private fun onPlayerInteract(e: PlayerInteractEvent) {
        val item = e.item ?: return
        interactHandlers[item]?.invoke(e)
    }

    @EventHandler
    private fun onPlayerInteract(e: InventoryClickEvent) {
        val holder = e.clickedInventory as? InventoryHolder ?: return
        val item = e.currentItem ?: return
        invHandlers[Pair(holder, item)]?.invoke(e)
    }

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

//    init {
//        BukkitEasyLib.api.packetManager.registerListener(object : PacketListener {
//            override fun onPacketReceive(e: PacketReceiveEvent) {
//                val player = e.getPlayer<Player>()
//                val isSuccess = when (e.packetType) {
//                    PacketType.Play.Client.CLICK_WINDOW -> handleClickWindow(
//                        player,
//                        WrapperPlayClientClickWindow(e)
//                    )
//                    PacketType.Play.Client.ATTACK -> {
//                        handleInteract(player, ClickType.LEFT)
//                    }
//                    PacketType.Play.Client.INTERACT_ENTITY -> {
//                        val packet = WrapperPlayClientInteractEntity(e)
//                        val clickType = if (packet.action == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
//                            ClickType.LEFT
//                        } else ClickType.RIGHT
//                        handleInteract(player, clickType)
//                    }
//                    else -> false
//                }
//                e.isCancelled = isSuccess
//            }
//        })
//    }
//
//    fun handleClickWindow(player: Player, packet: WrapperPlayClientClickWindow): Boolean {
//        val windowId = packet.windowId
//        if (windowId != 0 && windowId != -999) return false
//        val slot = packet.slot
//        val clickedItem = packet.hashedSlots[slot]?.getOrNull()?.asItemStack()?.let {
//            SpigotConversionUtil.toBukkitItemStack(it)
//        } ?: return false
//        val clickType = packet.windowClickType.toBukkitClickType(packet.button)
//        val clickEvent = ItemExtension.ClickEvent(
//            player = player,
//            type = clickType,
//            slot = packet.slot
//        )
//        return listeners[clickedItem]?.invoke(clickEvent) != null
//    }
//
//    fun handleInteract(player: Player, clickType: ClickType): Boolean {
//        val item = player.inventory.itemInMainHand
//        val clickEvent = ItemExtension.ClickEvent(
//            player = player,
//            type = clickType,
//            slot = player.inventory.heldItemSlot
//        )
//        return listeners[item]?.invoke(clickEvent) != null
//    }
//
//    fun WrapperPlayClientClickWindow.WindowClickType.toBukkitClickType(
//        button: Int
//    ): ClickType = when (this) {
//        WrapperPlayClientClickWindow.WindowClickType.PICKUP -> {
//            if (button == 0) ClickType.LEFT
//            if (button == 1) ClickType.RIGHT
//            ClickType.UNKNOWN
//        }
//
//        WrapperPlayClientClickWindow.WindowClickType.QUICK_MOVE -> {
//            if (button == 0) ClickType.SHIFT_LEFT
//            if (button == 1) ClickType.SHIFT_RIGHT
//            ClickType.UNKNOWN
//        }
//
//        WrapperPlayClientClickWindow.WindowClickType.SWAP -> ClickType.NUMBER_KEY
//        WrapperPlayClientClickWindow.WindowClickType.CLONE -> ClickType.MIDDLE
//        WrapperPlayClientClickWindow.WindowClickType.THROW -> {
//            if (button == 0) ClickType.DROP
//            if (button == 1) ClickType.CONTROL_DROP
//            ClickType.UNKNOWN
//        }
//        WrapperPlayClientClickWindow.WindowClickType.PICKUP_ALL -> ClickType.DOUBLE_CLICK
//        else -> ClickType.UNKNOWN
//    }
}