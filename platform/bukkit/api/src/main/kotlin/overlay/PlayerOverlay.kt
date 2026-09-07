package com.github.mayblock.easylib.platform.bukkit.api.overlay

import com.github.mayblock.easylib.base.api.event.EventSource
import com.github.mayblock.easylib.base.api.util.Destroyable
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 玩家背包覆盖层：用数据包在玩家自己的背包窗口上叠加虚拟显示并捕获交互，**非真实容器**。
 * 只做「展示 + 交互」，不与真实背包做物品转移（故无取出/放入的转移事件契约）。
 *
 * 对外只暴露订阅侧事件源（[EventSource]），可监听 [OverlayEvent]（show/hide、slot 点击/交互）。
 * 实例只应经 [PlayerOverlayFactory] 创建。
 *
 * 打开任意容器界面（箱子、工作台等，玩家自己背包视图除外）会自动隐藏覆盖层，防止绕过覆盖层
 * 直接看到/操作真实背包。
 *
 * 支持同步和异步调用。逻辑状态立即生效，显示计算与事件在工厂选定的上下文中串行执行
 * （默认 Sync）；方法返回不表示客户端已完成渲染。真实背包访问由实现局部桥接到 Sync。
 */
interface PlayerOverlay : Destroyable, EventSource<OverlayEvent> {
    /**
     * 对该玩家开启覆盖层，准备快照后计算并发送首帧。
     * @throws IllegalStateException 覆盖层已销毁
     */
    fun show(player: Player)

    /**
     * 立即移除该玩家的逻辑观察状态，并安排还原真实背包渲染；实际移除返回 true，否则 false。
     * @throws IllegalStateException 覆盖层已销毁
     */
    fun hide(player: Player): Boolean

    /**
     * 某声明槽位**共享基底**物品的拷贝；未声明或为空返回 null。
     * 不反映 `onUpdate` 对各观察者的显示结果（见 `docs/overlay.md`）。
     */
    fun getItem(index: Int): ItemStack?

    /**
     * 立即改写某声明槽位**共享基底**（存入拷贝），再重算并重绘给所有观察者；`null` 等价清空。
     * @throws IllegalArgumentException 槽位未在构建时声明
     */
    fun setItem(index: Int, item: ItemStack?)

    companion object {
        /** 覆盖层窗口格数：玩家背包窗口的 46 格（合成格 + 盔甲 + 主背包 + 热键栏 + 副手）。 */
        const val OVERLAY_SIZE = 46
    }
}
