package com.github.mayblock.easylib.impl.bukkit.menu

import com.github.mayblock.easylib.api.bukkit.menu.Menu
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

/**
 * impl 侧所有 Bukkit 菜单的公共契约：既是路由锚点（[InventoryHolder]——Bukkit 事件经
 * holder 找回菜单），也是 [MenuInteractionListener] 的交互分发目标。
 * 新增 UI 类型（如铁砧）：实现本接口即可自动获得事件路由，无需改动监听器。
 */
internal interface BukkitMenu : Menu, InventoryHolder {

    /**
     * 登记本菜单的 manager；由 [MenuManager] 的 register() 赋值，[MenuInteractionListener]
     * 据此校验事件归属（多 manager 防重复处理）。直接构造的测试实例可手动赋值。
     * 声明为 `var`（而非简报草图中的 `val`）是刻意取舍：manager 只持有 [BukkitMenu] 引用、
     * 对具体 UI 类型零感知，若声明为 `val` 则 register() 无法经接口类型完成赋值，
     * 只能反过来向下转型到具体实现类——恰是本次解耦要消除的耦合。
     */
    var owner: MenuManager?

    fun handleOpen(player: Player)
    fun handleClick(e: InventoryClickEvent)
    fun handleDrag(e: InventoryDragEvent)
    fun handleClose(player: Player)

    /**
     * 某槽位的当前物品：读真实容器并返回一份拷贝（修改返回值不会影响菜单内容，如需写入请调用 [setItem]）；
     * 槽位为空（AIR/数量≤0）返回 null。
     * @throws IllegalArgumentException 槽位索引越界（超出菜单容量）
     */
    fun getItem(index: Int): ItemStack?

    /**
     * 改写某槽位的物品（直接写真实共享容器，所有观看者立即可见）；`null` 等价于清空（AIR）。
     * @throws IllegalArgumentException 槽位索引越界（超出菜单容量）
     */
    fun setItem(index: Int, item: ItemStack?)
}
